package com.brollrender.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import android.widget.FrameLayout
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.min
import android.media.MediaFormat
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Spec section 5 - the offscreen render engine.
 *
 * The WebView is attached GHOST-VISIBLE (bottom of the activity root,
 * alpha 0.02, LAYER_TYPE_NONE), manually measured and laid
 * out at the exact output pixel size, composited every frame (GPU
 * compositing for full CSS filter/blend/canvas/WebGL support), and drawn only
 * after Chromium confirms each seek's visual state has committed -
 * GPU-direct into the encoder surface when the probe passes, into a
 * software bitmap (readback) otherwise (pitfalls
 * 7.1-7.3). The seek JS forces synchronous style+layout flush
 * (document.body.offsetHeight); postVisualStateCallback then closes the
 * compositor race before the draw.
 * Driven from a worker thread; every WebView touch is posted to the UI
 * thread and latched back, so any deadlock converts into a clean timeout
 * abort (pitfall 7.13).
 *
 * Two page modes:
 *  - CSS-only: motion via CSS keyframes, scrubbed by getAnimations()
 *  - Scrub-safe JS: page implements window.__broll = { seek(t), duration() }
 *    for canvas/WebGL/dynamic effects. Both are scrubbed on the same clock.
 */
class RenderEngine(private val activity: Activity) {

    companion object {
        const val VOID_COLOR = 0xFF0A0C10.toInt()
        private const val TAG = "RenderEngine"
        // GPU-vs-software probe tolerance: 3% of sampled pixels. The old 1%
        // was too tight for blur-heavy pages (text-shadow / glows / soft
        // gradients rasterize slightly differently on the hw and sw paths)
        // and downgraded blur-heavy pages to CPU readback for no real defect.
        // A blank or stale GPU frame still mismatches by far more (different
        // timeline shots differ in the tens of percent).
        private const val MISMATCH_TOL = 0.03
        private const val THUMB_W = 480
        private const val THUMB_H = 270
    }

    sealed class PrepareResult {
        data class Ok(
            val thumb: Bitmap,
            val frameRect: Rect,
            val cornerDeviationPx: Int, // -1 = manual mode (no DOM detection)
            val animCount: Int,
            val durationMs: Long,
            val fontsLoaded: Boolean, // false = fonts.status never reached 'loaded' within 30 s
            val manualRecommended: Boolean = false, // detection failed -> manual framing
            val note: String? = null,               // why (e.g. FRAME_NOT_FOUND detail)
            val fontsWarning: String? = null,       // webfont mismatch (warning, not abort)
            val fontsDetail: String? = null,        // families confirmed loaded (success)
            val sfxEvents: List<Sfx.Event> = emptyList(), // declarative #sfx manifest events
            val sfxLoudness: String? = null,               // page-declared loudness (low/normal/high)
            val suggestedZoom: ZoomTransform? = null, // initial auto-fit (non-conforming pages)
            val isBrollJs: Boolean = false,          // page implements __broll scrub-safe JS
            val brollWarning: String? = null,        // banned API usage detected (warning only)
            val ambienceType: String? = null,        // page-declared ambience bed type
            val ambienceGain: Float = 0.25f          // page-declared ambience gain
        ) : PrepareResult()

        data class Fail(val message: String) : PrepareResult()
    }

    sealed class RenderOutcome {
        data class Completed(val firstFrame: Bitmap) : RenderOutcome()

        object Cancelled : RenderOutcome()

        data class Failed(val message: String) : RenderOutcome()
    }

    private var webView: WebView? = null

    /** Host for the ghost-visible WebView: the BOTTOM of MainActivity's
     *  root - UI screens stack above it and own every touch. */
    private var hostRoot: ViewGroup? = null

    /** Call once after the activity's root exists, before the first prepare. */
    fun attachRoot(r: ViewGroup) {
        hostRoot = r
    }

    /** PixelCopy completion listener thread (main looper; the copy itself
     *  is initiated from the render worker thread - never the UI thread). */
    private val mainHandler = Handler(Looper.getMainLooper())
    private var visualStateRequestId = 0L

    /**
     * evaluateJavascript results are JSON-ENCODED (spec section 3), and the
     * JSON.stringify(...) probes are double-wrapped: JS returns a string,
     * the WebView then encodes it again. Unwrap one or two layers before
     * handing the result to JSONObject.
     */
    private fun parseJsonObject(raw: String?): JSONObject? {
        if (raw == null) return null
        var v: Any? = try {
            JSONTokener(raw).nextValue()
        } catch (e: Exception) {
            return null
        }
        if (v is String) {
            v = try {
                JSONTokener(v).nextValue()
            } catch (e: Exception) {
                null
            }
        }
        return v as? JSONObject
    }

    /** evaluateJavascript on the UI thread, latched; result stays JSON-encoded. */
    private fun evalJs(web: WebView, script: String, timeoutSec: Long = 15): String? {
        val latch = CountDownLatch(1)
        var out: String? = null
        activity.runOnUiThread {
            web.evaluateJavascript(script) { v ->
                out = v
                latch.countDown()
            }
        }
        if (!latch.await(timeoutSec, TimeUnit.SECONDS)) {
            throw RuntimeException("JS eval timed out (${timeoutSec}s): ${script.take(48)}")
        }
        return out
    }

    /**
     * Seek the page and wait until Chromium has committed that DOM state to
     * its visual pipeline.  evaluateJavascript() only says that the JS ran;
     * it does not mean a hardware-canvas draw will see the new animation
     * time.  Drawing immediately after it was the source of occasional
     * previous/wrong frames in the direct GPU path.
     *
     * This is deliberately asynchronous because it is called from the UI
     * thread.  Callers already own their own completion latch/state.
     */
    private fun seekAndCommit(web: WebView, tMs: Double, done: (Exception?) -> Unit) {
        web.evaluateJavascript(JsContracts.seekJs(tMs)) {
            try {
                val requestId = ++visualStateRequestId
                web.postVisualStateCallback(requestId, object : WebView.VisualStateCallback() {
                    override fun onComplete(id: Long) = done(null)
                })
            } catch (e: Exception) {
                done(e)
            }
        }
    }

    /**
     * Creates the offscreen WebView once and reuses it for later renders
     * (acceptance 6). GHOST-VISIBLE in the activity's root (alpha 0.02,
     * LAYER_TYPE_NONE - see the class doc: a composited frame must exist
     * for the GL draw path), manually measured and laid out at exactly
     * w x h px.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun obtainWebView(w: Int, h: Int): WebView {
        webView?.let { web ->
            // Reused for a later render at a (possibly different) output size
            // (draft after final, immediate second render): re-measure +
            // re-layout at w x h on the UI thread before handing it back.
            val rel = CountDownLatch(1)
            activity.runOnUiThread {
                web.layoutParams = FrameLayout.LayoutParams(w, h)
                web.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
                )
                web.layout(0, 0, w, h)
                rel.countDown()
            }
            if (!rel.await(10, TimeUnit.SECONDS)) {
                throw RuntimeException("WebView re-layout timed out")
            }
            return web
        }
        val latch = CountDownLatch(1)
        var created: WebView? = null
        activity.runOnUiThread {
            val web = WebView(activity)
            web.settings.apply {
                javaScriptEnabled = true
                allowFileAccess = true
                // Force the CSS viewport to ALWAYS equal the output canvas
                // (view width), for ANY html - with or without a viewport
                // meta. This guarantees .fit (100vw) fills the MP4 frame and
                // matches the Chrome preview. (true would fall back to 980px
                // for meta-less pages and re-shrink them.)
                useWideViewPort = false
                loadWithOverviewMode = false
                textZoom = 100
                cacheMode = WebSettings.LOAD_DEFAULT
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowContentAccess = true
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = true
            }
            // HARDWARE layer: Chromium GPU compositor - enables CSS filters,
            // mix-blend-mode, backdrop-filter, Canvas 2D GPU accel, WebGL.
            // Per frame, web.draw() runs inside the seek callback: GPU-direct
            // into the encoder surface's hardware canvas (fast path), or into
            // a software bitmap (synchronous GPU->CPU readback fallback); the
            // seek JS forces reflow before the draw.
            web.setInitialScale(100) // no auto-scaling; 1 CSS px = 1 dp
            // GHOST-VISIBLE placement (fix for the void-functor evidence:
            // probe dump hw = 11 KB uniform void vs 588 KB full reference).
            // A hardware-canvas web.draw() executes the GL functor, which
            // draws the COMPOSITED frame - and a compositor frame only
            // exists for an attached VISIBLE view. INVISIBLE + forced
            // LAYER_TYPE_HARDWARE = content parked in an FBO that never
            // refreshes = the functor paints void. So: LAYER_TYPE_NONE (the
            // WebView manages its own hardware path), alpha 0.02 (invisible
            // under the UI, still composited), and the view sits at the
            // BOTTOM of the activity's root so the UI owns all touches.
            web.setLayerType(View.LAYER_TYPE_NONE, null)
            web.alpha = 0.02f
            web.isFocusable = false
            web.isFocusableInTouchMode = false
            // Swallow any touch that falls through the UI above: the page
            // must never react to taps while it renders behind the screens.
            web.setOnTouchListener { _, _ -> true }
            val lp = FrameLayout.LayoutParams(w, h)
            val host = hostRoot
            if (host != null) host.addView(web, 0, lp)
            else activity.addContentView(web, lp)
            web.visibility = View.VISIBLE
            web.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
            web.layout(0, 0, w, h)
            created = web
            latch.countDown()
        }
        if (!latch.await(10, TimeUnit.SECONDS)) throw RuntimeException("WebView creation timed out")
        webView = created ?: throw RuntimeException("WebView creation failed")
        return webView!!
    }

    // Output size of the current prepare() - recorded so render() can size
    // the frame bitmap without extra parameters.
    private var outW = 0
    private var outH = 0

    /**
     * Sections 5.4-5.7 preparation: load, capability gate, fonts poll + hard
     * check, headless injection, detection, duration, PAUSE, and a t=0
     * thumbnail (drawn INSIDE the seek callback, pitfall 7.3). Worker thread.
     */
    fun prepare(htmlFile: File, targetW: Int, targetH: Int): PrepareResult {
        outW = targetW
        outH = targetH
        try {
            val web = obtainWebView(targetW, targetH)

            val pageLatch = CountDownLatch(1)
            activity.runOnUiThread {
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        pageLatch.countDown()
                    }
                }
                web.loadUrl("file://" + htmlFile.absolutePath + "?headless=1")
            }
            if (!pageLatch.await(30, TimeUnit.SECONDS)) {
                return PrepareResult.Fail("page did not finish loading within 30 s")
            }

            // Capability gate before anything else (pitfall 7.14).
            val rawCaps = evalJs(web, JsContracts.CAPABILITY_JS)
            val caps = parseJsonObject(rawCaps)
                ?: return PrepareResult.Fail(
                    "capability probe returned: ${rawCaps?.take(120) ?: "nothing"}"
                )
            if (!caps.optBoolean("anims") || !caps.optBoolean("containers")) {
                return PrepareResult.Fail(
                    "WebView too old - update Android System WebView (Chromium 105+ required)"
                )
            }

            // Fonts - ACTIVE load, then poll the per-family checks.
            // fonts.status alone is not trustworthy: it reads 'loaded' even
            // when a face FAILED or never started (both observed in the
            // field). So: (1) FONTS_KICK_JS re-inserts the Google Fonts
            // <link> (failed CSS fetch = no @font-face rules at all) and
            // explicitly document.fonts.load()s every family (lazy loads may
            // never trigger on an offscreen page); (2) poll check() results
            // for up to 45 s. Timeout = warning, NOT a hard fail.
            evalJs(web, JsContracts.FONTS_KICK_JS)
            var fontsLoaded = false
            var fontsDetail: String? = null
            var fontsWarning: String? = null
            var lastFams: org.json.JSONArray? = null
            var lastChecks: org.json.JSONObject? = null
            val deadline = System.currentTimeMillis() + 45_000L
            while (System.currentTimeMillis() < deadline) {
                val gen = parseJsonObject(evalJs(web, JsContracts.FONTS_GENERIC_JS))
                val fams = gen?.optJSONArray("families")
                val checks = gen?.optJSONObject("checks")
                if (fams != null && fams.length() > 0) {
                    lastFams = fams
                    lastChecks = checks
                    var missing = 0
                    for (i in 0 until fams.length()) {
                        if (checks == null || !checks.optBoolean(fams.optString(i))) missing++
                    }
                    if (missing == 0) {
                        fontsLoaded = true
                        break
                    }
                } else {
                    // No fonts <link> parsed: default pairing fallback.
                    val chk = parseJsonObject(evalJs(web, JsContracts.FONTS_CHECK_JS))
                    if (chk != null && chk.optBoolean("anton") && chk.optBoolean("plex")) {
                        fontsLoaded = true
                        break
                    }
                }
                Thread.sleep(300)
            }
            if (!fontsLoaded) {
                // Build the failure warning from the LAST probe, with
                // diagnostics: status + registered face count. faces=0 with a
                // fonts <link> present means the stylesheet itself never
                // parsed - a different failure than a slow font fetch.
                val fams = lastFams
                if (fams != null && fams.length() > 0) {
                    val missing = mutableListOf<String>()
                    for (i in 0 until fams.length()) {
                        val fam = fams.optString(i)
                        if (lastChecks == null || !lastChecks.optBoolean(fam)) missing.add(fam)
                    }
                    val gen = parseJsonObject(evalJs(web, JsContracts.FONTS_GENERIC_JS))
                    fontsWarning =
                        "webfonts not loaded: ${missing.joinToString(", ")} " +
                            "(status=${gen?.optString("status") ?: "?"}, " +
                            "faces=${gen?.optInt("faces", -1)}) - " +
                            "allow internet once (look may differ)"
                } else {
                    fontsWarning =
                        "webfonts not loaded - allow internet once (look may differ)"
                }
                // A page which explicitly requested a webfont must never be
                // rendered with Android's fallback font.  Fallback glyph
                // widths change line breaks and make titles look squeezed
                // compared with Chrome.  Stop here rather than baking a
                // knowingly different video; a retry after one connected
                // preview uses WebView's cache normally.
                if (lastFams != null && lastFams.length() > 0) {
                    return PrepareResult.Fail("$fontsWarning; retry after the fonts finish loading")
                }
            } else {
                fontsDetail = lastFams?.let { f ->
                    (0 until f.length()).joinToString(", ") { f.optString(it) }
                }
            }

            // URL images: wait for all <img> to finish loading (async
            // network fetch). Without this, early frames render broken/empty
            // images. 15s timeout; timeout = warning (images may pop in).
            var imagesReady = true
            val imgDeadline = System.currentTimeMillis() + 15_000L
            while (System.currentTimeMillis() < imgDeadline) {
                val imgStatus = parseJsonObject(evalJs(web, JsContracts.IMAGES_STATUS_JS))
                if (imgStatus != null && imgStatus.optBoolean("ready", true)) break
                imagesReady = imgStatus?.optBoolean("ready", true) ?: true
                Thread.sleep(300)
            }
            if (!imagesReady) {
                // Images still loading after 15s - proceed with warning
                // (they may pop in on later frames or be broken URLs)
            }

            // Strip crop chrome twice: ?headless=1 in the URL AND injection
            // (pitfall 7.11).
            evalJs(web, JsContracts.HEADLESS_JS)

            // Detection (section 4) - DOM geometry, never vision. A failed
            // detection no longer aborts: it falls back to MANUAL framing
            // (user zoom/pan, full-canvas 1:1 capture) so arbitrary HTML still
            // records. What still gates: capability, page load, >= 1 animation.
            val det = FrameDetector.detect(
                targetW,
                targetH,
                evalJs(web, JsContracts.DETECT_FRAME_JS)
            )
            val ok = det as? FrameDetector.Result.Ok
            val under = det as? FrameDetector.Result.Undersized
            val manualNote =
                (det as? FrameDetector.Result.Fail)?.message ?: under?.message

            // Scrub-safe JS detection: does the page implement __broll?
            val brollInfo = parseJsonObject(evalJs(web, JsContracts.BROLL_DETECT_JS))
            val isBrollJs = brollInfo?.optBoolean("seek") ?: false

            // Validate: warn if banned time-dependent APIs are present.
            var brollWarning: String? = null
            val validation = parseJsonObject(evalJs(web, JsContracts.BROLL_VALIDATE_JS))
            if (validation != null && !validation.optBoolean("clean", true)) {
                val violations = validation.optJSONArray("violations")
                val vList = mutableListOf<String>()
                if (violations != null) {
                    for (i in 0 until violations.length()) vList.add(violations.optString(i))
                }
                if (vList.isNotEmpty()) {
                    brollWarning = "banned APIs found (will not scrub): ${vList.joinToString(", ")}"
                }
            }

            // Duration (5.6) + PAUSE (5.7).
            val durationMs =
                evalJs(web, JsContracts.DURATION_JS)?.trim()?.toDoubleOrNull() ?: 0.0
            val animCount = evalJs(web, JsContracts.PAUSE_JS)?.trim()?.toIntOrNull() ?: 0
            if (animCount <= 0 && !isBrollJs) {
                return PrepareResult.Fail("0 animations locked and no __broll.seek - nothing to render")
            }

            // SFX manifest (declarative audio): the data-only #sfx JSON
            // block, on the same timeline as animation-delay.
            var sfxEvents: List<Sfx.Event> = emptyList()
            val sfxObj = parseJsonObject(evalJs(web, JsContracts.SFX_MANIFEST_JS))
            val sfxLoudness = sfxObj?.optString("loudness")?.lowercase()
            val sfxArr = sfxObj?.optJSONArray("events")
            if (sfxArr != null) {
                val evs = mutableListOf<Sfx.Event>()
                for (i in 0 until sfxArr.length()) {
                    val e = sfxArr.optJSONObject(i) ?: continue
                    val t = e.optDouble("t", -1.0)
                    if (t < 0.0) continue
                    evs.add(
                        Sfx.Event(
                            t,
                            e.optString("id", "tick"),
                            e.optDouble("gain", 0.7).toFloat().coerceIn(0f, 1f)
                        )
                    )
                }
                sfxEvents = evs
            }

            // Ambience manifest (background audio bed): type + gain.
            var ambienceType: String? = null
            var ambienceGain = 0.25f
            val ambObj = parseJsonObject(evalJs(web, JsContracts.AMBIENCE_MANIFEST_JS))
            if (ambObj != null && ambObj.optBoolean("present", false)) {
                ambienceType = ambObj.optString("type", "drone")
                ambienceGain = ambObj.optDouble("gain", 0.25).toFloat().coerceIn(0f, 1f)
            }

            // Auto-fit for non-conforming pages (qwen video-audit verdict:
            // ~2.9x observed vs ~2.75x density prediction): phone-authored
            // pages render small in the WebView's CSS viewport. Contain-fit
            // the measured content bounds and apply as the INITIAL manual
            // framing - content re-rasterizes, no bitmap upscaling (rule 2).
            var suggestedZoom: ZoomTransform? = null
            if (ok == null) {
                suggestedZoom = if (under != null) {
                    // 16:9 frame smaller than the canvas (black-wrapper
                    // design): fit THE FRAME to the canvas. Re-rasterized CSS
                    // scaling - never a bitmap upscale (rule 2's intent:
                    // never resample captured pixels).
                    frameFitZoom(under)
                } else {
                    computeAutoFit(web)
                }
                if (suggestedZoom != null) {
                    evalJs(
                        web,
                        JsContracts.zoomJs(suggestedZoom.scale, suggestedZoom.panNx, suggestedZoom.panNy)
                    )
                    Thread.sleep(150) // style recalc settles before the thumb
                }
            }

            // A bitmap thumbnail is the proven CPU path. It does not need a
            // compositor callback (and must keep working if the GPU path is
            // unavailable on this device).
            val thumb = Bitmap.createBitmap(THUMB_W, THUMB_H, Bitmap.Config.ARGB_8888)
            val thumbLatch = CountDownLatch(1)
            var thumbError: Exception? = null
            activity.runOnUiThread {
                web.evaluateJavascript(JsContracts.seekJs(0.0)) {
                    try {
                        val c = Canvas(thumb)
                        c.drawColor(VOID_COLOR)
                        c.scale(THUMB_W.toFloat() / targetW, THUMB_H.toFloat() / targetH)
                        web.draw(c)
                    } catch (e: Exception) {
                        thumbError = e
                    } finally {
                        thumbLatch.countDown()
                    }
                }
            }
            if (!thumbLatch.await(10, TimeUnit.SECONDS)) {
                return PrepareResult.Fail("thumbnail draw timed out")
            }
            thumbError?.let { return PrepareResult.Fail("thumbnail draw failed: ${it.message}") }

            return PrepareResult.Ok(
                thumb,
                ok?.rect ?: Rect(0, 0, targetW, targetH),
                ok?.cornerDeviationPx ?: -1,
                animCount,
                Math.round(durationMs),
                fontsLoaded,
                manualRecommended = ok == null,
                note = manualNote,
                fontsWarning = fontsWarning,
                fontsDetail = fontsDetail,
                sfxEvents = sfxEvents,
                sfxLoudness = sfxLoudness,
                suggestedZoom = suggestedZoom,
                isBrollJs = isBrollJs,
                brollWarning = brollWarning,
                ambienceType = ambienceType,
                ambienceGain = ambienceGain
            )
        } catch (e: Exception) {
            return PrepareResult.Fail("prepare failed: ${e.message}")
        }
    }

    /**
     * Contain-fit the page's content bounds (CONTENT_BOUNDS_JS, CSS px) into
     * the CSS viewport, centered, as the initial manual framing. The viewport
     * is 16:9 (WebView laid out at W x H), so fitting it in CSS space is
     * fitting the video frame in output space. Null = unreadable bounds (the
     * user frames manually, scale 1).
     */
    private fun computeAutoFit(web: WebView): ZoomTransform? {
        val raw = evalJs(web, JsContracts.CONTENT_BOUNDS_JS) ?: return null
        val o = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return null
        }
        if (o.has("error")) return null
        val x = o.optDouble("x", Double.NaN)
        val y = o.optDouble("y", Double.NaN)
        val w = o.optDouble("w", Double.NaN)
        val h = o.optDouble("h", Double.NaN)
        val vw = o.optDouble("vw", Double.NaN)
        val vh = o.optDouble("vh", Double.NaN)
        if (x.isNaN() || y.isNaN() || w.isNaN() || h.isNaN() || vw.isNaN() || vh.isNaN()) {
            return null
        }
        if (w < 1.0 || h < 1.0 || vw < 1.0 || vh < 1.0) return null
        val scale = (min(vw / w, vh / h)).coerceIn(0.25, 4.0) // contain, clamped
        val fittedW = w * scale
        val fittedH = h * scale
        // Center the fitted box; translate is applied BEFORE scale in the
        // ZOOM contract, so it uses unscaled CSS px.
        val cssTx = (vw - fittedW) / 2.0 - x * scale
        val cssTy = (vh - fittedH) / 2.0 - y * scale
        return ZoomTransform(
            scale.toFloat(),
            (cssTx / vw).toFloat(),
            (cssTy / vh).toFloat()
        )
    }

    /**
     * Fit an undersized 16:9 frame to the full CSS viewport (which mirrors
     * the 16:9 output canvas). Scale = contain-fit; pan maps the frame's
     * top-left to the origin and centers any rounding sliver.
     */
    private fun frameFitZoom(u: FrameDetector.Result.Undersized): ZoomTransform? {
        if (u.vw < 1.0 || u.vh < 1.0 || u.cssW < 1.0 || u.cssH < 1.0) return null
        val s = min(u.vw / u.cssW, u.vh / u.cssH)
        val fittedW = u.cssW * s
        val fittedH = u.cssH * s
        val cssTx = (u.vw - fittedW) / 2.0 - u.cssX * s
        val cssTy = (u.vh - fittedH) / 2.0 - u.cssY * s
        return ZoomTransform(
            s.toFloat(),
            (cssTx / u.vw).toFloat(),
            (cssTy / u.vh).toFloat()
        )
    }

    /**
     * Section 5.8 frame loop. Called from a WORKER thread (never the UI
     * thread). FAST PATH (GPU-direct, probe-validated): the UI thread runs a
     * self-driven chain - each seek callback draws its frame into the
     * encoder's input surface and issues the next seek itself - while this
     * thread only drains the codec and watches a stall watchdog; no
     * per-frame worker<->UI handoff (previously: runOnUiThread + latch +
     * await, two thread hops and a park/unpark around EVERY seek+draw), so
     * the GPU raster + encode pipeline runs continuously with no dead time.
     * SOFTWARE fallback (GPU->CPU readback): the legacy per-frame latch
     * loop - the shared bitmap must not be overwritten before its blit.
     * The codec is drained ONLY from the caller thread (section 6). Cancel
     * = clean abort, partial file deleted. onStats: ~1 Hz RAM/GPU/pipeline
     * readout for the RENDER screen.
     */
    fun render(
        frameRect: Rect,
        fps: Int,
        totalFrames: Int,
        outputFile: File,
        bitRate: Int,
        zoom: ZoomTransform? = null,
        enhance: Boolean = false,
        sfxEvents: List<Sfx.Event> = emptyList(),
        sfxLoudness: String? = null,
        ambienceType: String? = null,
        ambienceGain: Float = 0.25f,
        textScale: Float = 1f,
        allowGpu: Boolean = true,
        onProgress: (frameNo: Int, total: Int, rateFps: Double, etaSec: Long) -> Unit,
        onStats: ((stats: String) -> Unit)? = null,
        isPaused: () -> Boolean = { false },
        isCancelled: () -> Boolean
    ): RenderOutcome {
        val web = webView ?: return RenderOutcome.Failed("prepare() must run before render()")
        val w = outW
        val h = outH
        if (w <= 0 || h <= 0) return RenderOutcome.Failed("output size unknown")

        var encoder: VideoEncoder? = null
        // Warm-draw surface (stale-functor fix, probe-validated): when the
        // hw capture shows the PREVIOUSLY committed frame, each frame draws
        // here first (forcing the compositor commit for the current seek)
        // and then into the encoder surface. ImageReader-backed with
        // acquireLatestImage() drainage - never blocks the UI thread.
        // Declared here so the catch path can release it too.
        var warmSurface: Surface? = null
        var warmReader: ImageReader? = null
        val t0 = System.currentTimeMillis()
        try {
            val frameBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) // allocate ONCE
            val blit = Paint().apply { isFilterBitmap = false }
            // Frame-0 QC export (frame0.png, acceptance 4): snapshot the
            // FIRST frame the moment it exists - the software pass below
            // overwrites frameBmp on every frame, so the old post-loop copy
            // exported the LAST frame as "frame0".
            var firstFrame: Bitmap? = null

            // SFX: mix + AAC-encode the full timeline BEFORE the frame
            // loop. Both tracks then derive PTS from the same master clock -
            // sync by construction. Pages without events stay video-only.
            var audioFormat: MediaFormat? = null
            var audioSamples: List<AudioEncoder.Sample> = emptyList()
            if (sfxEvents.isNotEmpty() || ambienceType != null) {
                // Audio prep is user-visible work: report the two stages so
                // the pre-loop phase never reads as a hang (-1 / -2 codes).
                onProgress(-1, totalFrames, 0.0, -1) // "AUDIO: mixing"
                val pcm = Sfx.mix(sfxEvents, totalFrames.toDouble() / fps, sfxLoudness, ambienceType, ambienceGain)
                onProgress(-2, totalFrames, 0.0, -1) // "AUDIO: encoding (aac)"
                val encoded = AudioEncoder().encode(pcm)
                audioFormat = encoded.format
                audioSamples = encoded.samples
            }
            encoder = VideoEncoder(w, h, fps, bitRate, outputFile, audioFormat, audioSamples)
            encoder.start()
            val surface = encoder.inputSurface
                ?: throw RuntimeException("encoder produced no input surface")

            // Framing transform - ALWAYS written, not just when manual: an
            // AUTO capture (zoom == null) gets an explicit identity reset so
            // a stale transform from an earlier preview (auto-fit suggestion
            // or pinch state) can never leak into the render.
            val effectiveZoom = zoom ?: ZoomTransform(1f, 0f, 0f)
            evalJs(web, JsContracts.zoomJs(effectiveZoom.scale, effectiveZoom.panNx, effectiveZoom.panNy))
            Thread.sleep(150) // style recalc settles before frame 0's draw
            // Page voice: proportional CSS zoom on <body>, independent of
            // the framing transform on <html> above. ALWAYS written, on or
            // off - the same leak rule as the zoom transform and the ENHANCE
            // filter: a stale body zoom from an earlier render of this same
            // prepared page (CANCEL -> change TEXT -> render again) must
            // never leak into the next render.
            evalJs(web, JsContracts.textScaleJs(textScale))
            Thread.sleep(100)

            // ENHANCE grade: now a root CSS filter (identical math to the
            // old ColorMatrix blit - see JsContracts.enhanceJs) so BOTH
            // paths apply it: Chromium's compositor filters the GPU draws
            // for free; software draws produce the same pixels. ALWAYS
            // written, on or off - same leak rule as the zoom transform.
            evalJs(web, JsContracts.enhanceJs(enhance))
            Thread.sleep(100) // filter settle before frame 0

            // FAST PATH: draw the page straight into the encoder's input
            // surface (GL-to-GL, no CPU round trip). gpuProbe() validates
            // it once per render; any failure falls back to software below.
            // Overnight / screen-off renders force the CPU pipeline BY
            // DESIGN: the GPU functor draws the composited frame, and with
            // no active display pipeline there is nothing to composite -
            // the fast path would bake void frames into the video. Foreground
            // single renders probe the (now ghost-visible) GPU normally.
            var probe = if (allowGpu) {
                gpuProbe(web, frameRect, fps, totalFrames)
            } else {
                ProbeInfo(false, false, "gpu off (overnight mode)")
            }
            // END-TO-END GATE (the blank-video lesson): the surface probe
            // above validates a PROXY - a SurfaceTexture buffer read back
            // through PixelCopy, which SYNCHRONIZES the copy. The render loop
            // feeds the ENCODER input surface, whose buffers MediaCodec
            // consumes as queued - and on a real device those two paths
            // diverged: proxy passed, actual MP4 came out blank (audio fine).
            // The fast path is now enabled only when the ARTIFACT itself
            // verifies: e2eGpuProbe draws two frames through the EXACT loop
            // primitives into a throwaway encoder, decodes the mini MP4,
            // and compares the DECODED pixels with ground truth. A blank or
            // wrong video cannot pass this gate.
            if (probe.direct) {
                val e2e = e2eGpuProbe(web, frameRect, fps, totalFrames, probe.warmDraw)
                if (e2e != null) {
                    Log.w(TAG, "gpu e2e gate: $e2e - staying on the CPU pipeline")
                    probe = ProbeInfo(false, false, "e2e: $e2e")
                } else {
                    Log.i(TAG, "gpu e2e gate passed - decoded probe frames match")
                }
            }
            if (probe.warmDraw) {
                // The loop's warm primitive: an ImageReader-backed hardware
                // surface (same lockHardwareCanvas mechanism as the capture
                // path, so the functor draws identically) with a consumer -
                // the reader is drained after every warm draw, so its buffer
                // queue can never fill and block the UI thread.
                try {
                    val rd = ImageReader.newInstance(
                        w, h, android.graphics.PixelFormat.RGBA_8888, 2
                    )
                    warmSurface = rd.surface
                    warmReader = rd
                } catch (_: Exception) {
                    warmSurface = null
                }
            }
            val gpuDirect = probe.direct && (!probe.warmDraw || warmSurface != null)
            val probeNote = when {
                !probe.direct -> probe.reason
                warmSurface == null -> "warm surface unavailable"
                else -> null
            }

            // Full-canvas fast case: when the detected frame covers the whole
            // output canvas (AUTO-locked pages - the common case), the
            // save/clip/translate/restore dance is a no-op; skip it.
            val full = frameRect.left <= 0 && frameRect.top <= 0 &&
                frameRect.right >= w && frameRect.bottom >= h

            // Windowed progress: a cumulative-average rate (frames / total
            // elapsed) starts near 0.2 f/s because the first seconds
            // amortize the audio mix/encode and the GPU probe - a lie about
            // the render's real speed and an absurd ETA. The rate is now the
            // last-30-frame window, with the baseline taken AFTER the probe
            // so warm-up cost never pollutes it.
            var lastReportT = System.currentTimeMillis()
            var lastReportF = 0
            fun report(f: Int) {
                if (f % 30 == 0 || f == totalFrames - 1) {
                    if (f > lastReportF) {
                        val dt = ((System.currentTimeMillis() - lastReportT) / 1000.0)
                            .coerceAtLeast(0.001)
                        val rate = (f - lastReportF) / dt
                        val remaining = totalFrames - f - 1
                        val eta = if (rate > 0) (remaining / rate).toLong() else -1L
                        onProgress(f + 1, totalFrames, rate, eta)
                    }
                    lastReportT = System.currentTimeMillis()
                    lastReportF = f
                }
            }

            // ~1 Hz system readout (RAM + GPU + live pipeline) for the RENDER
            // screen; a no-op for callers that pass no onStats.
            var lastStats = 0L
            fun sample(gpuPath: Boolean) {
                val now = System.currentTimeMillis()
                if (now - lastStats >= 1000) {
                    lastStats = now
                    onStats?.invoke(sampleSys(gpuPath, probeNote))
                }
            }
            onStats?.invoke(sampleSys(gpuDirect, probeNote))
            lastStats = System.currentTimeMillis()

            if (gpuDirect) {
                // ===== FAST PATH: self-driven frame chain =====
                // The committed visual callback for frame N draws N and
                // issues the seek for N+1 from inside the same callback, all on the UI
                // thread - no worker<->UI ping-pong per frame. This thread
                // never touches frames: it is the codec drain + watchdog
                // thread (section 6 - only this thread drains), and
                // MediaCodec input-surface backpressure naturally throttels
                // the chain when the encoder is the bottleneck. The chain
                // must be initiated from OFF the UI thread; every render()
                // call site is a worker thread.
                val st = LoopState()
                fun drawFrame(f: Int) {
                    if (st.stop) return
                    seekAndCommit(web, f * 1000.0 / fps) { seekError ->
                        if (st.stop) return@seekAndCommit
                        if (seekError != null) {
                            st.error = seekError
                            st.stop = true
                            return@seekAndCommit
                        }
                        try {
                            warmSurface?.let { ws ->
                                // Stale-functor fix (probe-validated): a warm
                                // draw forces the compositor commit for t=f
                                // BEFORE the capture draw into the encoder
                                // surface - without it the functor renders
                                // the previously committed frame.
                                val wc = ws.lockHardwareCanvas()
                                wc.drawColor(VOID_COLOR)
                                web.draw(wc)
                                ws.unlockCanvasAndPost(wc)
                                // Free the buffer the compositor just filled:
                                // the reader only ever holds the latest image.
                                try { warmReader?.acquireLatestImage()?.close() } catch (_: Exception) {}
                            }
                            val sc = surface.lockHardwareCanvas()
                            sc.drawColor(VOID_COLOR)
                            if (!full) {
                                sc.save()
                                sc.clipRect(frameRect)
                                sc.translate(-frameRect.left.toFloat(), -frameRect.top.toFloat())
                                web.draw(sc)
                                sc.restore()
                            } else {
                                web.draw(sc)
                            }
                            surface.unlockCanvasAndPost(sc)
                            if (f == 0) {
                                // frame0.png QC (acceptance 4): a fresh
                                // SOFTWARE draw of frame 0 - never PixelCopy
                                // from the encoder surface (single consumer).
                                firstFrame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { g ->
                                    val fc = Canvas(g)
                                    fc.drawColor(VOID_COLOR)
                                    if (!full) {
                                        fc.save()
                                        fc.clipRect(frameRect)
                                        fc.translate(-frameRect.left.toFloat(), -frameRect.top.toFloat())
                                        web.draw(fc)
                                        fc.restore()
                                    } else {
                                        web.draw(fc)
                                    }
                                }
                            }
                            st.done = f + 1
                            if (f + 1 < totalFrames && !st.stop) {
                                // Section 7.15 pause (single-render): halt the
                                // chain AT the frame boundary - the UI thread
                                // must never block on a full surface buffer
                                // queue (that would ANR); the worker thread
                                // resumes the chain when unpaused.
                                if (isPaused()) st.paused = true else drawFrame(f + 1)
                            }
                        } catch (e: Exception) {
                            // Surface ops CAN throw: record and fail cleanly
                            // on the worker thread, no UI crash.
                            st.error = e
                            st.stop = true
                        }
                    }
                }
                activity.runOnUiThread { drawFrame(0) }
                while (st.done < totalFrames && !st.stop && st.error == null) {
                    if (isCancelled()) {
                        st.stop = true
                        break
                    }
                    if (st.paused) {
                        // The chain halted itself at a frame boundary (pause).
                        // Wait here; resume exactly where it stopped. The
                        // stall watchdog below must NOT fire during a pause.
                        if (!isPaused()) {
                            st.paused = false
                            activity.runOnUiThread { drawFrame(st.done) }
                        } else {
                            Thread.sleep(200)
                        }
                        continue
                    }
                    encoder.drain(false)
                    if (st.done > 0) report(st.done - 1)
                    sample(true)
                    // Stall watchdog: the chain normally advances within
                    // milliseconds; 10 s of zero progress means the UI thread
                    // or the page hung - fail the render, not the phone. A
                    // PAUSE is quiet, not a stall: if the app was backgrounded
                    // while the chain sat at a boundary (st.paused), the
                    // watchdog must NOT fire - the outer loop's pause branch
                    // above owns waiting and resuming.
                    val stable = st.done
                    val deadline = System.currentTimeMillis() + 10_000
                    while (st.done == stable && !st.stop && st.error == null &&
                        !st.paused && !isCancelled() &&
                        System.currentTimeMillis() < deadline) {
                        encoder.drain(false)
                        sample(true)
                        Thread.sleep(2)
                    }
                    if (st.done == stable && !st.stop && st.error == null &&
                        !st.paused && !isCancelled()) {
                        throw RuntimeException("frame $stable timed out")
                    }
                }
                st.error?.let {
                    throw RuntimeException("frame ${st.done} draw failed: ${it.message}")
                }
                if (st.stop && st.done < totalFrames) {
                    encoder.release()
                    outputFile.delete()
                    return RenderOutcome.Cancelled
                }
            } else {
                // ===== SOFTWARE FALLBACK: legacy per-frame loop =====
                // The shared frameBmp is reused per frame, so the UI chain
                // must not run ahead of the blit - the latch per frame (draw
                // N, await N, blit N, issue N+1) keeps the bitmap contents
                // stable. Correctness first: the readback path is already
                // the slow path.
                for (f in 0 until totalFrames) {
                    // Section 7.15 pause (single-render): wait at the frame
                    // boundary; cancel wins over resume.
                    while (isPaused() && !isCancelled()) Thread.sleep(200)
                    if (isCancelled()) {
                        encoder.release()
                        outputFile.delete()
                        return RenderOutcome.Cancelled
                    }
                    var drawError: Exception? = null
                    val latch = CountDownLatch(1)
                    activity.runOnUiThread {
                        web.evaluateJavascript(JsContracts.seekJs(f * 1000.0 / fps)) {
                            try {
                                val c = Canvas(frameBmp)
                                c.drawColor(VOID_COLOR)
                                if (!full) {
                                    c.save()
                                    c.clipRect(frameRect)
                                    c.translate(-frameRect.left.toFloat(), -frameRect.top.toFloat())
                                    web.draw(c)
                                    c.restore()
                                } else {
                                    web.draw(c)
                                }
                                if (f == 0) {
                                    firstFrame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { g ->
                                        val fc = Canvas(g)
                                        fc.drawColor(VOID_COLOR)
                                        if (!full) {
                                            fc.save()
                                            fc.clipRect(frameRect)
                                            fc.translate(-frameRect.left.toFloat(), -frameRect.top.toFloat())
                                            web.draw(fc)
                                            fc.restore()
                                        } else {
                                            web.draw(fc)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                drawError = e
                            } finally {
                                latch.countDown()
                            }
                        }
                    }
                    if (!latch.await(10, TimeUnit.SECONDS)) {
                        throw RuntimeException("frame $f timed out")
                    }
                    drawError?.let {
                        throw RuntimeException("frame $f draw failed: ${it.message}")
                    }
                    val sc = surface.lockHardwareCanvas()
                    sc.drawBitmap(frameBmp, null, Rect(0, 0, w, h), blit)
                    surface.unlockCanvasAndPost(sc)
                    encoder.drain(false)
                    report(f)
                    sample(false)
                }
            }

            encoder.signalEos()
            encoder.drain(true)
            encoder.release()
            try { warmReader?.close() } catch (_: Exception) {}
            return RenderOutcome.Completed(
                firstFrame ?: frameBmp.copy(Bitmap.Config.ARGB_8888, false)
            )
        } catch (e: Exception) {
            encoder?.release()
            outputFile.delete()
            try { warmReader?.close() } catch (_: Exception) {}
            return RenderOutcome.Failed("render failed: ${e.message}")
        }
    }

    /**
     * END-TO-END fast-path gate - the blank-video lesson. The surface probe
     * above validates a PROXY: a SurfaceTexture buffer read back through
     * PixelCopy, which synchronizes the copy. The render loop instead feeds
     * the ENCODER input surface, whose buffers MediaCodec consumes as queued
     * - and on a real device those two paths diverged (proxy passed, actual
     * MP4 blank, audio intact). This gate verifies the ARTIFACT: two
     * mid-timeline frames are drawn through the EXACT loop primitives
     * (seek-callback discipline, lockHardwareCanvas, warm draw when
     * required) into a throwaway H.264 encoder, the mini MP4 is decoded
     * back, and the DECODED pixels are compared with software ground truth
     * (hwMatchesRef). GPU-direct runs only if the decoded video actually
     * contains the page. Returns null = pass, else the failure reason
     * (surfaced on the RENDER screen, with diagnostic dumps on failure).
     */
    private fun e2eGpuProbe(web: WebView, frameRect: Rect, fps: Int, totalFrames: Int, warm: Boolean): String? {
        val w = outW
        val h = outH
        val tmp = File(activity.cacheDir, "gpu_e2e_probe.mp4")
        tmp.delete()
        var enc: VideoEncoder? = null
        var warmReader: ImageReader? = null
        try {
            enc = VideoEncoder(w, h, fps, 16_000_000, tmp, null, emptyList())
            enc.start()
            val s = enc.inputSurface ?: return "probe encoder produced no surface"
            var warmSurface: Surface? = null
            if (warm) {
                try {
                    warmReader = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2)
                    warmSurface = warmReader.surface
                } catch (_: Exception) {
                    warmSurface = null
                }
            }
            val f1 = (totalFrames / 3).coerceAtLeast(1)
            val f2 = (2 * totalFrames / 3).coerceAtLeast(f1 + 1)
            val t1 = f1 * 1000.0 / fps
            val t2 = f2 * 1000.0 / fps
            val full = frameRect.left <= 0 && frameRect.top <= 0 &&
                frameRect.right >= w && frameRect.bottom >= h
            for (pair in listOf(t1 to "t1", t2 to "t2")) {
                val tMs = pair.first
                val at = pair.second
                val latch = CountDownLatch(1)
                var err: Exception? = null
                activity.runOnUiThread {
                    seekAndCommit(web, tMs) { seekError ->
                        try {
                            seekError?.let { throw it }
                            warmSurface?.let { ws ->
                                val wc = ws.lockHardwareCanvas()
                                wc.drawColor(VOID_COLOR)
                                web.draw(wc)
                                ws.unlockCanvasAndPost(wc)
                                try { warmReader?.acquireLatestImage()?.close() } catch (_: Exception) {}
                            }
                            val sc = s.lockHardwareCanvas()
                            sc.drawColor(VOID_COLOR)
                            if (!full) {
                                sc.save()
                                sc.clipRect(frameRect)
                                sc.translate(-frameRect.left.toFloat(), -frameRect.top.toFloat())
                                web.draw(sc)
                                sc.restore()
                            } else {
                                web.draw(sc)
                            }
                            s.unlockCanvasAndPost(sc)
                        } catch (e: Exception) {
                            err = e
                        } finally {
                            latch.countDown()
                        }
                    }
                }
                if (!latch.await(10, TimeUnit.SECONDS)) return "draw timed out at $at"
                err?.let { return "draw failed at $at: ${it.message}" }
                enc.drain(false)
            }
            enc.signalEos()
            enc.drain(true)
            enc.release()
            enc = null
            // Decode the artifact back and compare the DECODED pixels.
            val rec = MediaMetadataRetriever()
            try {
                rec.setDataSource(tmp.absolutePath)
                val strideUs = 1_000_000L / fps
                val checks = if (Build.VERSION.SDK_INT >= 27) {
                    listOf(0L to "t1", strideUs to "t2")
                } else {
                    listOf(0L to "t1") // API 26: exact seeking unavailable; frame 0 only
                }
                for (pair in checks) {
                    val us = pair.first
                    val at = pair.second
                    val dec = try {
                        rec.getFrameAtTime(
                            us,
                            if (Build.VERSION.SDK_INT >= 27) MediaMetadataRetriever.OPTION_CLOSEST
                            else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )
                    } catch (e: Exception) {
                        null
                    }
                    if (dec == null) return "decode returned nothing at $at"
                    val ref = probeReference(web, frameRect, if (at == "t1") t1 else t2, w, h)
                    if (!hwMatchesRef(dec, ref, w, h)) {
                        dumpProbePng("e2e_dec_$at", dec)
                        dumpProbePng("e2e_ref_$at", ref)
                        val mm = framesAgreeRatio(dec, ref, w, h)
                        return "decoded frame mismatch " + (mm * 100).toInt() + "% at $at"
                    }
                }
                return null
            } finally {
                try {
                    rec.release()
                } catch (_: Exception) {
                }
                tmp.delete()
            }
        } catch (e: Exception) {
            return "e2e threw: " + e.javaClass.simpleName
        } finally {
            try {
                enc?.release()
            } catch (_: Exception) {
            }
            try {
                warmReader?.close()
            } catch (_: Exception) {
            }
            tmp.delete()
        }
    }

    /** GPU-direct validation outcome. */
    private class ProbeInfo(
        val direct: Boolean,    // run the GPU-direct frame chain
        val warmDraw: Boolean,  // per-frame warm draw required (stale functor)
        val reason: String?     // failure cause, shown on the RENDER screen
    )

    private fun gpuProbe(web: WebView, frameRect: Rect, fps: Int, totalFrames: Int): ProbeInfo {
        val w = outW
        val h = outH
        // Detached SurfaceTexture (single-buffer-mode constructor, API 26):
        // no GL context needed on this thread - it is only the buffer sink.
        val tex = SurfaceTexture(false)
        // Required before the first lock: a SurfaceTexture-backed
        // surface without a default buffer size dequeues wrong-sized
        // buffers and the probe would fail on every device.
        tex.setDefaultBufferSize(w, h)
        val probeSurface = Surface(tex)
        // Warm-draw surface - the EXACT primitive the loop uses in warmDraw
        // mode: ImageReader-backed hardware surface + per-draw drainage.
        // (NOT a hardware Bitmap: Canvas(Bitmap) throws on immutable
        // hardware bitmaps. NOT a bare SurfaceTexture: without a consumer
        // its queue blocks the UI thread after a few frames. The reader is
        // the consumer.)
        var warmReader: ImageReader? = null
        try {
            warmReader = ImageReader.newInstance(
                w, h, android.graphics.PixelFormat.RGBA_8888, 2
            )
        } catch (_: Exception) {
            warmReader = null
        }
        val warmSurface = warmReader?.surface
        try {
            // Two DIFFERENT mid-timeline times: differing frames prove the
            // functor drew live content per seek, not a stale cached layer.
            val f1 = (totalFrames / 3).coerceAtLeast(1)
            val f2 = (2 * totalFrames / 3).coerceAtLeast(f1 + 1)
            val t1 = f1 * 1000.0 / fps
            val t2 = f2 * 1000.0 / fps
            // Software ground truth at t=0: the state the page-load commit
            // holds. If the FIRST hw capture matches THIS instead of its own
            // reference, the functor is drawing the load commit - stale.
            val r0 = probeReference(web, frameRect, 0.0, w, h)
            var carry: Bitmap? = r0
            for (tMs in listOf(t1, t2)) {
                probeDraw(web, probeSurface, frameRect, tMs)
                val g = copySurface(probeSurface, w, h)
                if (g == null) {
                    Log.w(TAG, "gpu probe: PixelCopy readback failed")
                    return ProbeInfo(false, false, "pixelcopy readback failed")
                }
                val r = probeReference(web, frameRect, tMs, w, h)
                val at = if (tMs == t1) "t1" else "t2"
                if (hwMatchesRef(g, r, w, h)) {
                    carry = r
                    continue
                }
                // Diagnose WHY before giving up (any-GPU support: devices
                // legitimately rasterize AA/blur differently - tolerate that;
                // never tolerate blank, stale, or low-detail output).
                if (isUniform(g)) {
                    dumpProbePng("hw_$at", g)
                    dumpProbePng("ref_$at", r)
                    Log.w(TAG, "gpu probe: hw frame blank at $at")
                    return ProbeInfo(false, false, "hw frame blank at $at")
                }
                // STALE TEST: does the hw capture match an EARLIER time far
                // better than its own? (Previous frame still committed.)
                val stale = carry != null &&
                    tolerantBestRatio(g, carry, w, h) <= 0.02
                if (stale) {
                    // Warm-draw experiment: the loop's exact warm sequence -
                    // seek, warm draw into the ImageReader surface (forces
                    // the compositor commit), then the capture draw.
                    if (warmSurface != null) {
                        try {
                            probeWarmAndCapture(
                                web, warmSurface, warmReader, probeSurface, frameRect, tMs
                            )
                            val g2 = copySurface(probeSurface, w, h)
                            if (g2 != null && hwMatchesRef(g2, r, w, h)) {
                                Log.i(TAG, "gpu probe: warm-draw fixes stale functor at $at")
                                return ProbeInfo(true, true, null)
                            }
                        } catch (_: Exception) {
                        }
                    }
                    dumpProbePng("hw_$at", g)
                    dumpProbePng("ref_$at", r)
                    val reason = "stale hw at $at" +
                        if (warmSurface == null) ", warm n/a" else ", warm no-fix"
                    Log.w(TAG, "gpu probe: $reason")
                    return ProbeInfo(false, false, reason)
                }
                // Neither matching nor stale: the hw path renders different
                // pixels. Dump both for eyeball comparison and stay on the
                // correct CPU pipeline.
                dumpProbePng("hw_$at", g)
                dumpProbePng("ref_$at", r)
                val cs = centerLumaSimilarity(g, r)
                val reason = "mismatch " +
                    (framesAgreeRatio(g, r, w, h) * 100).toInt() + "% at $at (struct " +
                    String.format(java.util.Locale.US, "%.2f", cs) + ")"
                Log.w(TAG, "gpu probe: $reason")
                return ProbeInfo(false, false, reason)
            }
            Log.i(TAG, "gpu probe passed - drawing directly into the encoder surface")
            return ProbeInfo(true, false, null)
        } catch (e: Exception) {
            Log.w(TAG, "gpu probe failed (${e.javaClass.simpleName}: ${e.message}) - software path")
            return ProbeInfo(false, false, "probe threw: " + e.javaClass.simpleName)
        } finally {
            try {
                probeSurface.release()
            } catch (_: Exception) {
            }
            try {
                tex.release()
            } catch (_: Exception) {
            }
            try {
                warmReader?.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Seek to tMs, warm-draw into the ImageReader surface (forces the
     * compositor commit for the fresh seek), then capture-draw into the
     * probe surface - the EXACT sequence the render loop runs in warmDraw
     * mode, including the post-warm image drainage. UI thread, inside the
     * seek callback (same discipline as the loop).
     */
    private fun probeWarmAndCapture(
        web: WebView,
        warmSurface: Surface,
        warmReader: ImageReader?,
        surface: Surface,
        frameRect: Rect,
        tMs: Double
    ) {
        val latch = CountDownLatch(1)
        var err: Exception? = null
        activity.runOnUiThread {
            seekAndCommit(web, tMs) { seekError ->
                try {
                    seekError?.let { throw it }
                    val wc = warmSurface.lockHardwareCanvas()
                    wc.drawColor(VOID_COLOR)
                    web.draw(wc)
                    warmSurface.unlockCanvasAndPost(wc)
                    try { warmReader?.acquireLatestImage()?.close() } catch (_: Exception) {}
                    val sc = surface.lockHardwareCanvas()
                    sc.drawColor(VOID_COLOR)
                    sc.save()
                    sc.clipRect(frameRect)
                    sc.translate(-frameRect.left.toFloat(), -frameRect.top.toFloat())
                    web.draw(sc)
                    sc.restore()
                    surface.unlockCanvasAndPost(sc)
                } catch (e: Exception) {
                    err = e
                } finally {
                    latch.countDown()
                }
            }
        }
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw RuntimeException("warm probe draw timed out at t=$tMs ms")
        }
        err?.let { throw it }
    }

    /** Seek to tMs and draw the page into the probe surface (UI thread). */
    private fun probeDraw(web: WebView, surface: Surface, frameRect: Rect, tMs: Double) {
        val latch = CountDownLatch(1)
        var err: Exception? = null
        activity.runOnUiThread {
            seekAndCommit(web, tMs) { seekError ->
                try {
                    seekError?.let { throw it }
                    val sc = surface.lockHardwareCanvas()
                    sc.drawColor(VOID_COLOR)
                    sc.save()
                    sc.clipRect(frameRect)
                    sc.translate(-frameRect.left.toFloat(), -frameRect.top.toFloat())
                    web.draw(sc)
                    sc.restore()
                    surface.unlockCanvasAndPost(sc)
                } catch (e: Exception) {
                    err = e
                } finally {
                    latch.countDown()
                }
            }
        }
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw RuntimeException("probe draw timed out at t=$tMs ms")
        }
        err?.let { throw it }
    }

    /** Software ground truth: seek to tMs, draw into a fresh bitmap
     *  (UI thread, inside the seek callback - same discipline as render()). */
    private fun probeReference(web: WebView, frameRect: Rect, tMs: Double, w: Int, h: Int): Bitmap {
        val latch = CountDownLatch(1)
        val ref = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        var err: Exception? = null
        activity.runOnUiThread {
            web.evaluateJavascript(JsContracts.seekJs(tMs)) {
                try {
                    val c = Canvas(ref)
                    c.drawColor(VOID_COLOR)
                    c.save()
                    c.clipRect(frameRect)
                    c.translate(-frameRect.left.toFloat(), -frameRect.top.toFloat())
                    web.draw(c)
                    c.restore()
                } catch (e: Exception) {
                    err = e
                } finally {
                    latch.countDown()
                }
            }
        }
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw RuntimeException("probe reference timed out at t=$tMs ms")
        }
        err?.let { throw it }
        return ref
    }

    /**
     * PixelCopy the most recently queued buffer of [surface] into a bitmap
     * (Surface overload is API 24; minSdk is 26). Returns null on failure
     * or timeout - callers treat that as "fast path unavailable".
     */
    private fun copySurface(surface: Surface, w: Int, h: Int): Bitmap? {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        var result = -1
        PixelCopy.request(
            surface,
            out,
            { r ->
                result = r
                latch.countDown()
            },
            mainHandler
        )
        if (!latch.await(10, TimeUnit.SECONDS)) return null
        return if (result == PixelCopy.SUCCESS) out else null
    }

    /**
     * Sparse-sample mismatch RATIO between a GPU-captured frame and its
     * software reference (every 4th pixel in both axes - ~135k samples at
     * 1080p): fast, still catches a blank frame or a stale/cached functor
     * (frame 2 repeating frame 1). The caller owns the tolerance
     * (MISMATCH_TOL) and the failure reason surfaced to the RENDER screen.
     */
    private fun framesAgreeRatio(a: Bitmap, b: Bitmap, w: Int, h: Int): Double {
        val px = 4
        var diff = 0
        val total = ((w + px - 1) / px) * ((h + px - 1) / px)
        for (y in 0 until h step px) {
            for (x in 0 until w step px) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) diff++
            }
        }
        return diff.toDouble() / total
    }

    /**
     * ANY-GPU comparison gate (probe): does the hardware capture show the
     * right CONTENT? Devices legitimately rasterize antialiased text and
     * blur slightly differently, and sub-pixel transform rounding can shift
     * a whole frame by a pixel - exact equality wrongly rejects those and
     * parks good GPUs on the slow CPU path. Gate order, cheapest first:
     *  1. exact sparse ratio <= MISMATCH_TOL (strictest, no doubt);
     *  2. tolerant ratio (per-channel +-16) at the best of +-2 px shifts -
     *     passes legit raster noise and 1-px offsets;
     * A downsampled structural comparison used to be a third fallback here.
     * It could accept a frame with the right broad shapes but squeezed text
     * or a different layout scale.  This gate is about artifact fidelity, so
     * such a device must use the readback path instead of trading accuracy
     * for the GPU speedup.
     */
    private fun hwMatchesRef(g: Bitmap, r: Bitmap, w: Int, h: Int): Boolean {
        if (framesAgreeRatio(g, r, w, h) <= MISMATCH_TOL) return true
        return tolerantBestRatio(g, r, w, h) <= MISMATCH_TOL
    }

    /** Tolerant sparse mismatch at the best of +-2 px shifts (both axes):
     *  per-channel difference <= 16 counts as equal. Returns the best
     *  (lowest) ratio found. */
    private fun tolerantBestRatio(a: Bitmap, b: Bitmap, w: Int, h: Int): Double {
        val px = 4
        var best = 1.0
        for (dy in -2..2) {
            for (dx in -2..2) {
                var diff = 0
                var total = 0
                for (y in 2 until h - 2 step px) {
                    for (x in 2 until w - 2 step px) {
                        val pa = a.getPixel(x + dx, y + dy)
                        val pb = b.getPixel(x, y)
                        total++
                        if (Math.abs((pa shr 16 and 0xFF) - (pb shr 16 and 0xFF)) > 16 ||
                            Math.abs((pa shr 8 and 0xFF) - (pb shr 8 and 0xFF)) > 16 ||
                            Math.abs((pa and 0xFF) - (pb and 0xFF)) > 16
                        ) diff++
                    }
                }
                if (total > 0) {
                    val ratio = diff.toDouble() / total
                    if (ratio < best) best = ratio
                }
            }
        }
        return best
    }

    /** Uniform frame = blank capture (all sampled pixels identical). */
    private fun isUniform(b: Bitmap): Boolean {
        val first = b.getPixel(4, 4)
        var y = 4
        while (y < b.height) {
            var x = 4
            while (x < b.width) {
                if (b.getPixel(x, y) != first) return false
                x += 61
            }
            y += 43
        }
        return true
    }

    /** Cosine similarity of the center-crop luma, downsampled to 48x27 -
     *  the center is where timeline content changes, so a WRONG frame
     *  (e.g. a stale one) scores low even when the static background
     *  dominates the full frame. */
    private fun centerLumaSimilarity(a: Bitmap, b: Bitmap): Double {
        val ca = Bitmap.createScaledBitmap(
            Bitmap.createBitmap(
                a, a.width / 4, a.height / 4, a.width / 2, a.height / 2
            ),
            48, 27, true
        )
        val cb = Bitmap.createScaledBitmap(
            Bitmap.createBitmap(
                b, b.width / 4, b.height / 4, b.width / 2, b.height / 2
            ),
            48, 27, true
        )
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (y in 0 until 27) {
            for (x in 0 until 48) {
                val la = luma(ca.getPixel(x, y))
                val lb = luma(cb.getPixel(x, y))
                dot += la * lb
                na += la * la
                nb += lb * lb
            }
        }
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / (Math.sqrt(na) * Math.sqrt(nb))
    }

    private fun luma(c: Int): Double {
        val r = (c shr 16 and 0xFF).toDouble()
        val g = (c shr 8 and 0xFF).toDouble()
        val b = (c and 0xFF).toDouble()
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** Detail-energy guard: the hw frame must keep >= 75% of the
     *  reference's mean luma gradient - rejects soft / low-resolution
     *  functor output that would pass downscaled structural comparison. */
    private fun detailOk(g: Bitmap, r: Bitmap, w: Int, h: Int): Boolean {
        var dg = 0.0
        var dr = 0.0
        var n = 0
        for (y in 0 until h step 8) {
            var x = 0
            while (x + 4 < w) {
                dg += Math.abs(luma(g.getPixel(x + 4, y)) - luma(g.getPixel(x, y)))
                dr += Math.abs(luma(r.getPixel(x + 4, y)) - luma(r.getPixel(x, y)))
                n++
                x += 8
            }
        }
        if (n == 0 || dr < 1.0) return true // reference is flat: nothing to guard
        return dg >= 0.75 * dr
    }

    /** Dump a probe frame for eyeball comparison (Pictures/BrollRender) when
     *  the fast path is rejected - real pixels beat theorizing about why. */
    private fun dumpProbePng(tag: String, bmp: Bitmap) {
        try {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "BrollRender_$tag.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BrollRender")
            }
            val uri = activity.contentResolver
                .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return
            activity.contentResolver.openOutputStream(uri)?.use { outs ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, outs)
            }
        } catch (_: Exception) {
        }
    }

    /** Cross-thread state for the self-driven (GPU path) frame chain. */
    private class LoopState {
        @Volatile
        var done = 0     // frames drawn

        @Volatile
        var stop = false // cancel: stop issuing further frames

        @Volatile
        var paused = false // section 7.15: chain halted itself at a boundary

        @Volatile
        var error: Exception? = null
    }

    /**
     * One-line system readout for the RENDER screen (~1 Hz): app RAM (PSS),
     * best-effort GPU busy % (Adreno sysfs; omitted on SoCs that do not
     * expose it - Android has NO public GPU-utilization API, so nothing is
     * ever fabricated), and the live pipeline (GPU-direct vs CPU readback).
     */
    private fun sampleSys(gpuDirect: Boolean, probeNote: String?): String {
        val pssMb = try {
            val mi = Debug.MemoryInfo()
            Debug.getMemoryInfo(mi)
            mi.totalPss / 1024
        } catch (e: Exception) {
            ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576L).toInt()
        }
        val sb = StringBuilder("MEM ").append(pssMb).append(" MB")
        gpuBusyPct()?.let { g -> sb.append(" · GPU ").append(g).append("%") }
        sb.append(" · ").append(if (gpuDirect) "GPU DIRECT" else "CPU READBACK")
        // When the fast path was rejected, say WHY - the number is the
        // diagnostic for the next fix, never a guess.
        if (!gpuDirect && probeNote != null) sb.append(" (").append(probeNote).append(")")
        return sb.toString()
    }

    /** Best-effort Adreno utilization; null when not exposed (non-Snapdragon
     *  SoCs and some Snapdragon kernels). Never guessed, never faked. */
    private fun gpuBusyPct(): Int? {
        for (p in listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load"
        )) {
            try {
                File(p).readText().trim().removeSuffix("%").trim().toIntOrNull()?.let { return it }
            } catch (_: Exception) {
            }
        }
        return null
    }

    /** Activity.onDestroy ONLY (pitfall 7.9) - never call mid-render. */
    fun destroy() {
        val web = webView ?: return
        webView = null
        activity.runOnUiThread {
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            web.destroy()
        }
    }
}
