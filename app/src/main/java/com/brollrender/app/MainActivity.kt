package com.brollrender.app

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Single-Activity app, plain views, zero third-party dependencies. Four
 * screens per spec section 8: PICK, PREVIEW (app-drawn amber detection
 * overlay), RENDER, DONE. All WebView/codec work lives in RenderEngine;
 * this class owns UI, the SAF picker, the render worker thread, and the
 * MediaStore export.
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_PICK = 1
        private const val VOID = 0xFF0A0C10.toInt()
        private const val AMBER = 0xFFFFB454.toInt()
        private const val AMBER_DIM = 0x99FFB454.toInt()
        private const val TXT = 0xFFF2F4F8.toInt()
        private const val TXT2 = 0xFF8A93A6.toInt()
        private const val RED = 0xFFFF6B6B.toInt()
        private const val STROKE = 0xFF232833.toInt()
    }

    // PICK settings
    private var resW = 1920
    private var resH = 1080
    private var fps = 30

    // PREVIEW state
    private var htmlFile: File? = null
    private var htmlName = "broll.html"
    private var prepared: RenderEngine.PrepareResult.Ok? = null

    // RENDER state
    private var renderThread: Thread? = null

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var renderPaused = false

    @Volatile
    private var rendering = false

    // DONE state
    private var doneUri: Uri? = null
    private var doneName = ""

    private lateinit var root: FrameLayout
    private lateinit var engine: RenderEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = RenderEngine(this)
        root = FrameLayout(this)
        root.setBackgroundColor(VOID)
        setContentView(root)
        showPickScreen()
    }

    // Section 7.15: pause the loop while backgrounded mid-render; keep the
    // screen on only while rendering in the foreground.
    override fun onPause() {
        super.onPause()
        if (rendering) renderPaused = true
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        renderPaused = false
        if (rendering) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        cancelRequested = true
        renderThread?.join(1500)
        engine.destroy() // web.destroy() ONLY here (pitfall 7.9)
        super.onDestroy()
    }

    private fun showScreen(v: View) {
        root.removeAllViews()
        root.addView(
            v,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).roundToInt()

    private fun spacer(h: Int): View =
        View(this).apply { layoutParams = LinearLayout.LayoutParams(1, h) }

    private fun monoTv(text: String, sizeSp: Int, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp.toFloat()
            setTextColor(color)
            typeface = Typeface.create(
                Typeface.MONOSPACE,
                if (bold) Typeface.BOLD else Typeface.NORMAL
            )
        }

    private fun styleButton(b: Button, filled: Boolean) {
        if (filled) {
            b.setBackgroundColor(AMBER)
            b.setTextColor(VOID)
        } else {
            b.setBackgroundColor(STROKE)
            b.setTextColor(AMBER)
        }
    }

    private fun mkButton(label: String, filled: Boolean = false): Button =
        Button(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            setPadding(dp(10), dp(9), dp(10), dp(9))
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            styleButton(this, filled)
        }

    // ===================== PICK SCREEN (section 8.1) =====================

    private fun showPickScreen() {
        val pad = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER_VERTICAL
        }
        col.addView(monoTv("BROLLRENDER", 26, AMBER, true))
        col.addView(monoTv("HTML -> exact 16:9 MP4 / offscreen render", 12, TXT2))
        col.addView(spacer(dp(28)))
        col.addView(mkButton("CHOOSE HTML", filled = true).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
            )
            setOnClickListener { launchPicker() }
        })
        if (htmlFile != null) {
            col.addView(spacer(dp(12)))
            col.addView(monoTv("last: $htmlName", 12, TXT2))
        }
        col.addView(spacer(dp(34)))

        val resRow = toggleRow(
            col, "RESOLUTION",
            listOf(
                "1080p" to { resW = 1920; resH = 1080 },
                "720p" to { resW = 1280; resH = 720 }
            ),
            if (resW == 1920) 0 else 1
        )
        val fpsRow = toggleRow(
            col, "FPS",
            listOf(
                "30" to { fps = 30 },
                "24" to { fps = 24 }
            ),
            if (fps == 30) 0 else 1
        )
        // MODE is a preset: selecting it snaps the resolution + FPS rows too.
        toggleRow(
            col, "MODE",
            listOf(
                "FINAL" to {
                    resRow.select(0); resW = 1920; resH = 1080
                    fpsRow.select(0); fps = 30
                },
                "DRAFT" to {
                    resRow.select(1); resW = 1280; resH = 720
                    fpsRow.select(1); fps = 24
                }
            ),
            if (resW == 1920 && fps == 30) 0 else 1
        )
        prepared?.let { p ->
            col.addView(spacer(dp(14)))
            col.addView(monoTv("DURATION  auto ${p.durationMs / 1000} s - editable on preview", 12, TXT2))
        }
        showScreen(col)
    }

    /** Two-option toggle row; select() restyles without re-running actions. */
    private inner class ToggleRow(opts: List<Pair<String, () -> Unit>>) {
        val buttons: Array<Button> = Array(opts.size) { i ->
            mkButton(opts[i].first).apply {
                setOnClickListener {
                    select(i)
                    opts[i].second()
                }
            }
        }

        fun select(i: Int) {
            for (j in buttons.indices) styleButton(buttons[j], j == i)
        }
    }

    private fun toggleRow(
        parent: LinearLayout,
        label: String,
        opts: List<Pair<String, () -> Unit>>,
        initial: Int
    ): ToggleRow {
        val row = ToggleRow(opts)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        box.addView(monoTv(label, 11, TXT2).apply {
            layoutParams = LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        for (b in row.buttons) {
            box.addView(
                b,
                LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(6) }
            )
        }
        row.select(initial)
        parent.addView(box)
        parent.addView(spacer(dp(10)))
        return row
    }

    private fun showBusy(message: String) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        col.addView(monoTv(message, 14, AMBER))
        col.addView(spacer(dp(12)))
        col.addView(ProgressBar(this))
        showScreen(col)
    }

    private fun launchPicker() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "text/html"
        startActivityForResult(i, REQ_PICK)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        showBusy("COPYING HTML")
        Thread {
            try {
                // SAF stream fully copied to cache BEFORE loadUrl (pitfall 7.12).
                val f = File(cacheDir, "broll.html")
                contentResolver.openInputStream(uri)?.use { ins ->
                    FileOutputStream(f).use { outs -> ins.copyTo(outs) }
                } ?: throw RuntimeException("cannot open selected file")
                htmlFile = f
                htmlName = queryName(uri)
                runOnUiThread { showBusy("ANALYZING PAGE") }
                val result = engine.prepare(f, resW, resH)
                runOnUiThread {
                    when (result) {
                        is RenderEngine.PrepareResult.Ok -> showPreviewScreen(result)
                        is RenderEngine.PrepareResult.Fail -> showErrorScreen(result.message)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { showErrorScreen("pick failed: ${e.message}") }
            }
        }.start()
    }

    private fun queryName(uri: Uri): String = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else "broll.html"
        } ?: "broll.html"
    } catch (e: Exception) {
        "broll.html"
    }

    // Section 8.2: validation failure = the specific error + what to check.
    private fun showErrorScreen(message: String) {
        val pad = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER_VERTICAL
        }
        col.addView(monoTv("VALIDATION FAILED", 16, RED, true))
        col.addView(spacer(dp(10)))
        col.addView(monoTv(message, 13, TXT))
        col.addView(spacer(dp(12)))
        col.addView(
            monoTv(
                "check: the HTML needs a .fit (16:9) element containing .stage, " +
                    "CSS keyframe animations, and webfonts that load",
                12,
                TXT2
            )
        )
        col.addView(spacer(dp(24)))
        col.addView(mkButton("BACK").apply { setOnClickListener { showPickScreen() } })
        showScreen(col)
    }

    // ===================== PREVIEW SCREEN (section 8.2) =====================

    private fun showPreviewScreen(p: RenderEngine.PrepareResult.Ok) {
        prepared = p
        val pad = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // The app's OWN overlay (section 4.6): amber corner brackets, rect
        // outline and crosshair at the scaled detection, drawn in Kotlin on
        // top of the t=0 thumbnail - proof the corners were found before the
        // user burns 5 minutes.
        col.addView(ImageView(this).apply {
            setImageBitmap(drawDetectionOverlay(p))
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
        col.addView(spacer(dp(6)))
        col.addView(
            monoTv(
                "FRAME ${resW}x${resH} · LOCKED · CORNERS ±${p.cornerDeviationPx}px",
                13, AMBER, true
            )
        )
        col.addView(spacer(dp(8)))
        col.addView(monoTv("animations locked: ${p.animCount}", 12, TXT2))
        col.addView(
            monoTv(
                if (p.fontsLoaded) "fonts: OK (anton + plex)"
                else "fonts: TIMEOUT WARNING - look may differ",
                12,
                if (p.fontsLoaded) TXT2 else AMBER
            )
        )
        col.addView(monoTv("detected timeline: ${p.durationMs / 1000.0} s", 12, TXT2))
        col.addView(spacer(dp(10)))

        // Duration: auto-detected, editable, clamped to 5-600 s on start.
        col.addView(monoTv("DURATION (s)", 11, TXT2))
        val dur = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((((p.durationMs + 999) / 1000).coerceIn(5, 600)).toString())
            textSize = 14f
            setTextColor(TXT)
            setBackgroundColor(STROKE)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(140), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        col.addView(dur)
        col.addView(spacer(dp(18)))

        col.addView(mkButton("START RENDER", filled = true).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
            )
            setOnClickListener {
                val secs = dur.text.toString().toIntOrNull() ?: 5
                startRender(p, secs.coerceIn(5, 600))
            }
        })
        col.addView(spacer(dp(8)))
        col.addView(mkButton("BACK").apply { setOnClickListener { showPickScreen() } })

        showScreen(android.widget.ScrollView(this).apply { addView(col) })
    }

    /** Thumbnail + the app-drawn amber detection overlay (section 4.6). */
    private fun drawDetectionOverlay(p: RenderEngine.PrepareResult.Ok): Bitmap {
        val bmp = p.thumb.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(bmp)
        val sx = bmp.width / resW.toFloat()
        val sy = bmp.height / resH.toFloat()
        val r = Rect(
            (p.frameRect.left * sx).roundToInt(),
            (p.frameRect.top * sy).roundToInt(),
            (p.frameRect.right * sx).roundToInt(),
            (p.frameRect.bottom * sy).roundToInt()
        )
        val outline = Paint().apply {
            color = AMBER_DIM
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        val bracket = Paint().apply {
            color = AMBER
            style = Paint.Style.STROKE
            strokeWidth = 3f
            strokeCap = Paint.Cap.ROUND
        }
        c.drawRect(
            r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(),
            outline
        )
        // Crosshair at the frame center.
        val cx = (r.left + r.right) / 2f
        val cy = (r.top + r.bottom) / 2f
        c.drawLine(cx - 12f, cy, cx + 12f, cy, outline)
        c.drawLine(cx, cy - 12f, cx, cy + 12f, outline)
        // Four corner brackets, arm ~ 1/12 of the shorter frame side.
        val arm = (minOf(r.width(), r.height()) / 12f).coerceAtLeast(10f)
        val lft = r.left.toFloat(); val top = r.top.toFloat()
        val rgt = r.right.toFloat(); val bot = r.bottom.toFloat()
        c.drawLine(lft, top, lft + arm, top, bracket)
        c.drawLine(lft, top, lft, top + arm, bracket)
        c.drawLine(rgt - arm, top, rgt, top, bracket)
        c.drawLine(rgt, top, rgt, top + arm, bracket)
        c.drawLine(lft, bot, lft + arm, bot, bracket)
        c.drawLine(lft, bot, lft, bot - arm, bracket)
        c.drawLine(rgt - arm, bot, rgt, bot, bracket)
        c.drawLine(rgt, bot, rgt, bot - arm, bracket)
        return bmp
    }

    private fun startRender(p: RenderEngine.PrepareResult.Ok, durationSec: Int) {
        cancelRequested = false
        rendering = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showRenderScreen(durationSec)
        renderThread = Thread {
            val outcome = runRenderJob(p, durationSec)
            rendering = false
            runOnUiThread {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                when (outcome) {
                    is RenderEngine.RenderOutcome.Completed -> handleCompleted(outcome)
                    is RenderEngine.RenderOutcome.Cancelled -> {
                        prepared?.let { showPreviewScreen(it) } ?: showPickScreen()
                    }
                    is RenderEngine.RenderOutcome.Failed -> showErrorScreen(outcome.message)
                }
            }
        }
        renderThread?.start()
    }

    // ===================== RENDER SCREEN (section 8.3) =====================

    private lateinit var renderBar: ProgressBar
    private lateinit var renderStatus: TextView
    private var tempOutput: File? = null

    private fun showRenderScreen(durationSec: Int) {
        val pad = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER_VERTICAL
        }
        col.addView(monoTv("RENDERING", 16, AMBER, true))
        col.addView(monoTv("${resW}x${resH} @ ${fps}fps · ${durationSec}s", 12, TXT2))
        col.addView(spacer(dp(14)))
        renderBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = durationSec * fps
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8)
            )
        }
        col.addView(renderBar)
        col.addView(spacer(dp(8)))
        renderStatus = monoTv("frame 0/${durationSec * fps}", 12, TXT2)
        col.addView(renderStatus)
        col.addView(spacer(dp(24)))
        col.addView(mkButton("CANCEL").apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
            )
            setOnClickListener {
                cancelRequested = true
                isEnabled = false
                text = "CANCELLING..."
                renderStatus.text = "aborting - releasing encoder, deleting partial file"
            }
        })
        showScreen(col)
    }

    private fun updateRenderProgress(f: Int, total: Int, rateFps: Double, etaSec: Long) {
        if (!::renderBar.isInitialized) return
        renderBar.progress = f.coerceAtMost(renderBar.max)
        renderStatus.text = String.format(
            Locale.US, "frame %d/%d · %.1f f/s · ETA %s", f, total, rateFps, fmtEta(etaSec)
        )
    }

    private fun fmtEta(sec: Long): String {
        if (sec < 0) return "--"
        return String.format(Locale.US, "%dm %02ds", sec / 60, sec % 60)
    }

    // ===================== RENDER JOB =====================

    private fun runRenderJob(
        p: RenderEngine.PrepareResult.Ok,
        durationSec: Int
    ): RenderEngine.RenderOutcome {
        val total = durationSec * fps
        // Section 6: 16 Mbps final (1080p), 8 Mbps draft (720p).
        val bitRate = if (resW >= 1920) 16_000_000 else 8_000_000
        val out = File(cacheDir, "render_tmp.mp4")
        tempOutput = out
        return engine.render(
            frameRect = p.frameRect,
            fps = fps,
            totalFrames = total,
            outputFile = out,
            bitRate = bitRate,
            onProgress = { f, t, rate, eta ->
                runOnUiThread { updateRenderProgress(f, t, rate, eta) }
            },
            isCancelled = {
                // Section 7.15: backgrounded mid-render = graceful pause (wait
                // at the frame boundary), not an abort.
                while (renderPaused && !cancelRequested) Thread.sleep(200)
                cancelRequested
            }
        )
    }

    // ===================== EXPORT (section 5.9) =====================

    /**
     * MediaStore export with the IS_PENDING pattern. frame0.png goes to
     * Pictures/BrollRender (MediaStore forbids images under Movies/).
     */
    private fun exportToMediaStore(firstFrame: Bitmap, tempFile: File): Uri? {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(java.util.Date())
        val vName = "BrollRender_$stamp.mp4"
        val vValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, vName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/BrollRender")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, vValues)
            ?: return null
        try {
            contentResolver.openOutputStream(uri)?.use { outs ->
                tempFile.inputStream().use { it.copyTo(outs) }
            } ?: throw RuntimeException("cannot open MediaStore output stream")

            // frame0.png QC export (acceptance 4: eyeball first-frame match).
            val pValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "BrollRender_${stamp}_frame0.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BrollRender")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val pngUri = contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pValues
            )
            if (pngUri != null) {
                try {
                    contentResolver.openOutputStream(pngUri)?.use { outs ->
                        firstFrame.compress(Bitmap.CompressFormat.PNG, 100, outs)
                    }
                    pValues.clear()
                    pValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(pngUri, pValues, null, null)
                } catch (e: Exception) {
                    contentResolver.delete(pngUri, null, null)
                }
            }

            vValues.clear()
            vValues.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(uri, vValues, null, null)
            tempFile.delete()
            return uri
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            tempFile.delete()
            return null
        }
    }

    // ===================== DONE SCREEN (section 8.4) =====================

    private fun handleCompleted(outcome: RenderEngine.RenderOutcome.Completed) {
        val temp = tempOutput
        if (temp == null || !temp.exists()) {
            showErrorScreen("render finished but the temp file is missing")
            return
        }
        showBusy("SAVING + VERIFYING")
        Thread {
            val uri = exportToMediaStore(outcome.firstFrame, temp)
            if (uri == null) {
                runOnUiThread { showErrorScreen("export failed - could not write to MediaStore") }
                return@Thread
            }
            // Built-in self-verification (section 5.9): read the file back.
            var durMs = 0L
            var vw = 0
            var vh = 0
            var size = 0L
            try {
                val r = MediaMetadataRetriever()
                r.setDataSource(this, uri)
                durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                r.release()
                contentResolver.query(
                    uri, arrayOf(MediaStore.Video.Media.SIZE), null, null, null
                )?.use { c -> if (c.moveToFirst()) size = c.getLong(0) }
            } catch (e: Exception) {
                // verification is best-effort; the card shows what it got
            }
            runOnUiThread { showDoneScreen(uri, durMs, vw, vh, size) }
        }.start()
    }

    private fun fmtSize(bytes: Long): String =
        if (bytes >= (1 shl 20)) String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        else String.format(Locale.US, "%.0f KB", bytes / 1024.0)

    private fun showDoneScreen(uri: Uri, durMs: Long, vw: Int, vh: Int, size: Long) {
        doneUri = uri
        val pad = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER_VERTICAL
        }
        col.addView(monoTv("RENDER COMPLETE", 18, AMBER, true))
        col.addView(spacer(dp(12)))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(STROKE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        card.addView(monoTv("FILE", 10, TXT2))
        card.addView(monoTv(queryName(uri), 13, TXT, true))
        card.addView(spacer(dp(8)))
        card.addView(monoTv("size      ${fmtSize(size)}", 12, TXT2))
        card.addView(
            monoTv(
                "duration  ${String.format(Locale.US, "%.1f", durMs / 1000.0)} s",
                12, TXT2
            )
        )
        card.addView(monoTv("video     ${vw}x${vh}", 12, TXT2))
        card.addView(monoTv("frame0.png saved for QC (Pictures/BrollRender)", 11, TXT2))
        val verified = vw == resW && vh == resH
        card.addView(spacer(dp(8)))
        card.addView(
            if (verified) monoTv("VERIFIED ${vw}x${vh} - pixel-exact", 12, AMBER, true)
            else monoTv("MISMATCH: expected ${resW}x${resH}, got ${vw}x${vh}", 12, RED, true)
        )
        col.addView(card)
        col.addView(spacer(dp(20)))

        col.addView(mkButton("OPEN", filled = true).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
            setOnClickListener {
                try {
                    val i = Intent(Intent.ACTION_VIEW)
                    i.setDataAndType(uri, "video/mp4")
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(i)
                } catch (e: Exception) {
                }
            }
        })
        col.addView(spacer(dp(8)))
        col.addView(mkButton("SHARE").apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
            setOnClickListener {
                val i = Intent(Intent.ACTION_SEND)
                i.type = "video/mp4"
                i.putExtra(Intent.EXTRA_STREAM, uri)
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(i, "Share render"))
            }
        })
        col.addView(spacer(dp(8)))
        col.addView(mkButton("RENDER ANOTHER").apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
            setOnClickListener { showPickScreen() }
        })

        showScreen(android.widget.ScrollView(this).apply { addView(col) })
    }
}
