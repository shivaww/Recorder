package com.brollrender.app

import android.graphics.Rect
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Spec section 4 - frame detection math ("flawless corners").
 * The page reports its own frame rect (DOM geometry, never vision); this
 * converts CSS px to bitmap px and hard-validates. Never auto-correct: any
 * mismatch aborts with a specific message. Never upscale (rule 2): a frame
 * smaller than the target is an error, not something to scale up.
 */
object FrameDetector {

    private const val ASPECT_TOLERANCE = 0.01      // |w/h - 16/9|
    private const val DENSITY_TOLERANCE = 0.005    // |scaleX/scaleY - 1|
    private const val SLACK_PX = 2                 // bounds + never-upscale slack

    sealed class Result {
        data class Ok(
            val rect: Rect,               // bitmap px, 1:1 with the output canvas
            val densityScale: Double,     // W / vw
            val cornerDeviationPx: Int    // max distance of any corner from target
        ) : Result()

        data class Fail(val message: String) : Result()
    }

    /** jsResult is the raw evaluateJavascript return of DETECT_FRAME_JS. */
    fun detect(targetW: Int, targetH: Int, jsResult: String?): Result {
        if (jsResult == null || jsResult == "null") {
            return Result.Fail("FRAME_NOT_FOUND - detection returned nothing")
        }
        val o = try {
            JSONObject(jsResult)
        } catch (e: Exception) {
            return Result.Fail("detection result unparseable: $jsResult")
        }
        if (o.has("error")) {
            return Result.Fail(
                "FRAME_NOT_FOUND - this HTML has no .fit / #video-frame / 16:9 .stage element"
            )
        }

        val x = o.optDouble("x", Double.NaN)
        val y = o.optDouble("y", Double.NaN)
        val w = o.optDouble("w", Double.NaN)
        val h = o.optDouble("h", Double.NaN)
        val vw = o.optDouble("vw", Double.NaN)
        val vh = o.optDouble("vh", Double.NaN)
        if (x.isNaN() || y.isNaN() || w.isNaN() || h.isNaN() || vw.isNaN() || vh.isNaN()) {
            return Result.Fail("detection result incomplete - missing geometry fields")
        }

        // Aspect check in CSS px (scale-independent).
        if (abs(w / h - 16.0 / 9.0) > ASPECT_TOLERANCE) {
            return Result.Fail("frame is ${fmt(w)}x${fmt(h)} CSS px - ratio is not 16:9 (broken page)")
        }

        // Density consistency: W/vw must equal H/vh within 0.5%.
        val scaleX = targetW / vw
        val scaleY = targetH / vh
        if (abs(scaleX / scaleY - 1.0) > DENSITY_TOLERANCE) {
            return Result.Fail(
                "viewport ${fmt(vw)}x${fmt(vh)} CSS px - X/Y scale mismatch over 0.5%, layout broken"
            )
        }

        // Frame rect in bitmap px: each field x densityScale, rounded (section 4.4).
        val left = (x * scaleX).roundToInt()
        val top = (y * scaleY).roundToInt()
        val frameW = (w * scaleX).roundToInt()
        val frameH = (h * scaleY).roundToInt()
        val right = left + frameW
        val bottom = top + frameH

        // Frame must sit inside [0,W]x[0,H] with <= 2 px slack.
        if (left < -SLACK_PX || top < -SLACK_PX || right > targetW + SLACK_PX || bottom > targetH + SLACK_PX) {
            return Result.Fail(
                "frame at [$left,$top]-[$right,$bottom] overflows the ${targetW}x${targetH} canvas (page overflow)"
            )
        }

        // The page is designed to fill the target exactly (section 4.5).
        if (abs(frameW - targetW) > SLACK_PX || abs(frameH - targetH) > SLACK_PX) {
            val why = if (frameW < targetW || frameH < targetH) {
                "frame SMALLER than target - broken layout; NEVER upscale (rule 2)"
            } else {
                "frame LARGER than target - detection or layout bug"
            }
            return Result.Fail(
                "frame is ${frameW}x${frameH}px but target is ${targetW}x${targetH}px - $why"
            )
        }

        val deviation = max(
            max(abs(left), abs(targetW - right)),
            max(abs(top), abs(targetH - bottom))
        )
        return Result.Ok(Rect(left, top, right, bottom), scaleX, deviation)
    }

    private fun fmt(d: Double): String = String.format(Locale.US, "%.1f", d)
}
