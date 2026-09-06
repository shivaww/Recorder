"""renderer.py — Pixel-perfect Playwright frame rendering.

Captures HTML animation frames with deterministic Chromium settings.
Enforces exact 16:9 clip bounds with integer CSS pixels.
Reports progress via callback for real-time telemetry.
"""
import os
import time
from playwright.sync_api import sync_playwright, TimeoutError as PWTimeout

from config import CHROMIUM_FLAGS


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


def enforce_16_9(bounds):
    """Snap bounds to exact 16:9 with integer pixels, centered."""
    w = bounds["w"]
    h = bounds["h"]
    target_ratio = 16.0 / 9.0
    current_ratio = w / h

    if current_ratio > target_ratio:
        new_w = int(h * target_ratio)
        x_offset = int((w - new_w) / 2)
        return {"x": int(bounds["x"]) + x_offset, "y": int(bounds["y"]),
                "w": new_w, "h": int(h)}
    else:
        new_h = int(w / target_ratio)
        y_offset = int((h - new_h) / 2)
        return {"x": int(bounds["x"]), "y": int(bounds["y"]) + y_offset,
                "w": int(w), "h": new_h}


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


def render_frames(html_path, frames_dir, fps, resolution, duration, enhance,
                  cancel_event, progress_cb=None):
    """Render all frames from HTML animation.

    Args:
        html_path: path to the HTML file
        frames_dir: directory to write PNG frames
        fps: frames per second
        resolution: "WxH" string
        duration: seconds
        enhance: bool — apply contrast/saturation boost
        cancel_event: threading.Event to signal cancellation
        progress_cb: callback(frame, total_frames) for telemetry

    Returns:
        dict with sfx_events, ambience_type, clip_bounds

    Raises:
        RenderError on failure
    """
    os.makedirs(frames_dir, exist_ok=True)
    width, height = map(int, resolution.split("x"))
    total_frames = int(fps * duration)

    with sync_playwright() as p:
        browser = p.chromium.launch(args=CHROMIUM_FLAGS)
        page = browser.new_page(
            viewport={"width": width, "height": height},
            device_scale_factor=1
        )
        page.goto(f"file://{html_path}", wait_until="networkidle")

        # Wait for fonts
        try:
            page.wait_for_function("document.fonts.status === 'loaded'", timeout=15000)
        except PWTimeout:
            pass  # Continue anyway, fonts may be system fallbacks

        # Wait for images
        try:
            page.wait_for_function("""() => {
                const imgs = document.querySelectorAll('img');
                if (imgs.length === 0) return true;
                for (const img of imgs) {
                    if (!img.complete || img.naturalWidth === 0) return false;
                }
                return true;
            }""", timeout=15000)
        except PWTimeout:
            pass

        # Extract SFX and ambience manifests
        sfx_manifest = extract_sfx_manifest(page)
        amb_manifest = extract_ambience_manifest(page)

        # Detect and enforce 16:9 clip bounds
        raw_bounds = detect_content_bounds(page)
        clip = enforce_16_9(raw_bounds)

        # Apply enhance filter if requested
        if enhance:
            page.evaluate("() => { document.documentElement.style.filter = 'saturate(1.18) contrast(1.12)'; }")

        # Pause all animations and prepare for frame-by-frame seek
        page.evaluate("""() => {
            const s = document.createElement('style');
            s.textContent = '*,*::before,*::after{animation-play-state:paused!important;animation-fill-mode:both!important}';
            document.head.appendChild(s);
            window.__a = document.getAnimations();
        }""")

        # Render each frame
        for i in range(total_frames):
            if cancel_event.is_set():
                browser.close()
                raise RenderError("CANCELLED")

            t_ms = (i / fps) * 1000
            seek_js = (
                f"window.__a.forEach(a => a.currentTime = {t_ms}); "
                f"if(window.__broll && window.__broll.seek) window.__broll.seek({t_ms}/1000); "
                f"void document.body.offsetHeight"
            )
            page.evaluate(seek_js)

            page.screenshot(
                path=os.path.join(frames_dir, f"frame_{i:05d}.png"),
                type="png",
                clip=clip
            )

            if progress_cb:
                progress_cb(i + 1, total_frames)

        browser.close()

    return {
        "sfx_events": sfx_manifest.get("events", []),
        "ambience_type": amb_manifest.get("type") if amb_manifest else None,
        "clip_bounds": clip,
        "total_frames": total_frames
    }
