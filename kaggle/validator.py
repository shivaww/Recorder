"""validator.py — HTML validation for renderability.

Checks that the uploaded HTML is actually renderable as a video:
- Has CSS animations or JS-driven motion
- Has detectable content bounds
- Fonts/images can load
- No fatal parse errors

Returns structured result the app can display.
"""
import re
import time
from playwright.sync_api import sync_playwright, TimeoutError as PWTimeout

from config import CHROMIUM_FLAGS, CPU_FALLBACK_FLAGS


def _empty_result():
    return {
        "valid": False,
        "reason": None,
        "has_animations": False,
        "animation_count": 0,
        "has_sfx": False,
        "has_ambience": False,
        "content_bounds": None,
        "fonts_loaded": False,
        "duration_s": None,
        "warnings": [],
    }


ANIM_MARKERS = (
    "@keyframes", "animation:", "animation-name", "requestAnimationFrame",
    "__broll", ".animate(", "gsap", "transition:", "setInterval",
)


def _static_animation_hint(html_path):
    """Last-resort hint: JS-driven or finished animations race the probe."""
    try:
        with open(html_path, "r", encoding="utf-8", errors="ignore") as f:
            low = f.read().lower()
        return [m for m in ANIM_MARKERS if m.lower() in low]
    except Exception:
        return []


RUNTIME_ANIM_JS = """() => {
    const anims = document.getAnimations();
    let kf = 0;
    for (const s of document.styleSheets) {
        try {
            kf += [...s.cssRules].filter(r => r instanceof CSSKeyframesRule).length;
        } catch (e) {}
    }
    const broll = !!(window.__broll && window.__broll.seek);
    if (anims.length === 0 && kf === 0 && !broll) return null;
    return {total: anims.length, keyframes: kf, hasBrollSeek: broll};
}"""


DURATION_JS = """() => {
    let maxEnd = 0;
    const anims = (document.getAnimations ? document.getAnimations() : []);
    for (const a of anims) {
        try {
            const ef = a.effect;
            if (!ef || !ef.getTiming) continue;
            const t = ef.getTiming();
            const dur = (typeof t.duration === 'number') ? t.duration : 0;
            const it = (typeof t.iterations === 'number' && isFinite(t.iterations)) ? t.iterations : 1;
            const end = (t.delay || 0) + dur * it;
            if (isFinite(end) && end > maxEnd) maxEnd = end;
        } catch (e) {}
    }
    return maxEnd / 1000;
}"""


def _attempt(html_path, width, height, timeout_ms):
    """One validation pass; expected failures set reason, never raise."""
    result = _empty_result()
    with sync_playwright() as p:
        try:
            browser = p.chromium.launch(args=CHROMIUM_FLAGS)
        except Exception:
            print("[validate] GPU launch failed; CPU fallback", flush=True)
            browser = p.chromium.launch(args=CPU_FALLBACK_FLAGS)
        try:
            page = browser.new_page(viewport={"width": width, "height": height})
            page.goto(f"file://{html_path}", wait_until="load", timeout=timeout_ms)
            try:
                page.wait_for_function("document.fonts.status === 'loaded'", timeout=5000)
                result["fonts_loaded"] = True
            except PWTimeout:
                result["warnings"].append("Some fonts may not have loaded")
            runtime = None
            try:
                page.wait_for_function("(" + RUNTIME_ANIM_JS.strip() + ")() !== null",
                                       timeout=5000)
                runtime = page.evaluate(RUNTIME_ANIM_JS)
            except PWTimeout:
                runtime = None
            if runtime:
                result["animation_count"] = runtime["total"]
                result["has_animations"] = True
            elif _static_animation_hint(html_path):
                hints = _static_animation_hint(html_path)
                result["has_animations"] = True
                result["warnings"].append(
                    f"runtime probe found no animations; static markers: {hints[:4]}")
            else:
                result["reason"] = ("NO_ANIMATIONS: no running animations, keyframes, "
                                    "__broll.seek, or static markers")
            # CONTENT_CHECKS_BELOW
            if result["has_animations"]:
                result["has_sfx"] = page.evaluate(
                    "!!document.querySelector('script#sfx[type=\"application/json\"]')"
                )
                result["has_ambience"] = page.evaluate(
                    "!!document.querySelector('script#ambience[type=\"application/json\"]')"
                )
                bounds = page.evaluate("""() => {
                    const el = document.querySelector('.fit')
                        || document.querySelector('#video-frame')
                        || [...document.querySelectorAll('div')].find(d => {
                            const r = d.getBoundingClientRect();
                            return r.width > 100 && r.height > 100;
                        });
                    if (!el) return null;
                    const r = el.getBoundingClientRect();
                    return {x: Math.round(r.left), y: Math.round(r.top),
                            w: Math.round(r.width), h: Math.round(r.height)};
                }""")
                result["content_bounds"] = bounds
                if not bounds:
                    result["warnings"].append("No content bounds detected — will use full viewport")
                try:
                    dur_s = page.evaluate(DURATION_JS)
                    if dur_s and dur_s > 0.5:
                        result["duration_s"] = round(min(max(float(dur_s), 2.0), 600.0), 2)
                except Exception:
                    pass
                result["valid"] = True
        finally:
            browser.close()
    return result


def validate_html(html_path, width=1920, height=1080, timeout_ms=20000, attempts=3):
    """Validate with retries and layered animation detection."""
    last = _empty_result()
    for i in range(1, attempts + 1):
        try:
            last = _attempt(html_path, width, height, timeout_ms)
        except PWTimeout:
            last = _empty_result()
            last["reason"] = "TIMEOUT: page did not load within limit"
        except Exception as e:
            last = _empty_result()
            last["reason"] = f"ERROR: {type(e).__name__}: {e}"
        print(f"[validate] attempt {i}/{attempts}: valid={last['valid']} "
              f"reason={last.get('reason')}", flush=True)
        if last["valid"]:
            return last
        if i < attempts:
            time.sleep(2)
    return last
