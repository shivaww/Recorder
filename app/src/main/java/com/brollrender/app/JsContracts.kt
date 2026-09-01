package com.brollrender.app

/**
 * FROZEN JS contracts - verbatim from the BrollRender spec, section 3.
 * Do not edit: the HTML files are built against these exact strings.
 *
 * evaluateJavascript returns results JSON-encoded (e.g. "loaded" arrives as
 * "\"loaded\""); callers must unwrap/parse accordingly (see RenderEngine).
 */
object JsContracts {

    /** Run first; both must be true, else "WebView too old". */
    val CAPABILITY_JS = """
        JSON.stringify({
          anims: typeof document.getAnimations === 'function',
          containers: !!(window.CSS && CSS.supports && CSS.supports('container-type: size'))
        })
    """.trimIndent()

    /** Returns the 16:9 frame geometry in CSS pixels. */
    val DETECT_FRAME_JS = """
        (() => {
          const el = document.querySelector('.fit')
              || document.querySelector('#video-frame')
              || [...document.querySelectorAll('div')].find(d => {
                   const r = d.getBoundingClientRect();
                   return r.width > 100 && Math.abs(r.width / r.height - 16 / 9) < 0.01
                       && !!d.querySelector('.stage');
                 });
          if (!el) return { error: 'FRAME_NOT_FOUND' };
          const r = el.getBoundingClientRect();
          return { x: r.left, y: r.top, w: r.width, h: r.height,
                   vw: document.documentElement.clientWidth,
                   vh: document.documentElement.clientHeight };
        })()
    """.trimIndent()

    /** Freeze the clock, seize every animation (returns the count). */
    val PAUSE_JS = """
        (() => {
          const s = document.createElement('style');
          s.textContent = '*,*::before,*::after{animation-play-state:paused!important;animation-fill-mode:both!important}';
          document.head.appendChild(s);
          window.__a = document.getAnimations();
          return window.__a.length;
        })()
    """.trimIndent()

    /** Auto-detect timeline length in ms (ignores infinite ambient loops). */
    val DURATION_JS = """
        (() => Math.max(0, ...document.getAnimations().map(a => {
          try { const t = a.effect && a.effect.getComputedTiming();
                return (t && t.endTime != null && t.endTime !== Infinity) ? t.endTime : 0; }
          catch (e) { return 0; }
        })))()
    """.trimIndent()

    /** Poll after onPageFinished until result === "loaded". */
    val FONTS_STATUS_JS = "document.fonts.status"

    /** Both must be true or the render is refused (fallback fonts = wrong look). */
    val FONTS_CHECK_JS = """
        JSON.stringify({ anton: document.fonts.check('16px Anton'),
                         plex:  document.fonts.check('16px "IBM Plex Mono"') })
    """.trimIndent()

    /** Belt-and-suspenders alongside ?headless=1. */
    val HEADLESS_JS = "document.body.classList.add('cdp')"

    // SEEK_JS - per frame; MS substituted as milliseconds, "%.3f".
    // Locale.US is load-bearing: a comma decimal separator ("16,200") is a
    // JS syntax error and would silently freeze the clock on some locales.
    private const val SEEK_TEMPLATE = "window.__a.forEach(a => a.currentTime = %s)"

    fun seekJs(ms: Double): String =
        SEEK_TEMPLATE.format(String.format(java.util.Locale.US, "%.3f", ms))

    // Manual framing (peak customization): CSS transform on <html>, origin
    // 0 0. SCALE = zoom factor; PNX/PNY = normalized pan fractions of the
    // viewport, converted to CSS px inside the page. Content re-rasterizes
    // at the new scale - crisp, never a bitmap upscale of the capture.
    // Locale.US formatting is load-bearing (comma decimals are JS errors).
    private val ZOOM_TEMPLATE = """
        (() => {
          const vw = document.documentElement.clientWidth;
          const vh = document.documentElement.clientHeight;
          const el = document.documentElement;
          el.style.transformOrigin = '0 0';
          el.style.transform =
              'translate(' + (PNX * vw) + 'px,' + (PNY * vh) + 'px) scale(' + SCALE + ')';
        })()
    """.trimIndent()

    fun zoomJs(scale: Float, panNx: Float, panNy: Float): String =
        ZOOM_TEMPLATE
            .replace("PNX", String.format(java.util.Locale.US, "%.4f", panNx))
            .replace("PNY", String.format(java.util.Locale.US, "%.4f", panNy))
            .replace("SCALE", String.format(java.util.Locale.US, "%.4f", scale))

    // Page "voice": CSS zoom on <body> (Chromium) - scales the whole layout
    // proportionally (cqh recompute against the zoomed container, so the
    // design scales coherently). Render-time only, independent of the
    // framing transform on <html>.
    private const val TEXT_SCALE_TEMPLATE =
        "(() => { document.body.style.zoom = 'ZV'; return 1; })()"

    fun textScaleJs(scale: Float): String =
        TEXT_SCALE_TEMPLATE.replace("ZV", String.format(java.util.Locale.US, "%.4f", scale))

    // Auto-fit for non-conforming pages: content bounds in CSS px at t=0
    // (every visible element's getBoundingClientRect union, fixed included).
    // The engine contain-fits these into the CSS viewport - which mirrors the
    // 16:9 output canvas because the WebView is laid out at W x H.
    val CONTENT_BOUNDS_JS = """
        (() => {
          const de = document.documentElement;
          const vw = de.clientWidth, vh = de.clientHeight;
          let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
          const els = document.body ? document.body.querySelectorAll('*') : [];
          for (const el of els) {
            if (el.offsetParent === null &&
                getComputedStyle(el).position !== 'fixed') continue;
            const r = el.getBoundingClientRect();
            if (r.width < 1 || r.height < 1) continue;
            minX = Math.min(minX, r.left); minY = Math.min(minY, r.top);
            maxX = Math.max(maxX, r.right); maxY = Math.max(maxY, r.bottom);
          }
          if (minX === Infinity) return { error: 'NO_CONTENT' };
          return { x: minX, y: minY, w: maxX - minX, h: maxY - minY,
                   vw: vw, vh: vh };
        })()
    """.trimIndent()

    // Generic webfont check: the generation prompt may deviate from the
    // default Anton + IBM Plex Mono pairing. Every family actually requested
    // via the page's Google Fonts <link> is checked - all must be loaded.
    val FONTS_GENERIC_JS = """
        (() => {
          const fams = [];
          document.querySelectorAll('link[href*="fonts.googleapis"]').forEach(l => {
            (l.href.match(/family=[^&]+/g) || []).forEach(m => {
              fams.push(decodeURIComponent(m.slice(7)).replace(/[+ ]/g, ' ').split(':')[0].trim());
            });
          });
          const out = {};
          fams.forEach(f => { out[f] = document.fonts.check('16px "' + f + '"'); });
          return JSON.stringify({ families: fams, checks: out,
                                   status: document.fonts.status,
                                   faces: document.fonts.size });
        })()
    """.trimIndent()

    // Fonts kick: re-insert every Google Fonts <link> (a failed stylesheet
    // fetch leaves no @font-face rules - re-inserting the element forces a
    // retry through the normal loader) and explicitly document.fonts.load()
    // each family - lazy font loads may never trigger on an offscreen,
    // never-composited page. Fire-and-forget: the engine polls the checks.
    val FONTS_KICK_JS = """
        (() => {
          document.querySelectorAll('link[href*="fonts.googleapis"]').forEach(l => {
            const c = l.cloneNode();
            l.parentNode.replaceChild(c, l);
          });
          const fams = [];
          document.querySelectorAll('link[href*="fonts.googleapis"]').forEach(l => {
            (l.href.match(/family=[^&]+/g) || []).forEach(m => {
              fams.push(decodeURIComponent(m.slice(7)).replace(/[+ ]/g, ' ').split(':')[0].trim());
            });
          });
          if (fams.length === 0) fams.push('Anton', 'IBM Plex Mono');
          fams.forEach(f => {
            try { document.fonts.load('16px "' + f + '"', 'AaBbCc123'); } catch (e) {}
          });
          return fams.length;
        })()
    """.trimIndent()

    // SFX events (declarative audio): a data-only JSON block in the page -
    //   <script type="application/json" id="sfx">
    //     [{"t":16.2,"id":"slam","gain":0.9}, ...]
    //   </script>
    // t is seconds on the SAME timeline as animation-delay. The engine
    // synthesizes + mixes these onto the MP4's audio track; the zero-JS
    // rule stays intact because the block is data, not logic.
    val SFX_MANIFEST_JS = """
        (() => {
          const el = document.querySelector('script#sfx[type="application/json"]');
          if (!el) return { events: [] };
          try {
            const d = JSON.parse(el.textContent);
            const evs = Array.isArray(d) ? d : (d.events || []);
            const out = [];
            for (const e of evs) {
              const t = +e.t;
              if (!isFinite(t) || t < 0) continue;
              out.push({
                t: t,
                id: String(e.id || 'tick'),
                gain: Math.min(1, Math.max(0, +e.gain || 0.7))
              });
            }
            return { events: out };
          } catch (err) { return { error: String(err) }; }
        })()
    """.trimIndent()
}
