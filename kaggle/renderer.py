"""renderer.py — Pixel-perfect Playwright frame rendering.

Captures HTML animation frames with deterministic Chromium settings.
Enforces exact 16:9 clip bounds with integer CSS pixels.
Reports progress via callback for real-time telemetry.
"""
import os
import time
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from playwright.sync_api import sync_playwright, TimeoutError as PWTimeout

from config import CHROMIUM_FLAGS, CPU_FALLBACK_FLAGS


class RenderError(Exception):
    """Raised when rendering fails."""
    pass


def detect_content_bounds(page):
    """Detect the renderable content area in the page."""
    bounds = page.evaluate("""() => {
        const el = document.querySelector('.fit')
            || document.querySelector('#video-frame')
            || [...document.querySelectorAll('div')].find(d => {
                const r = d.getBoundingClientRect();
                return r.width > 100 && Math.abs(r.width / r.height - 16/9) < 0.01
                    && !!d.querySelector('.stage');
            });
        if (!el) return null;
        const r = el.getBoundingClientRect();
        return {x: r.left, y: r.top, w: r.width, h: r.height};
    }""")

    if not bounds:
        bounds = page.evaluate("""() => {
            const els = document.body ? document.body.querySelectorAll('*') : [];
            let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
            for (const el of els) {
                if (el.offsetParent === null && getComputedStyle(el).position !== 'fixed') continue;
                const r = el.getBoundingClientRect();
                if (r.width < 1 || r.height < 1) continue;
                minX = Math.min(minX, r.left); minY = Math.min(minY, r.top);
                maxX = Math.max(maxX, r.right); maxY = Math.max(maxY, r.bottom);
            }
            if (minX === Infinity) return null;
            return {x: minX, y: minY, w: maxX - minX, h: maxY - minY};
        }""")

    if not bounds:
        raise RenderError("FRAME_NOT_FOUND: no content bounds detected")

    return bounds


def enforce_16_9(bounds, vw=1920, vh=1080):
    """Smallest 16:9 window that CONTAINS the bounds (expand, never crop),
    centered on the bounds, clamped inside the viewport."""
    target = 16.0 / 9.0
    bx, by, bw, bh = (int(round(bounds[k])) for k in ("x", "y", "w", "h"))

    # If the author frame essentially fills the viewport, capture it exactly
    if bw >= vw * 0.98 and bh >= vh * 0.98:
        return {"x": 0, "y": 0, "w": vw, "h": vh}

    cx, cy = bx + bw / 2.0, by + bh / 2.0
    w = float(bw)
    h = w / target
    if h < bh:          # too tall: widen so nothing is cut vertically
        h = float(bh)
        w = h * target
    if w > vw:          # cap at viewport
        w = float(vw)
        h = w / target
    if h > vh:
        h = float(vh)
        w = h * target
    w = int(round(w))
    h = int(round(h))
    x = int(round(cx - w / 2.0))
    y = int(round(cy - h / 2.0))
    x = max(0, min(x, vw - w))
    y = max(0, min(y, vh - h))
    return {"x": x, "y": y, "w": w, "h": h}


def extract_sfx_manifest(page):
    """Extract SFX events from the HTML's #sfx JSON block."""
    return page.evaluate("""(() => {
        const el = document.querySelector('script#sfx[type="application/json"]');
        if (!el) return {events: []};
        try {
            const d = JSON.parse(el.textContent);
            const evs = Array.isArray(d) ? d : (d.events || []);
            return {events: evs.map(e => ({
                t: +e.t,
                id: String(e.id || 'tick'),
                gain: Math.min(1, Math.max(0, +e.gain || 0.7))
            }))};
        } catch(err) { return {events: []}; }
    })()""")


def extract_ambience_manifest(page):
    """Extract ambience type from the HTML's #ambience JSON block."""
    return page.evaluate("""(() => {
        const el = document.querySelector('script#ambience[type="application/json"]');
        if (!el) return null;
        try {
            const d = JSON.parse(el.textContent);
            return {type: String(d.type || 'drone')};
        } catch(err) { return null; }
    })()""")


WORKER_COUNT = max(1, min(4, os.cpu_count() or 2))
# Global cap on concurrent frame captures across ALL jobs, so two queued
# jobs cannot oversubscribe the cores with 2x workers.
_WORK_SEM = threading.Semaphore(WORKER_COUNT)


def _launch_browser(p):
    """Launch Chromium with GPU flags; fall back to CPU-only flags."""
    try:
        browser = p.chromium.launch(headless=True, args=CHROMIUM_FLAGS)
        print("[render] Chromium launched with Vulkan/GPU flags", flush=True)
        return browser
    except Exception:
        import traceback
        print("[render] GPU launch failed; falling back to CPU", flush=True)
        traceback.print_exc()
        return p.chromium.launch(headless=True, args=CPU_FALLBACK_FLAGS)


def _prep_page(browser, html_path, width, height, enhance):
    """Load page, wait for fonts/images, pause animations, apply enhance."""
    page = browser.new_page(
        viewport={"width": width, "height": height},
        device_scale_factor=1
    )
    # Freeze animations before the page's own document parses, so real
    # wall-clock time during networkidle/font/image waits can never let a
    # short-delay animation actually run and finish before capture starts.
    page.add_init_script("""
        (() => {
            const s = document.createElement('style');
            s.textContent = '*,*::before,*::after{animation-play-state:paused!important;animation-fill-mode:both!important}';
            document.documentElement.appendChild(s);
        })();
    """)
    page.goto(f"file://{html_path}", wait_until="networkidle")
    try:
        page.wait_for_function("document.fonts.status === 'loaded'", timeout=15000)
        page.evaluate("""async () => {
            const faces = [...document.fonts];
            await Promise.all(faces.map(f =>
                document.fonts.load(f.style + ' ' + f.weight + ' 16px "' + f.family + '"')
            ));
            await document.fonts.ready;
        }""")
    except PWTimeout:
        pass
    # Preload CSS background-image URLs so they get the same explicit
    # decode wait as <img> tags (networkidle alone can race slow CDNs).
    page.evaluate("""() => {
        window.__bgImgs = {};
        const urls = new Set();
        for (const el of document.querySelectorAll('*')) {
            const bg = getComputedStyle(el).backgroundImage || '';
            for (const part of bg.split('url(').slice(1)) {
                const q = part[0];
                const quoted = (q === '"' || q === "'");
                const end = quoted ? part.indexOf(q, 1) : part.indexOf(')');
                if (end > 0) urls.add(part.slice(quoted ? 1 : 0, end));
            }
        }
        window.__bgUrls = [...urls];
        for (const u of window.__bgUrls) {
            const im = new Image();
            im.src = u;
            window.__bgImgs[u] = im;
        }
    }""")
    try:
        page.wait_for_function("""() => {
            for (const img of document.querySelectorAll('img')) {
                if (!img.complete || img.naturalWidth === 0) return false;
            }
            for (const u of (window.__bgUrls || [])) {
                const im = window.__bgImgs[u];
                if (!im || !im.complete || im.naturalWidth === 0) return false;
            }
            return true;
        }""", timeout=15000)
    except PWTimeout:
        pass
    try:
        broken = page.evaluate("""() => {
            const bad = [];
            for (const img of document.querySelectorAll('img')) {
                if (!img.complete || img.naturalWidth === 0) bad.push(img.src.slice(0, 80));
            }
            for (const u of (window.__bgUrls || [])) {
                const im = window.__bgImgs[u];
                if (!im || !im.complete || im.naturalWidth === 0) bad.push(('bg:' + u).slice(0, 80));
            }
            return bad;
        }""")
        if broken:
            print(f"[render] WARNING: {len(broken)} image(s) failed to load: {broken[:5]}", flush=True)
    except Exception:
        pass
    if enhance:
        page.evaluate("() => { document.documentElement.style.filter = 'saturate(1.18) contrast(1.12)'; }")
    return page


def _gl_renderer(page):
    """Print the actual GL renderer string (NVIDIA T4 vs SwiftShader)."""
    try:
        r = page.evaluate("""() => {
            const c = document.createElement('canvas');
            const gl = c.getContext('webgl');
            if (!gl) return 'NO_WEBGL';
            const d = gl.getExtension('WEBGL_debug_renderer_info');
            return d ? gl.getParameter(d.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER);
        }""")
        print(f"[render] GL_RENDERER: {r}", flush=True)
    except Exception as ge:
        print(f"[render] GL_RENDERER check failed: {ge}", flush=True)


# ─── PARALLEL FRAME CAPTURE ──────────────────────────────────────────────────

def _render_slice(html_path, width, height, enhance, clip, frames_dir, fps,
                  frame_ids, cancel_event, stop_event, on_frame):
    """One worker: own browser, seeks + screenshots its contiguous frame range."""
    with sync_playwright() as p:
        browser = _launch_browser(p)
        try:
            page = _prep_page(browser, html_path, width, height, enhance)
            t0 = time.time()
            for i in frame_ids:
                if cancel_event.is_set() or stop_event.is_set():
                    raise RenderError("CANCELLED")
                t_ms = (i / fps) * 1000
                while not _WORK_SEM.acquire(timeout=0.5):
                    if cancel_event.is_set() or stop_event.is_set():
                        raise RenderError("CANCELLED")
                try:
                    page.evaluate(
                        """(t) => {
                            if (window.__broll && window.__broll.seek) window.__broll.seek(t / 1000);
                            for (const a of document.getAnimations()) {
                                a.pause();
                                a.currentTime = t;
                            }
                            void document.body.offsetHeight;
                        }""",
                        t_ms,
                    )
                    page.screenshot(
                        path=os.path.join(frames_dir, f"frame_{i:05d}.png"),
                        type="png",
                        clip={
                            "x": clip["x"],
                            "y": clip["y"],
                            "width": clip.get("width", clip.get("w")),
                            "height": clip.get("height", clip.get("h")),
                        }
                    )
                finally:
                    _WORK_SEM.release()
                on_frame()
            dt = max(time.time() - t0, 1e-6)
            print(f"[render] worker: {len(frame_ids)} frames in {dt:.1f}s "
                  f"({len(frame_ids)/dt:.1f} fps)", flush=True)
        finally:
            browser.close()


def render_frames(html_path, frames_dir, fps, resolution, duration, enhance,
                  cancel_event, progress_cb=None):
    """Render all frames: scout pass for manifests/clip, then N parallel workers."""
    os.makedirs(frames_dir, exist_ok=True)
    width, height = map(int, resolution.split("x"))
    total_frames = int(fps * duration)

    # Scout pass: single browser computes manifests + clip bounds + GPU probe
    with sync_playwright() as p:
        browser = _launch_browser(p)
        try:
            page = _prep_page(browser, html_path, width, height, enhance)
            _gl_renderer(page)
            sfx_manifest = extract_sfx_manifest(page)
            amb_manifest = extract_ambience_manifest(page)
            raw_bounds = detect_content_bounds(page)
            clip = enforce_16_9(raw_bounds, width, height)
            print(f"[render] raw_bounds={raw_bounds} clip={clip}", flush=True)
        finally:
            browser.close()

    workers = max(1, min(WORKER_COUNT, total_frames))
    step = max(1, (total_frames + workers - 1) // workers)
    slices = [list(range(s, min(s + step, total_frames)))
              for s in range(0, total_frames, step)]
    print(f"[render] parallel workers: {workers} "
          f"({[len(s) for s in slices]} frames each)", flush=True)

    done = [0]
    lock = threading.Lock()
    stop_event = threading.Event()

    def on_frame():
        with lock:
            done[0] += 1
            d = done[0]
        if progress_cb:
            progress_cb(d, total_frames)

    with ThreadPoolExecutor(max_workers=workers) as ex:
        futs = [ex.submit(_render_slice, html_path, width, height, enhance,
                          clip, frames_dir, fps, sl, cancel_event, stop_event,
                          on_frame) for sl in slices]
        err = None
        for f in as_completed(futs):
            try:
                f.result()
            except Exception as e:
                stop_event.set()
                if err is None:
                    err = e
    if err is not None:
        raise err if isinstance(err, RenderError) else RenderError(str(err))

    return {
        "sfx_events": sfx_manifest.get("events", []),
        "ambience_type": amb_manifest.get("type") if amb_manifest else None,
        "clip_bounds": clip,
        "total_frames": total_frames
    }
