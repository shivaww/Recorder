package com.brollrender.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.widget.FrameLayout
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Spec section 5 - the offscreen render engine.
 *
 * The WebView is attached INVISIBLE (never GONE), manually measured and laid
 * out at the exact output pixel size, software-layered, and drawn manually
 * into a bitmap INSIDE the evaluateJavascript callback of each seek
 * (pitfalls 7.1-7.3). Driven from a worker thread; every WebView touch is
 * posted to the UI thread and latched back, so any deadlock converts into a
 * clean timeout abort (pitfall 7.13).
 */
class RenderEngine(private val activity: Activity) {

    companion object {
        const val VOID_COLOR = 0xFF0A0C10.toInt()
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
            val suggestedZoom: ZoomTransform? = null // initial auto-fit (non-conforming pages)
        ) : PrepareResult()

        data class Fail(val message: String) : PrepareResult()
    }

    sealed class RenderOutcome {
        data class Completed(val firstFrame: Bitmap) : RenderOutcome()

        object Cancelled : RenderOutcome()

        data class Failed(val message: String) : RenderOutcome()
    }

    private var webView: WebView? = null

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
     * Creates the offscreen WebView once and reuses it for later renders
     * (acceptance 6). Attached via addContentView, INVISIBLE (never GONE),
     * software layer, manually measured and laid out at exactly w x h px.
     */
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
                useWideViewPort = false
                loadWithOverviewMode = false
                textZoom = 100
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            web.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            activity.addContentView(web, FrameLayout.LayoutParams(w, h))
            web.visibility = View.INVISIBLE
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

            // Fonts: poll status up to 30 s (timeout = warning, NOT a hard
            // fail), then the hard check - fallback fonts mean the wrong look.
            var fontsLoaded = false
            val deadline = System.currentTimeMillis() + 30_000L
            while (System.currentTimeMillis() < deadline) {
                if ("\"loaded\"" == evalJs(web, JsContracts.FONTS_STATUS_JS)?.trim()) {
                    fontsLoaded = true
                    break
                }
                Thread.sleep(200)
            }
            var fontsWarning: String? = null
            if (fontsLoaded) {
                val rawChk = evalJs(web, JsContracts.FONTS_CHECK_JS)
                val chk = parseJsonObject(rawChk)
                if (chk == null) {
                    fontsWarning = "fonts check unreadable - look may differ"
                } else if (!chk.optBoolean("anton") || !chk.optBoolean("plex")) {
                    // Warning, not abort: the app now renders arbitrary HTML,
                    // which may not use Anton / IBM Plex Mono at all.
                    fontsWarning =
                        "webfonts not loaded - allow internet once (look may differ)"
                }
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
            val manualNote = (det as? FrameDetector.Result.Fail)?.message

            // Duration (5.6) + PAUSE (5.7).
            val durationMs =
                evalJs(web, JsContracts.DURATION_JS)?.trim()?.toDoubleOrNull() ?: 0.0
            val animCount = evalJs(web, JsContracts.PAUSE_JS)?.trim()?.toIntOrNull() ?: 0
            if (animCount <= 0) {
                return PrepareResult.Fail("0 animations locked - nothing to render")
            }

            // Auto-fit for non-conforming pages (qwen video-audit verdict:
            // ~2.9x observed vs ~2.75x density prediction): phone-authored
            // pages render small in the WebView's CSS viewport. Contain-fit
            // the measured content bounds and apply as the INITIAL manual
            // framing - content re-rasterizes, no bitmap upscaling (rule 2).
            var suggestedZoom: ZoomTransform? = null
            if (ok == null) {
                suggestedZoom = computeAutoFit(web)
                if (suggestedZoom != null) {
                    evalJs(
                        web,
                        JsContracts.zoomJs(suggestedZoom.scale, suggestedZoom.panNx, suggestedZoom.panNy)
                    )
                    Thread.sleep(150) // style recalc settles before the thumb
                }
            }

            // t=0 thumbnail - same draw-inside-callback discipline as the loop.
            val thumb = Bitmap.createBitmap(THUMB_W, THUMB_H, Bitmap.Config.ARGB_8888)
            val thumbLatch = CountDownLatch(1)
            activity.runOnUiThread {
                web.evaluateJavascript(JsContracts.seekJs(0.0)) { _ ->
                    val c = Canvas(thumb)
                    c.drawColor(VOID_COLOR)
                    c.scale(THUMB_W.toFloat() / targetW, THUMB_H.toFloat() / targetH)
                    web.draw(c)
                    thumbLatch.countDown()
                }
            }
            if (!thumbLatch.await(10, TimeUnit.SECONDS)) {
                return PrepareResult.Fail("thumbnail draw timed out")
            }

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
                suggestedZoom = suggestedZoom
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
     * Section 5.8 frame loop. Worker thread; ONLY this thread touches the
     * codec/surface (section 6). Seek + draw land in one UI-thread callback
     * pass (pitfall 7.3); the blit is 1:1 with filterBitmap=false (pitfall
     * 7.10); drain after every frame; EOS drain; codec released before muxer
     * (inside VideoEncoder). Cancel = clean abort, partial file deleted.
     */
    fun render(
        frameRect: Rect,
        fps: Int,
        totalFrames: Int,
        outputFile: File,
        bitRate: Int,
        zoom: ZoomTransform? = null,
        enhance: Boolean = false,
        onProgress: (frameNo: Int, total: Int, rateFps: Double, etaSec: Long) -> Unit,
        isCancelled: () -> Boolean
    ): RenderOutcome {
        val web = webView ?: return RenderOutcome.Failed("prepare() must run before render()")
        val w = outW
        val h = outH
        if (w <= 0 || h <= 0) return RenderOutcome.Failed("output size unknown")

        var encoder: VideoEncoder? = null
        val t0 = System.currentTimeMillis()
        try {
            val frameBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) // allocate ONCE
            val blit = Paint().apply { isFilterBitmap = false }
            if (enhance) {
                // Real, fast color-grade enhance: contrast 1.12 around mid-gray
                // + saturation 1.18, applied at native speed during the blit.
                // Deterministic - honestly NOT an AI model (see preview note).
                val cm = ColorMatrix()
                cm.setSaturation(1.18f)
                cm.postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            1.12f, 0f, 0f, 0f, -15.3f,
                            0f, 1.12f, 0f, 0f, -15.3f,
                            0f, 0f, 1.12f, 0f, -15.3f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                )
                blit.colorFilter = ColorMatrixColorFilter(cm)
            }

            encoder = VideoEncoder(w, h, fps, bitRate, outputFile)
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

            for (f in 0 until totalFrames) {
                if (isCancelled()) {
                    encoder.release()
                    outputFile.delete()
                    return RenderOutcome.Cancelled
                }
                val latch = CountDownLatch(1)
                activity.runOnUiThread {
                    web.evaluateJavascript(JsContracts.seekJs(f * 1000.0 / fps)) { _ ->
                        // Draw INSIDE this callback - the seek is guaranteed
                        // applied on this same UI-thread pass (pitfall 7.3).
                        val c = Canvas(frameBmp)
                        c.drawColor(VOID_COLOR) // page void color
                        c.save()
                        c.clipRect(frameRect) // only the 16:9 region
                        c.translate(-frameRect.left.toFloat(), -frameRect.top.toFloat())
                        web.draw(c)
                        c.restore()
                        latch.countDown()
                    }
                }
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    throw RuntimeException("frame $f timed out")
                }
                val sc = surface.lockHardwareCanvas()
                sc.drawBitmap(frameBmp, null, Rect(0, 0, w, h), blit)
                surface.unlockCanvasAndPost(sc)
                encoder.drain(false)

                if (f % 30 == 0 || f == totalFrames - 1) {
                    val elapsed = (System.currentTimeMillis() - t0) / 1000.0
                    if (elapsed > 0.5) {
                        val done = f + 1
                        val rate = done / elapsed
                        val eta = if (rate > 0) ((totalFrames - done) / rate).toLong() else -1L
                        onProgress(done, totalFrames, rate, eta)
                    }
                }
            }

            encoder.signalEos()
            encoder.drain(true)
            val firstFrame = if (blit.colorFilter != null) {
                // frame0.png must match the video's first frame (acceptance 4):
                // export it with the same enhance grade applied.
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { g ->
                    Canvas(g).drawBitmap(frameBmp, null, Rect(0, 0, w, h), blit)
                }
            } else {
                frameBmp.copy(Bitmap.Config.ARGB_8888, false)
            }
            encoder.release()
            return RenderOutcome.Completed(firstFrame)
        } catch (e: Exception) {
            encoder?.release()
            outputFile.delete()
            return RenderOutcome.Failed("render failed: ${e.message}")
        }
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
