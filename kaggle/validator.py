"""validator.py — HTML validation for renderability.

Checks that the uploaded HTML is actually renderable as a video:
- Has CSS animations or JS-driven motion
- Has detectable content bounds
- Fonts/images can load
- No fatal parse errors

Returns structured result the app can display.
"""
import re
from playwright.sync_api import sync_playwright, TimeoutError as PWTimeout

from config import CHROMIUM_FLAGS


def validate_html(html_path, width=1920, height=1080, timeout_ms=10000):
    """Validate HTML for video rendering.

    Returns:
        dict: {
            "valid": bool,
            "reason": str or None,
            "has_animations": bool,
            "animation_count": int,
            "has_sfx": bool,
            "has_ambience": bool,
            "content_bounds": dict or None,
            "fonts_loaded": bool,
            "warnings": list[str]
        }
    """
    result = {
        "valid": False,
        "reason": None,
        "has_animations": False,
        "animation_count": 0,
        "has_sfx": False,
        "has_ambience": False,
        "content_bounds": None,
        "fonts_loaded": False,
        "duration_s": None,
        "warnings": []
    }

    try:
        with sync_playwright() as p:
            browser = p.chromium.launch(args=CHROMIUM_FLAGS)
            page = browser.new_page(viewport={"width": width, "height": height})
            page.goto(f"file://{html_path}", wait_until="networkidle", timeout=timeout_ms)

            # Check fonts
            try:
                page.wait_for_function("document.fonts.status === 'loaded'", timeout=5000)
                result["fonts_loaded"] = True
            except PWTimeout:
                result["warnings"].append("Some fonts may not have loaded")

            # Check animations
            anim_data = page.evaluate("""() => {
                const anims = document.getAnimations();
                const cssAnims = anims.filter(a => a instanceof CSSAnimation);
                const transitions = anims.filter(a => a instanceof CSSTransition);
                const keyframes = document.styleSheets.length > 0 ?
                    [...document.styleSheets].reduce((acc, sheet) => {
                        try {
                            return acc + [...sheet.cssRules].filter(
                                r => r instanceof CSSKeyframesRule
                            ).length;
                        } catch(e) { return acc; }
                    }, 0) : 0;
                return {
                    total: anims.length,
                    css: cssAnims.length,
                    transitions: transitions.length,
                    keyframes: keyframes,
                    hasBrollSeek: !!(window.__broll && window.__broll.seek)
                };
            }""")

            result["animation_count"] = anim_data["total"]
            result["has_animations"] = (
                anim_data["total"] > 0 or
                anim_data["keyframes"] > 0 or
                anim_data["hasBrollSeek"]
            )

            if not result["has_animations"]:
                result["reason"] = "NO_ANIMATIONS: page has no CSS animations, transitions, or __broll.seek"
                browser.close()
                return result

            # Check SFX / Ambience manifests
            result["has_sfx"] = page.evaluate(
                "!!document.querySelector('script#sfx[type=\"application/json\"]')"
            )
            result["has_ambience"] = page.evaluate(
                "!!document.querySelector('script#ambience[type=\"application/json\"]')"
            )

            # Detect content bounds
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

            # Detect animation timeline duration (seconds) for auto-duration
            try:
                dur_s = page.evaluate("""() => {
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
                }""")
                if dur_s and dur_s > 0.5:
                    result["duration_s"] = round(min(max(float(dur_s), 2.0), 600.0), 2)
            except Exception:
                pass

            result["valid"] = True
            browser.close()

    except PWTimeout:
        result["reason"] = "TIMEOUT: page did not load within limit"
    except Exception as e:
        result["reason"] = f"ERROR: {str(e)}"

    return result
