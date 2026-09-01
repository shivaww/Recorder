package com.brollrender.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Manual framing transform: zoom factor + normalized pan fractions. */
data class ZoomTransform(
    val scale: Float,
    val panNx: Float,
    val panNy: Float
)

/**
 * Interactive manual framing for the PREVIEW screen. The view's bounds ARE
 * the output frame (16:9): what is visible inside the amber brackets is
 * exactly what gets recorded - the same translate/scale is injected into
 * the WebView as a CSS transform on <html>, so the page re-rasterizes at
 * the zoomed size (crisp text/vectors; never a bitmap upscale of the
 * capture). Pinch to zoom (0.5x-4x), drag to pan, double-tap to reset.
 *
 * The preview bitmap is a 480x270 approximation; the render is exact.
 */
@SuppressLint("ViewConstructor")
class ZoomView(
    context: Context,
    private val thumb: Bitmap,
    initial: ZoomTransform?
) : View(context) {

    var onTransform: ((ZoomTransform) -> Unit)? = null

    private var scale = initial?.scale ?: 1f
    private var panX = 0f // view px
    private var panY = 0f

    private val voidPaint = Paint().apply { color = 0xFF0A0C10.toInt() }
    private val imgPaint = Paint().apply { isFilterBitmap = true } // preview-only smoothing
    private val outlinePaint = Paint().apply {
        color = 0x99FFB454.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val bracketPaint = Paint().apply {
        color = 0xFFFFB454.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }

    fun transform(): ZoomTransform = ZoomTransform(
        scale,
        if (width > 0) panX / width else 0f,
        if (height > 0) panY / height else 0f
    )

    fun reset() {
        scale = 1f
        panX = 0f
        panY = 0f
        invalidate()
        onTransform?.invoke(transform())
    }

    private fun clampPan() {
        if (width == 0 || height == 0) return
        panX = panX.coerceIn(min(0f, width - width * scale), max(0f, width - width * scale))
        panY = panY.coerceIn(min(0f, height - height * scale), max(0f, height - height * scale))
    }

    private val panDetector =
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                dx: Float,
                dy: Float
            ): Boolean {
                panX -= dx
                panY -= dy
                clampPan()
                invalidate()
                onTransform?.invoke(transform())
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                reset()
                return true
            }
        })

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val fx = detector.focusX
                val fy = detector.focusY
                val newScale = (scale * detector.scaleFactor).coerceIn(0.5f, 4f)
                if (newScale == scale) return true
                panX = fx - (fx - panX) * (newScale / scale)
                panY = fy - (fy - panY) * (newScale / scale)
                scale = newScale
                clampPan()
                invalidate()
                onTransform?.invoke(transform())
                return true
            }
        }
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        panDetector.onTouchEvent(e)
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 9f / 16f).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampPan()
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), voidPaint)
        c.save()
        c.translate(panX, panY)
        c.scale(scale, scale)
        c.drawBitmap(thumb, null, Rect(0, 0, width, height), imgPaint)
        c.restore()
        // Crosshair at frame center.
        val cx = width / 2f
        val cy = height / 2f
        c.drawLine(cx - 10f, cy, cx + 10f, cy, outlinePaint)
        c.drawLine(cx, cy - 10f, cx, cy + 10f, outlinePaint)
        // Amber corner brackets: the output frame boundary (WYSIWYG).
        val arm = (min(width, height) / 7f).coerceAtLeast(14f)
        val l = 2f
        val t = 2f
        val r = width - 2f
        val b = height - 2f
        c.drawLine(l, t, l + arm, t, bracketPaint)
        c.drawLine(l, t, l, t + arm, bracketPaint)
        c.drawLine(r - arm, t, r, t, bracketPaint)
        c.drawLine(r, t, r, t + arm, bracketPaint)
        c.drawLine(l, b, l + arm, b, bracketPaint)
        c.drawLine(l, b, l, b - arm, bracketPaint)
        c.drawLine(r - arm, b, r, b, bracketPaint)
        c.drawLine(r, b, r, b - arm, bracketPaint)
    }
}
