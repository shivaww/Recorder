package com.brollrender.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Console — Broadcast Deck & Field Monitor design system.
 *
 * Semantic engine accents:
 *  - AMBER (Cine Gold) = PIXEL & Kaggle GPU Farm
 *  - TEAL  (Sonic Cyan) = VOICE Studio & Healthy / Verified telemetry
 *  - ALERT (Signal Coral) = Destructive / Fault states
 */
object Console {
    const val VOID = 0xFF0D1117.toInt()       // Obsidian slate chassis background
    const val PANEL = 0xFF161D27.toInt()      // Primary deck card surface
    const val PANEL_HI = 0xFF1E2736.toInt()   // Elevated control bay / secondary button
    const val FIELD_BG = 0xFF0F151D.toInt()   // Recessed input well / unselected pill
    const val EDGE = 0xFF2E3B4E.toInt()       // Crisp hairline steel border
    const val AMBER = 0xFFF5A623.toInt()      // PIXEL / Kaggle GPU primary accent
    const val AMBER_SOFT = 0x2EF5A623.toInt() // Tinted selection fill (Amber)
    const val TEAL = 0xFF2DD4BF.toInt()       // VOICE / Verified primary accent
    const val TEAL_SOFT = 0x2E2DD4BF.toInt()  // Tinted selection fill (Teal)
    const val ALERT = 0xFFF87171.toInt()      // High-legibility coral error/danger
    const val ALERT_SOFT = 0x26F87171.toInt() // Tinted danger fill
    const val CHALK = 0xFFF3F4F6.toInt()      // Alabaster primary ink (WCAG AAA)
    const val CHALK_DIM = 0xFFB0BAC9.toInt()  // Silver mist secondary ink (opaque, readable)

    @Volatile private var tfDisplay: Typeface? = null
    @Volatile private var tfData: Typeface? = null
    @Volatile private var tfDataBold: Typeface? = null
    @Volatile private var tfBody: Typeface? = null

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

    fun display(bold: Boolean = true): Typeface =
        tfDisplay ?: Typeface.create(
            "sans-serif-condensed", if (bold) Typeface.BOLD else Typeface.NORMAL
        )

    fun data(bold: Boolean = false): Typeface =
        (if (bold) tfDataBold else tfData)
            ?: Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)

    fun body(): Typeface = tfBody ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun dp(ctx: Context, v: Int): Int = (ctx.resources.displayMetrics.density * v).roundToInt()

    fun panelBg(
        ctx: Context,
        stroke: Int = EDGE,
        fill: Int = PANEL,
        radiusDp: Int = 8,
        strokeDp: Int = 1
    ): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        setStroke(dp(ctx, strokeDp), stroke)
        cornerRadius = dp(ctx, radiusDp).toFloat()
    }

    fun fieldBg(ctx: Context, stroke: Int = EDGE): GradientDrawable =
        panelBg(ctx, stroke = stroke, fill = FIELD_BG, radiusDp = 6, strokeDp = 1)

    fun badgeBg(ctx: Context, color: Int): GradientDrawable = GradientDrawable().apply {
        val fill = (color and 0x00FFFFFF) or 0x24000000
        setColor(fill)
        setStroke(dp(ctx, 1), color)
        cornerRadius = dp(ctx, 99).toFloat()
    }

    /**
     * Button surface:
     *  - primary: solid engine accent (AMBER or TEAL) with dark VOID text
     *  - danger: tinted coral surface with ALERT stroke
     *  - secondary: elevated slate surface (PANEL_HI) with EDGE stroke
     */
    fun buttonBg(
        ctx: Context,
        primary: Boolean,
        danger: Boolean = false,
        accent: Int = AMBER
    ): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(ctx, 6).toFloat()
        when {
            primary -> setColor(accent)
            danger -> {
                setColor(ALERT_SOFT)
                setStroke(dp(ctx, 1), ALERT)
            }
            else -> {
                setColor(PANEL_HI)
                setStroke(dp(ctx, 1), EDGE)
            }
        }
    }

    /** Segmented toggle pill background — distinct from full-width action buttons. */
    fun pillBg(ctx: Context, selected: Boolean, accent: Int = AMBER): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(ctx, 6).toFloat()
            if (selected) {
                setColor(if (accent == TEAL) TEAL_SOFT else AMBER_SOFT)
                setStroke(dp(ctx, 2), accent)
            } else {
                setColor(FIELD_BG)
                setStroke(dp(ctx, 1), EDGE)
            }
        }

    fun stateColor(state: String, downloaded: Boolean): Int = when {
        downloaded || state == "DONE" -> TEAL
        state == "RENDERING" || state == "ENCODING" || state == "WAITING_START" -> AMBER
        state == "FAILED" || state == "INVALID" -> ALERT
        else -> CHALK_DIM
    }

    fun rail(ctx: Context, accent: Int = TEAL): ProgressBar =
        ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(EDGE)
            minHeight = dp(ctx, 8)
        }

    fun led(ctx: Context, color: Int, diameterDp: Int = 10): View =
        View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(dp(ctx, 2), (color and 0x00FFFFFF) or 0x44000000)
            }
            layoutParams = LinearLayout.LayoutParams(dp(ctx, diameterDp), dp(ctx, diameterDp))
        }

    /**
     * PipelineRail — broadcast step bar showing sequence order (01, 02...),
     * active highlight bar, and captured technical decision notes.
     */
    fun pipelineRail(
        ctx: Context,
        steps: List<Pair<String, String>>,
        active: Int,
        accent: Int
    ): View {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = panelBg(ctx, stroke = EDGE, fill = PANEL, radiusDp = 8)
            setPadding(dp(ctx, 6), dp(ctx, 8), dp(ctx, 6), dp(ctx, 8))
        }
        steps.forEachIndexed { i, stepDef ->
            val isActive = i == active
            val isDone = i < active
            val stepCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(ctx, 6), dp(ctx, 4), dp(ctx, 6), dp(ctx, 4))
                if (isActive) {
                    background = pillBg(ctx, selected = true, accent = accent)
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(ctx, 4)
                }
            }
            val titleColor = when {
                isActive -> accent
                isDone -> CHALK
                else -> CHALK_DIM
            }
            stepCol.addView(TextView(ctx).apply {
                text = String.format("%02d · %s", i + 1, stepDef.first.uppercase())
                textSize = 10f
                setTextColor(titleColor)
                typeface = display()
                letterSpacing = 0.06f
                isSingleLine = true
            })
            val noteText = if (i <= active && stepDef.second.isNotBlank()) stepDef.second else "—"
            stepCol.addView(TextView(ctx).apply {
                text = noteText
                textSize = 10f
                setTextColor(if (isActive) CHALK else CHALK_DIM)
                typeface = data(isActive)
                isSingleLine = true
                setPadding(0, dp(ctx, 2), 0, 0)
            })
            container.addView(stepCol)
        }
        return container
    }
}

/**
 * FarmPulseView — live dual-T4 Kaggle GPU utilization telemetry bar.
 */
class FarmPulseView(ctx: Context) : View(ctx) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Console.FIELD_BG }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Console.EDGE
        style = Paint.Style.STROKE
        strokeWidth = Console.dp(ctx, 1).toFloat()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Console.CHALK
        textSize = Console.dp(ctx, 11).toFloat()
        typeface = Console.data(true)
    }
    private val rect = RectF()
    private var gpus: List<Pair<Int, Int>> = emptyList()

    fun setGpus(list: List<Pair<Int, Int>>) {
        gpus = list
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), Console.dp(context, 28))

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (width == 0) return
        val r = Console.dp(context, 6).toFloat()
        val h = height.toFloat()
        if (gpus.isEmpty()) {
            rect.set(0f, 0f, width.toFloat(), h)
            c.drawRoundRect(rect, r, r, trackPaint)
            c.drawRoundRect(rect, r, r, borderPaint)
            labelPaint.color = Console.CHALK_DIM
            c.drawText("KAGGLE T4 TELEMETRY · STANDBY (NO ACTIVE POLL)", Console.dp(context, 10).toFloat(), h * 0.66f, labelPaint)
            return
        }
        labelPaint.color = Console.CHALK
        val gap = Console.dp(context, 8).toFloat()
        val segW = (width - gap * (gpus.size - 1)) / gpus.size
        gpus.forEachIndexed { i, gpu ->
            val x = i * (segW + gap)
            rect.set(x, 0f, x + segW, h)
            c.drawRoundRect(rect, r, r, trackPaint)
            val pct = gpu.second.coerceIn(0, 100)
            if (pct > 0) {
                val w = (segW * pct / 100f).coerceAtLeast(r * 2)
                fillPaint.color = if (pct >= 85) 0x55F5A623 else 0x552DD4BF
                rect.set(x, 0f, (x + w).coerceAtMost(x + segW), h)
                c.drawRoundRect(rect, r, r, fillPaint)
            }
            rect.set(x, 0f, x + segW, h)
            borderPaint.color = if (pct > 0) Console.TEAL else Console.EDGE
            c.drawRoundRect(rect, r, r, borderPaint)
            c.drawText(
                "GPU ${gpu.first}  ·  ${pct}% LOAD",
                x + Console.dp(context, 10),
                h * 0.66f,
                labelPaint
            )
        }
    }
}
