package com.brollrender.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ProgressBar
import kotlin.math.roundToInt

/**
 * Console — the app's design system. One palette, three type roles, and the
 * card / button / rail / pulse builders every screen composes from.
 * Identity: a pocket mission-control for a remote GPU render farm.
 */
object Console {
    // Palette — six named tokens; CHALK_DIM is secondary text.
    const val VOID = 0xFF0B0E11.toInt()      // background field
    const val PANEL = 0xFF151B21.toInt()     // card surface
    const val EDGE = 0xFF232C33.toInt()      // hairlines + ghost fills
    const val AMBER = 0xFFFFB454.toInt()     // primary action / attention
    const val TEAL = 0xFF3FD8C2.toInt()      // progress / healthy
    const val ALERT = 0xFFFF6B5E.toInt()     // error / destructive
    const val CHALK = 0xFFE6EDF3.toInt()     // primary text
    const val CHALK_DIM = 0x9EE6EDF3.toInt() // secondary text (62% chalk)

    // Type roles: DISPLAY = titles + control labels; DATA = every number,
    // id, eta; BODY = helper sentences.
    fun display(bold: Boolean = true): Typeface =
        Typeface.create("sans-serif-condensed", if (bold) Typeface.BOLD else Typeface.NORMAL)

    fun data(bold: Boolean = false): Typeface =
        Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)

    fun body(): Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun dp(ctx: Context, v: Int): Int = (ctx.resources.displayMetrics.density * v).roundToInt()

    fun panelBg(ctx: Context, stroke: Int = EDGE): GradientDrawable = GradientDrawable().apply {
        setColor(PANEL)
        setStroke(dp(ctx, 1), stroke)
        cornerRadius = dp(ctx, 4).toFloat()
    }

    fun buttonBg(ctx: Context, primary: Boolean, danger: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(ctx, 4).toFloat()
            if (primary) setColor(AMBER)
            else { setColor(0x00000000); setStroke(dp(ctx, 1), if (danger) ALERT else EDGE) }
        }

    /** Stripe/accent per job state — encodes truth, never decoration. */
    fun stateColor(state: String, downloaded: Boolean): Int = when {
        downloaded || state == "DONE" -> TEAL
        state == "RENDERING" || state == "ENCODING" -> AMBER
        state == "FAILED" || state == "INVALID" -> ALERT
        else -> CHALK_DIM
    }

    fun rail(ctx: Context): ProgressBar =
        ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(TEAL)
            progressBackgroundTintList = ColorStateList.valueOf(EDGE)
            minHeight = dp(ctx, 6)
        }
}

/**
 * FarmPulse — the signature element: one segmented rail per GPU, fill width
 * = live utilization from the /gpu poll, label set inside. The farm's
 * heartbeat; the thing this console is remembered by.
 */
class FarmPulseView(ctx: Context) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Console.CHALK
        textSize = Console.dp(ctx, 9).toFloat()
        typeface = Console.data()
    }
    private var gpus: List<Pair<Int, Int>> = emptyList()

    fun setGpus(list: List<Pair<Int, Int>>) {
        gpus = list
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), Console.dp(context, 18))

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (gpus.isEmpty() || width == 0) return
        val gap = Console.dp(context, 6).toFloat()
        val segW = (width - gap * (gpus.size - 1)) / gpus.size
        val h = height.toFloat()
        gpus.forEachIndexed { i, gpu ->
            val x = i * (segW + gap)
            fill.color = Console.EDGE
            c.drawRect(x, 0f, x + segW, h, fill)
            val w = segW * gpu.second.coerceIn(0, 100) / 100f
            fill.color = if (gpu.second >= 85) Console.AMBER else Console.TEAL
            c.drawRect(x, 0f, x + w, h, fill)
            c.drawText("GPU${gpu.first} ${gpu.second}%", x + Console.dp(context, 4), h * 0.74f, label)
        }
    }
}
