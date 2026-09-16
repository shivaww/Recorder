package com.brollrender.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Console — the app's design system. One palette, three type roles, and the
 * card / button / rail / pulse builders every screen composes from.
 * Identity: a pocket mission-control for a remote GPU render farm.
 *
 * Engine accents are semantic: AMBER = PIXEL (video engine), TEAL = VOICE
 * (audio engine). A screen states which engine it operates through the accent
 * it carries — the accent is information, never decoration.
 */
object Console {
    // Palette — named tokens; CHALK_DIM is secondary text.
    const val VOID = 0xFF0A0D12.toInt()       // room with the monitors off
    const val PANEL = 0xFF141926.toInt()      // raised console surface, blue-cast steel
    const val EDGE = 0xFF232B3D.toInt()       // hairline steel stroke
    const val AMBER = 0xFFFFB454.toInt()      // PIXEL — video engine accent
    const val TEAL = 0xFF3FD9B0.toInt()       // VOICE — audio engine accent
    const val ALERT = 0xFFFF5C5C.toInt()      // error / destructive
    const val CHALK = 0xFFE8E4D8.toInt()      // primary ink, warm chalk
    const val CHALK_DIM = 0x9E9BA3B5.toInt()  // secondary ink

    // ---- type: three bundled faces (res/font), loaded once per process ----
    @Volatile private var tfDisplay: Typeface? = null
    @Volatile private var tfData: Typeface? = null
    @Volatile private var tfDataBold: Typeface? = null
    @Volatile private var tfBody: Typeface? = null

    /** Call once from each Activity's onCreate, before building any view. */
    fun init(ctx: Context) {
        if (tfDisplay == null) tfDisplay = fetch(ctx, R.font.chakra_petch)
        if (tfData == null) tfData = fetch(ctx, R.font.plex_mono)
        if (tfDataBold == null) tfDataBold = fetch(ctx, R.font.plex_mono_med)
        if (tfBody == null) tfBody = fetch(ctx, R.font.saira)
    }

    private fun fetch(ctx: Context, id: Int): Typeface? = try {
        ctx.resources.getFont(id)
    } catch (_: Exception) {
        null
    }

    // Type roles: DISPLAY = titles + control labels (Chakra Petch, used with
    // restraint); DATA = every number, id, eta (IBM Plex Mono); BODY = helper
    // sentences (Saira).
    fun display(bold: Boolean = true): Typeface =
        tfDisplay ?: Typeface.create(
            "sans-serif-condensed", if (bold) Typeface.BOLD else Typeface.NORMAL
        )

    fun data(bold: Boolean = false): Typeface =
        (if (bold) tfDataBold else tfData)
            ?: Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)

    fun body(): Typeface = tfBody ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun dp(ctx: Context, v: Int): Int = (ctx.resources.displayMetrics.density * v).roundToInt()

    fun panelBg(ctx: Context, stroke: Int = EDGE): GradientDrawable = GradientDrawable().apply {
        setColor(PANEL)
        setStroke(dp(ctx, 1), stroke)
        cornerRadius = dp(ctx, 4).toFloat()
    }

    /** Primary fills with the engine accent (AMBER by default; VOICE screens
     *  pass TEAL). Quiet buttons are steel strokes. */
    fun buttonBg(
        ctx: Context,
        primary: Boolean,
        danger: Boolean = false,
        accent: Int = AMBER
    ): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(ctx, 4).toFloat()
        if (primary) setColor(accent)
        else {
            setColor(0x00000000)
            setStroke(dp(ctx, 1), if (danger) ALERT else EDGE)
        }
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

    /** Status LED — a small lamp that carries machine state (alive / offline). */
    fun led(ctx: Context, color: Int, diameterDp: Int = 8): View =
        View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            layoutParams = LinearLayout.LayoutParams(dp(ctx, diameterDp), dp(ctx, diameterDp))
        }

    /**
     * PipelineRail — the signature element. The render pipeline is a real
     * sequence, so the rail states it: active step lit in the engine accent,
     * completed steps carrying their captured decision (e.g. "720p · 30").
     * steps = (label, note) pairs; note may be "" until the step is taken.
     */
    fun pipelineRail(
        ctx: Context,
        steps: List<Pair<String, String>>,
        active: Int,
        accent: Int
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        steps.forEachIndexed { i, stepDef ->
            if (i > 0) {
                row.addView(View(ctx).apply {
                    setBackgroundColor(EDGE)
                    layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 1), 1f)
                })
            }
            val step = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(ctx, 6), dp(ctx, 4), dp(ctx, 6), dp(ctx, 4))
            }
            step.addView(TextView(ctx).apply {
                text = stepDef.first.uppercase()
                textSize = 10f
                setTextColor(if (i == active) accent else CHALK_DIM)
                typeface = display()
                letterSpacing = 0.08f
            })
            step.addView(TextView(ctx).apply {
                text = if (i <= active) stepDef.second else ""
                textSize = 9f
                setTextColor(if (i <= active) CHALK else CHALK_DIM)
                typeface = data()
                minHeight = dp(ctx, 11)
            })
            row.addView(step)
        }
        return row
    }
}

/**
 * FarmPulse — the farm's heartbeat: one segmented rail per GPU, fill width
 * = live utilization from the /gpu poll, label set inside.
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
