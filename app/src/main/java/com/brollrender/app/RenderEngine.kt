package com.brollrender.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.FrameLayout
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
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
            val cornerDeviationPx: Int,
            val animCount: Int,
            val durationMs: Long,
            val fontsLoaded: Boolean // false = fonts.status never reached 'loaded' within 30 s
        ) : PrepareResult()

        data class Fail(val message: String) : PrepareResult()
    }

    sealed class RenderOutcome {
        data class Completed(val firstFrame: Bitmap) : RenderOutcome()

        object Cancelled : RenderOutcome()

        data class Failed(val message: String) : RenderOutcome()
    }

    private var webView: WebView? = null

    private fun parseJsonObject(raw: String?): JSONObject? = try {
        if (raw == null) null else JSONObject(raw)
    } catch (e: Exception) {
        null
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
            val caps = parseJsonObject(evalJs(web, JsContracts.CAPABILITY_JS))
                ?: return PrepareResult.Fail("capability probe returned nothing")
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
            if (fontsLoaded) {
                val chk = parseJsonObject(evalJs(web, JsContracts.FONTS_CHECK_JS))
                    ?: return PrepareResult.Fail("fonts check returned nothing")
                if (!chk.optBoolean("anton") || !chk.optBoolean("plex")) {
                    return PrepareResult.Fail(
                        "webfonts not loaded - allow internet once (first render fetches Google Fonts)"
                    )
                }
            }

            // Strip crop chrome twice: ?headless=1 in the URL AND injection
            // (pitfall 7.11).
            evalJs(web, JsContracts.HEADLESS_JS)

            // Detection (section 4) - DOM geometry, never vision.
            val det = FrameDetector.detect(
                targetW,
                targetH,
                evalJs(web, JsContracts.DETECT_FRAME_JS)
            )
            val ok = when (det) {
                is FrameDetector.Result.Fail -> return PrepareResult.Fail(det.message)
                is FrameDetector.Result.Ok -> det
            }

            // Duration (5.6) + PAUSE (5.7).
            val durationMs =
                evalJs(web, JsContracts.DURATION_JS)?.trim()?.toDoubleOrNull() ?: 0.0
            val animCount = evalJs(web, JsContracts.PAUSE_JS)?.trim()?.toIntOrNull() ?: 0
            if (animCount <= 0) {
                return PrepareResult.Fail("0 animations locked - nothing to render")
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
                ok.rect,
                ok.cornerDeviationPx,
                animCount,
                Math.round(durationMs),
                fontsLoaded
            )
        } catch (e: Exception) {
            return PrepareResult.Fail("prepare failed: ${e.message}")
        }
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
            val blit = Paint().apply { filterBitmap = false }

            encoder = VideoEncoder(w, h, fps, bitRate, outputFile)
            encoder.start()
            val surface = encoder.inputSurface
                ?: throw RuntimeException("encoder produced no input surface")

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
            val firstFrame = frameBmp.copy(Bitmap.Config.ARGB_8888, false)
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
