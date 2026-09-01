package com.brollrender.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Manual framing transform: zoom factor + normalized pan fractions. */
data class ZoomTransform(
    val scale: Float,
    val panNx: Float,
    val panNy: Float
)

/**
 * Interactive manual framing for the PREVIEW screen.
 *
 * Two editing modes:
 *  - PINCH: pinch 0.5x-4x + drag pan + double-tap reset.
 *  - CROP: gallery/editor-style - four draggable corner handles + move-rect
 *    over the base (unscaled) page, rule-of-thirds grid, dimmed scrim
 *    outside the rect; APPLY maps the 16:9 crop to the full output frame.
 *
 * The committed transform is injected into the WebView as a CSS transform
 * on <html> at render time: zoomed content re-rasterizes (crisp text and
 * vectors, NEVER a bitmap upscale of the capture - rule 2 intact). No screen
 * recording anywhere - the render path is unchanged.
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
    var onModeChange: ((cropEditing: Boolean) -> Unit)? = null

    private var scale = initial?.scale ?: 1f
    private var panX = 0f // view px
    private var panY = 0f

    // CROP-mode state (rect in view px, always kept 16:9).
    private var cropMode = false
    private val crop = RectF()
    private var dragMode = -1 // -1 none, -2 move rect, 0..3 corner index
    private var moveDx = 0f
    private var moveDy = 0f

    private val voidPaint = Paint().apply { color = 0xFF0A0C10.toInt() }
    private val imgPaint = Paint().apply { isFilterBitmap = true } // preview-only smoothing
    private val scrimPaint = Paint().apply { color = 0x88000000 }
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
    private val handlePaint = Paint().apply {
        color = 0xFFFFB454.toInt()
        style = Paint.Style.FILL
    }
    private val handleStrokePaint = Paint().apply {
        color = 0xFF0A0C10.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    fun transform(): ZoomTransform = ZoomTransform(
        scale,
        if (width > 0) panX / width else 0f,
        if (height > 0) panY / height else 0f
    )

    /** FULL preset: whole page, scale 1, no pan. */
    fun resetFull() {
        scale = 1f
        panX = 0f
        panY = 0f
        exitCrop()
        clampPan()
        invalidate()
        onTransform?.invoke(transform())
    }

    /** CENTER preset: keep the current zoom, center the content. */
    fun centerContent() {
        if (width > 0 && height > 0) {
            panX = (width - width * scale) / 2f
            panY = (height - height * scale) / 2f
        }
        exitCrop()
        clampPan()
        invalidate()
        onTransform?.invoke(transform())
    }

    fun enterCrop() {
        if (width == 0 || height == 0) return
        cropMode = true
        val ix = width * 0.05f
        val iy = height * 0.05f
        crop.set(ix, iy, width - ix, height - iy) // proportional inset keeps 16:9
        dragMode = -1
        invalidate()
        onModeChange?.invoke(true)
    }

    fun applyCrop() {
        if (!cropMode || width == 0) return
        val s = width / crop.width()
        scale = s
        panX = -crop.left * s
        panY = -crop.top * s
        exitCrop()
        clampPan()
        invalidate()
        onTransform?.invoke(transform())
    }

    private fun exitCrop() {
        if (cropMode) {
            cropMode = false
            dragMode = -1
            onModeChange?.invoke(false)
        }
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
                if (cropMode) return false
                panX -= dx
                panY -= dy
                clampPan()
                invalidate()
                onTransform?.invoke(transform())
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetFull()
                return true
            }
        })

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (cropMode) return false
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
        if (cropMode) {
            handleCropTouch(e)
            return true
        }
        scaleDetector.onTouchEvent(e)
        panDetector.onTouchEvent(e)
        return true
    }

    // ===================== CROP-mode touch + geometry =====================

    private fun cornerAt(i: Int): Pair<Float, Float> = when (i) {
        0 -> crop.left to crop.top
        1 -> crop.right to crop.top
        2 -> crop.right to crop.bottom
        else -> crop.left to crop.bottom
    }

    private fun hitCorner(x: Float, y: Float): Int {
        val r = 48f
        for (i in 0..3) {
            val (cx, cy) = cornerAt(i)
            if (abs(x - cx) < r && abs(y - cy) < r) return i
        }
        return -1
    }

    private fun handleCropTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragMode = hitCorner(e.x, e.y)
                if (dragMode < 0 && crop.contains(e.x, e.y)) {
                    dragMode = -2
                    moveDx = e.x - crop.left
                    moveDy = e.y - crop.top
                }
            }
            MotionEvent.ACTION_MOVE -> {
                when (dragMode) {
                    -2 -> {
                        val nl = (e.x - moveDx).coerceIn(0f, width - crop.width())
                        val nt = (e.y - moveDy).coerceIn(0f, height - crop.height())
                        crop.offsetTo(nl, nt)
                        invalidate()
                    }
                    0, 1, 2, 3 -> setCropFromPoint(e.x, e.y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragMode = -1
        }
    }

    /** Move corner [dragMode] to (x, y); opposite corner pinned; stays 16:9. */
    private fun setCropFromPoint(x: Float, y: Float) {
        val (ox, oy) = when (dragMode) {
            0 -> cornerAt(2) // drag TL, pin BR
            1 -> cornerAt(3) // drag TR, pin BL
            2 -> cornerAt(0) // drag BR, pin TL
            else -> cornerAt(1) // drag BL, pin TR
        }
        val sx = if (x >= ox) 1f else -1f
        val sy = if (y >= oy) 1f else -1f
        val minH = height * 0.12f
        var h = abs(y - oy).coerceAtLeast(minH)
        var w = h * 16f / 9f
        val maxByX = if (sx > 0) width - ox else ox
        val maxByY = if (sy > 0) height - oy else oy
        if (w > maxByX) {
            w = maxByX
            h = w * 9f / 16f
        }
        if (h > maxByY) {
            h = maxByY
            w = h * 16f / 9f
        }
        if (h < minH) {
            h = minH
            w = h * 16f / 9f
        }
        val l = if (sx > 0) ox else ox - w
        val t = if (sy > 0) oy else oy - h
        crop.set(l, t, l + w, t + h)
        invalidate()
    }

    // ===================== measure / layout / draw =====================

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = View.MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 9f / 16f).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampPan()
        if (cropMode) enterCrop() // re-fit the crop rect to the new size
    }

    override fun onDraw(c: Canvas) {
        if (cropMode) {
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), voidPaint)
            c.drawBitmap(thumb, null, Rect(0, 0, width, height), imgPaint)
            // Dim everything outside the crop rect (editor-style scrim).
            c.drawRect(0f, 0f, width.toFloat(), crop.top, scrimPaint)
            c.drawRect(0f, crop.bottom, width.toFloat(), height.toFloat(), scrimPaint)
            c.drawRect(0f, crop.top, crop.left, crop.bottom, scrimPaint)
            c.drawRect(crop.right, crop.top, width.toFloat(), crop.bottom, scrimPaint)
            // Outline + rule-of-thirds grid.
            c.drawRect(crop, outlinePaint)
            for (i in 1..2) {
                val gx = crop.left + crop.width() * i / 3f
                val gy = crop.top + crop.height() * i / 3f
                c.drawLine(gx, crop.top, gx, crop.bottom, outlinePaint)
                c.drawLine(crop.left, gy, crop.right, gy, outlinePaint)
            }
            // Corner handles.
            for (i in 0..3) {
                val (cx, cy) = cornerAt(i)
                c.drawCircle(cx, cy, 16f, handlePaint)
                c.drawCircle(cx, cy, 16f, handleStrokePaint)
            }
            return
        }
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
