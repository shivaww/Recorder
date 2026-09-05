package com.brollrender.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
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
        private const val REQ_NOTIF = 2
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
    private var bitRate = 16_000_000 // Mbps x 1e6 - user-adjustable on preview

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

    // BATCH (overnight) queue: staged block files live in internal storage
    // (filesDir/blocks); only the block currently rendering is in RAM.
    private val blocks = mutableListOf<Block>()
    private var blockSeq = 0
    private var batchEnhance = false

    @Volatile
    private var batchRunning = false

    @Volatile
    private var batchCancel = false
    private var postNotifAsked = false

    // SAF picker target: false = normal flow (CHOOSE HTML -> PREVIEW),
    // true = overnight queue (+ ADD BLOCK stages a block).
    private var queuePick = false

    // DONE state
    private var doneUri: Uri? = null
    private var doneName = ""

    private lateinit var root: FrameLayout
    private lateinit var engine: RenderEngine
    /** Foreground UI only. The renderer owns a separate, persistent child at
     * index 0; never remove it during a screen transition. */
    private var currentScreen: View? = null

    // REMOTE (Kaggle) state
    private lateinit var securePrefs: remote.SecurePrefs
    private lateinit var jobStore: remote.JobStore
    private val remoteJobs = mutableListOf<remote.JobStore.JobMeta>()
    @Volatile private var remotePolling = false
    private var remotePollThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = RenderEngine(this)
        root = FrameLayout(this)
        // The engine's WebView renders GHOST-VISIBLE at the BOTTOM of this
        // root: attached + composited (required for the GPU draw path),
        // invisible under the UI at 2% alpha, touches swallowed by the
        // engine. UI screens stack above it and own all interaction.
        engine.attachRoot(root)
        root.setBackgroundColor(VOID)
        setContentView(root)
        // Purge block files orphaned by a previous session.
        blockDir().deleteRecursively()
        // Init remote render state
        securePrefs = remote.SecurePrefs(this)
        jobStore = remote.JobStore(this)
        remoteJobs.addAll(jobStore.loadAll())
        showPickScreen()
    }

    // Section 7.15: pause the loop while backgrounded mid-render; keep the
    // screen on only while rendering in the foreground.
    override fun onPause() {
        super.onPause()
        // Overnight batch: screen off must NOT pause the loop - the FGS +
        // wake lock keep the CPU running (that is the whole point).
        if (rendering && !batchRunning) renderPaused = true
        if (!batchRunning) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopRemotePolling()
    }

    override fun onResume() {
        super.onResume()
        renderPaused = false
        if (rendering && !batchRunning) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        startRemotePolling()
    }

    override fun onDestroy() {
        cancelRequested = true
        batchCancel = true
        renderThread?.join(1500)
        engine.destroy() // web.destroy() ONLY here (pitfall 7.9)
        super.onDestroy()
    }

    private fun showScreen(v: View) {
        // Do NOT call root.removeAllViews(): the renderer's ghost-visible
        // WebView is a sibling beneath this UI. Removing it detaches
        // Chromium from the window, which prevents compositor commits and
        // makes GPU capture return stale/blank frames (or time out waiting
        // for postVisualStateCallback).
        currentScreen?.let { root.removeView(it) }
        root.addView(
            v,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        currentScreen = v
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

    // ========== PICK SCREEN (single render) + OVERNIGHT QUEUE ==========

    /** One staged overnight-batch block: file in internal storage + label. */
    private data class Block(val file: File, val name: String)

    private fun blockDir(): File {
        val d = File(filesDir, "blocks")
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** Monotonic file names: removing a queued block never collides. */
    private fun nextBlockFile(): File {
        blockSeq += 1
        return File(blockDir(), "b$blockSeq.html")
    }

    private fun showPickScreen() {
        val pad = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        col.addView(monoTv("BROLLRENDER", 26, AMBER, true))
        col.addView(monoTv("HTML -> exact 16:9 MP4 / offscreen render", 12, TXT2))
        col.addView(spacer(dp(28)))
        col.addView(mkButton("CHOOSE HTML", filled = true).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
            )
            setOnClickListener {
                queuePick = false
                launchPicker()
            }
        })
        col.addView(spacer(dp(8)))
        col.addView(mkButton("PASTE HTML").apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
            setOnClickListener { pasteHtml() }
        })
        if (htmlFile != null) {
            col.addView(spacer(dp(12)))
            col.addView(monoTv("last: $htmlName", 12, TXT2))
        }
        col.addView(spacer(dp(16)))
        // Overnight batch is a SEPARATE feature: its own queue screen, never
        // in the way of the normal PICK -> PREVIEW -> RENDER -> DONE flow.
        col.addView(mkButton("OVERNIGHT QUEUE").apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
            setOnClickListener { showQueueScreen() }
        })
        col.addView(spacer(dp(8)))
        col.addView(mkButton("RENDER VIA KAGGLE").apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
            setOnClickListener { showKaggleSetupScreen() }
        })
        col.addView(spacer(dp(34)))

        val resRow = toggleRow(
            col, "RESOLUTION",
            listOf(
                "4K" to { resW = 3840; resH = 2160; bitRate = 40_000_000 },
                "1080p" to { resW = 1920; resH = 1080; bitRate = 16_000_000 },
                "720p" to { resW = 1280; resH = 720; bitRate = 8_000_000 },
                "480p" to { resW = 854; resH = 480; bitRate = 8_000_000 }
            ),
            if (resW == 3840) 0 else if (resW == 1920) 1 else if (resW == 1280) 2 else 3
        )
        val fpsRow = toggleRow(
            col, "FPS",
            listOf(
                "30" to { fps = 30 },
                "24" to { fps = 24 },
                "60" to { fps = 60 }
            ),
            if (fps == 30) 0 else if (fps == 24) 1 else 2
        )
        // MODE is a preset: selecting it snaps the resolution + FPS rows too.
        toggleRow(
            col, "MODE",
            listOf(
                "FINAL" to {
                    resRow.select(0); resW = 1920; resH = 1080
                    fpsRow.select(0); fps = 30
                    bitRate = 16_000_000
                },
                "DRAFT" to {
                    resRow.select(1); resW = 1280; resH = 720
                    fpsRow.select(1); fps = 24
                    bitRate = 8_000_000
                }
            ),
            if (resW == 1920 && fps == 30) 0 else 1
        )
        prepared?.let { p ->
            col.addView(spacer(dp(14)))
            col.addView(monoTv("DURATION  auto ${p.durationMs / 1000} s - editable on preview", 12, TXT2))
        }
        showScreen(android.widget.ScrollView(this).apply { addView(col) })
    }

    // ===================== OVERNIGHT QUEUE SCREEN =====================

    /** Queue builder for the overnight batch - reached from home. */
    private fun showQueueScreen() {
        val pad = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        col.addView(monoTv("OVERNIGHT QUEUE", 22, AMBER, true))
        col.addView(monoTv("blocks render one at a time · screen off", 12, TXT2))
        col.addView(spacer(dp(16)))

        // ---- queue: add block 1, then + the next, until N ----
        col.addView(
            monoTv(
                "QUEUE · ${blocks.size} BLOCK" + if (blocks.size == 1) "" else "S",
                11, TXT2
            )
        )
        blocks.forEachIndexed { i, b ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                monoTv("B${i + 1}  ${b.name}", 12, TXT).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
            )
            row.addView(
                mkButton("X").apply {
                    setOnClickListener {
                        if (!batchRunning) {
                            blocks.removeAt(i)
                            showQueueScreen()
                        }
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(36))
                }
            )
            col.addView(row)
            col.addView(spacer(dp(4)))
        }
        col.addView(spacer(dp(8)))
        col.addView(mkButton("+ ADD BLOCK (FILE)", filled = true).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
            )
            setOnClickListener {
                queuePick = true
                launchPicker()
            }
        })
        col.addView(spacer(dp(8)))
        col.addView(mkButton("+ PASTE BLOCK").apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
            )
            setOnClickListener { pasteBlock() }
        })
        if (blocks.isNotEmpty()) {
            col.addView(spacer(dp(4)))
            col.addView(mkButton("CLEAR QUEUE").apply {
                setOnClickListener {
                    if (!batchRunning) {
                        blocks.clear()
                        blockDir().deleteRecursively()
                        blockSeq = 0
                        showQueueScreen()
                    }
                }
            })
        }
        col.addView(spacer(dp(24)))

        val qResRow = toggleRow(
            col, "RESOLUTION",
            listOf(
                "4K" to { resW = 3840; resH = 2160; bitRate = 40_000_000 },
                "1080p" to { resW = 1920; resH = 1080; bitRate = 16_000_000 },
                "720p" to { resW = 1280; resH = 720; bitRate = 8_000_000 },
                "480p" to { resW = 854; resH = 480; bitRate = 8_000_000 }
            ),
            if (resW == 3840) 0 else if (resW == 1920) 1 else if (resW == 1280) 2 else 3
        )
        val qFpsRow = toggleRow(
            col, "FPS",
            listOf(
                "30" to { fps = 30 },
                "24" to { fps = 24 },
                "60" to { fps = 60 }
            ),
            if (fps == 30) 0 else if (fps == 24) 1 else 2
        )
        toggleRow(
            col, "MODE",
            listOf(
                "FINAL" to {
                    qResRow.select(0); resW = 1920; resH = 1080
                    qFpsRow.select(0); fps = 30
                    bitRate = 16_000_000
                },
                "DRAFT" to {
                    qResRow.select(1); resW = 1280; resH = 720
                    qFpsRow.select(1); fps = 24
                    bitRate = 8_000_000
                }
            ),
            if (resW == 1920 && fps == 30) 0 else 1
        )
        // ENHANCE applies to every block in the batch (one global setting).
        toggleRow(
            col, "ENHANCE",
            listOf(
                "OFF" to { batchEnhance = false },
                "ON" to { batchEnhance = true }
            ),
            if (batchEnhance) 1 else 0
        )

        col.addView(spacer(dp(20)))
        col.addView(
            mkButton(
                if (blocks.isEmpty()) "START OVERNIGHT" else "START OVERNIGHT (${blocks.size})",
                filled = true
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
                )
                setOnClickListener { startOvernight() }
            }
        )
        col.addView(spacer(dp(8)))
        col.addView(mkButton("BACK").apply { setOnClickListener { showPickScreen() } })

        showScreen(android.widget.ScrollView(this).apply { addView(col) })
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
        // Transparent: keep the ghost WebView's on-screen composite alive.
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

    /** Paste full HTML from clipboard (any length, Termux-style) - the
     *  normal single-render flow: prepare + PREVIEW screen. */
    private fun pasteHtml() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        if (text.isBlank()) {
            showErrorScreen("clipboard is empty - copy the full HTML first")
            return
        }
        val trimmed = text.trim()
        if (!trimmed.startsWith("<") && !trimmed.contains("<html", ignoreCase = true)
            && !trimmed.contains("<!doctype", ignoreCase = true)) {
            showErrorScreen("clipboard doesn't look like HTML - copy the full file content")
            return
        }
        showBusy("WRITING PASTED HTML")
        Thread {
            try {
                val f = File(cacheDir, "pasted.html")
                f.writeText(text)
                htmlFile = f
                htmlName = "pasted (${text.length / 1024}KB)"
                runOnUiThread { showBusy("ANALYZING PAGE") }
                val result = engine.prepare(f, resW, resH)
                runOnUiThread {
                    when (result) {
                        is RenderEngine.PrepareResult.Ok -> showPreviewScreen(result)
                        is RenderEngine.PrepareResult.Fail -> showErrorScreen(result.message)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { showErrorScreen("paste failed: ${e.message}") }
            }
        }.start()
    }

    /** Paste the next OVERNIGHT QUEUE block into internal storage. */
    private fun pasteBlock() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        if (text.isBlank()) {
            showErrorScreen("clipboard is empty - copy the full HTML first")
            return
        }
        val trimmed = text.trim()
        if (!trimmed.startsWith("<") && !trimmed.contains("<html", ignoreCase = true)
            && !trimmed.contains("<!doctype", ignoreCase = true)) {
            showErrorScreen("clipboard doesn't look like HTML - copy the full file content")
            return
        }
        showBusy("STAGING BLOCK ${blocks.size + 1}")
        Thread {
            try {
                val dest = nextBlockFile()
                dest.writeText(text)
                runOnUiThread {
                    blocks.add(Block(dest, "pasted (${text.length / 1024}KB)"))
                    showQueueScreen()
                }
            } catch (e: Exception) {
                runOnUiThread { showErrorScreen("paste failed: ${e.message}") }
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (queuePick) {
            // Overnight queue: stage the picked file as the next block.
            showBusy("STAGING BLOCK ${blocks.size + 1}")
            Thread {
                try {
                    val dest = nextBlockFile()
                    contentResolver.openInputStream(uri)?.use { ins ->
                        FileOutputStream(dest).use { outs -> ins.copyTo(outs) }
                    } ?: throw RuntimeException("cannot open selected file")
                    runOnUiThread {
                        blocks.add(Block(dest, queryName(uri)))
                        showQueueScreen()
                    }
                } catch (e: Exception) {
                    runOnUiThread { showErrorScreen("add block failed: ${e.message}") }
                }
            }.start()
            return
        }
        // Normal single-render flow: PICK -> PREVIEW.
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
        val validationHint =
            "check: the HTML needs a .fit (16:9) element containing .stage, " +
                "CSS keyframe animations, and webfonts that load"
        val renderHint =
            "renderer timeout: retry the render. This is not an HTML validation error."
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
                if (message.startsWith("render failed:")) renderHint else validationHint,
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

        // FRAMING: AUTO = detected DOM frame (spec section 4); MANUAL =
        // pinch/drag zoom+pan in a ZoomView whose brackets are the output
        // frame (WYSIWYG). Defaults to MANUAL when detection failed, so
        // arbitrary HTML still records.
        var manual = p.manualRecommended
        // Seed with the engine's auto-fit suggestion (non-conforming pages):
        // preview opens already fitted; user can still pinch/crop from here.
        var zoom: ZoomTransform? = p.suggestedZoom
        val frameHolder = LinearLayout(this)
        val statusLock = monoTv("FRAME ${resW}x${resH}", 13, AMBER, true)

        // Manual-framing controls (visible only in MANUAL): zoom readout +
        // editor-style preset row. FULL = whole page (scale 1, no pan);
        // CENTER = center current zoom; CROP = corner-handle crop over the
        // base page (thirds grid, dimmed outside); APPLY commits the crop.
        val zoomLabel = monoTv("zoom 100% · pan +0.0% +0.0%", 11, TXT2)
        var zoomView: ZoomView? = null
        var enhanceOn = false
        var sfxOn = p.sfxEvents.isNotEmpty()
        var textScale = 1.0f
        var sfxVol: String = p.sfxLoudness ?: "normal"
        val btnFull = mkButton("FULL")
        val btnCenter = mkButton("CENTER")
        val btnCrop = mkButton("CROP")
        val btnApply = mkButton("APPLY", filled = true)
        btnApply.isEnabled = false
        btnFull.setOnClickListener { zoomView?.resetFull() }
        btnCenter.setOnClickListener { zoomView?.centerContent() }
        btnCrop.setOnClickListener { zoomView?.enterCrop() }
        btnApply.setOnClickListener { zoomView?.applyCrop() }
        val manualCtrls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(btnFull, btnCenter, btnCrop, btnApply).forEachIndexed { i, b ->
            presetRow.addView(
                b,
                LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                    if (i > 0) marginStart = dp(4)
                }
            )
        }
        manualCtrls.addView(zoomLabel)
        manualCtrls.addView(spacer(dp(4)))
        manualCtrls.addView(presetRow)

        fun rebuildFrame() {
            frameHolder.removeAllViews()
            if (manual) {
                val zv = ZoomView(this, p.thumb, zoom)
                zv.onTransform = { t ->
                    zoom = t
                    zoomLabel.text = String.format(
                        Locale.US,
                        "zoom %.0f%% · pan %+.1f%% %+.1f%%",
                        t.scale * 100f, t.panNx * 100f, t.panNy * 100f
                    )
                }
                zv.onModeChange = { editing ->
                    btnCrop.isEnabled = !editing
                    btnApply.isEnabled = editing
                }
                zv.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                zoomView = zv
                frameHolder.addView(zv)
                zoom?.let {
                    zoomLabel.text = String.format(
                        Locale.US,
                        "zoom %.0f%% · pan %+.1f%% %+.1f%%",
                        it.scale * 100f, it.panNx * 100f, it.panNy * 100f
                    )
                }
                statusLock.text = if (p.suggestedZoom != null) {
                    "FRAME ${resW}x${resH} · MANUAL · AUTO-FITTED · pinch/drag or CROP"
                } else {
                    "FRAME ${resW}x${resH} · MANUAL · pinch/drag or CROP · dbl-tap reset"
                }
                manualCtrls.visibility = View.VISIBLE
            } else {
                zoomView = null
                frameHolder.addView(ImageView(this).apply {
                    setImageBitmap(drawDetectionOverlay(p))
                    adjustViewBounds = true
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                })
                statusLock.text =
                    "FRAME ${resW}x${resH} · LOCKED · CORNERS ±${p.cornerDeviationPx}px"
                manualCtrls.visibility = View.GONE
            }
        }

        col.addView(frameHolder)
        col.addView(spacer(dp(6)))
        col.addView(statusLock)
        p.note?.let { col.addView(monoTv("detection: $it", 11, AMBER)) }
        col.addView(spacer(dp(8)))
        col.addView(monoTv("animations locked: ${p.animCount}", 12, TXT2))
        if (p.isBrollJs) {
            col.addView(monoTv("JS: scrub-safe __broll active (canvas/WebGL)", 12, TXT2))
        }
        p.brollWarning?.let { col.addView(monoTv("JS warning: $it", 12, AMBER)) }
        if (p.sfxEvents.isNotEmpty()) {
            col.addView(monoTv("sfx: ${p.sfxEvents.size} events declared", 12, TXT2))
        }
        when {
            p.fontsWarning != null -> col.addView(monoTv("fonts: ${p.fontsWarning}", 12, AMBER))
            p.fontsLoaded -> col.addView(monoTv("fonts: OK (${p.fontsDetail ?: "anton + plex"})", 12, TXT2))
            else -> col.addView(monoTv("fonts: TIMEOUT WARNING - look may differ", 12, AMBER))
        }
        col.addView(monoTv("detected timeline: ${p.durationMs / 1000.0} s", 12, TXT2))
        col.addView(spacer(dp(10)))
        col.addView(manualCtrls) // visible only in MANUAL framing

        if (p.cornerDeviationPx >= 0) {
            col.addView(monoTv("FRAMING", 11, TXT2))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val bAuto = mkButton("AUTO")
            val bManual = mkButton("MANUAL")
            fun syncToggle() {
                styleButton(bAuto, !manual)
                styleButton(bManual, manual)
            }
            bAuto.setOnClickListener {
                manual = false
                zoom = null
                syncToggle()
                rebuildFrame()
            }
            bManual.setOnClickListener {
                manual = true
                syncToggle()
                rebuildFrame()
            }
            syncToggle()
            row.addView(bAuto, LinearLayout.LayoutParams(0, dp(40), 1f))
            row.addView(
                bManual,
                LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(6) }
            )
            col.addView(row)
            col.addView(spacer(dp(10)))
        }

        // FPS + bitrate (peak customization; the lossless speed levers are
        // fps and resolution - less work per output second, same per-frame
        // quality). Bitrate trades file size only, above ~8M for 720p /
        // ~16M for 1080p it is visually transparent.
        toggleRow(
            col, "FPS",
            listOf("24" to { fps = 24 }, "30" to { fps = 30 }, "60" to { fps = 60 }),
            if (fps == 24) 0 else if (fps == 30) 1 else 2
        )
        toggleRow(
            col, "BITRATE",
            listOf(
                "8M" to { bitRate = 8_000_000 },
                "16M" to { bitRate = 16_000_000 },
                "24M" to { bitRate = 24_000_000 },
                "40M" to { bitRate = 40_000_000 }
            ),
            if (bitRate == 8_000_000) 0 else if (bitRate == 16_000_000) 1 else if (bitRate == 24_000_000) 2 else 3
        )

        // SFX: synthesized sound baked into the MP4's audio track when
        // the page declares events (data-only #sfx manifest - PROMPT_SPEC).
        if (p.sfxEvents.isNotEmpty()) {
            toggleRow(
                col, "SFX",
                listOf(
                    "OFF" to { sfxOn = false },
                    "ON" to { sfxOn = true }
                ),
                if (sfxOn) 1 else 0
            )
            // Volume row starts at the page's declared loudness; the user's
            // final choice wins at render time.
            toggleRow(
                col, "SFX VOLUME",
                listOf(
                    "QUIET" to { sfxVol = "low" },
                    "NORMAL" to { sfxVol = "normal" },
                    "LOUD" to { sfxVol = "high" }
                ),
                if (sfxVol == "low") 0 else if (sfxVol == "high") 2 else 1
            )
        }
        toggleRow(
            col, "TEXT",
            listOf(
                "COMPACT" to { textScale = 0.9f },
                "STANDARD" to { textScale = 1.0f },
                "LARGE" to { textScale = 1.35f }
            ),
            1
        )

        // ENHANCE: honest naming - a color grade (contrast ~1.12 around
        // mid-gray + saturation 1.18) applied as a root CSS filter by
        // Chromium's GPU compositor - identical math to the old native
        // ColorMatrix blit, still not an AI model (zero-dependency app,
        // nothing bundled, nothing uploaded). Pops soft raster content;
        // frame0.png is exported with the same grade so QC still matches
        // the video's first frame.
        toggleRow(
            col, "ENHANCE",
            listOf(
                "OFF" to { enhanceOn = false },
                "ON" to { enhanceOn = true }
            ),
            0
        )

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
                val zv = if (manual) (zoom ?: ZoomTransform(1f, 0f, 0f)) else null
                startRender(
                    p, secs.coerceIn(5, 600), zv, enhanceOn, sfxOn,
                    textScale, sfxVol
                )
            }
        })
        col.addView(spacer(dp(8)))
        col.addView(mkButton("BACK").apply { setOnClickListener { showPickScreen() } })

        rebuildFrame()
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

    private fun startRender(
        p: RenderEngine.PrepareResult.Ok,
        durationSec: Int,
        zoom: ZoomTransform?,
        enhance: Boolean,
        sfxOn: Boolean,
        textScale: Float,
        sfxVol: String
    ) {
        cancelRequested = false
        rendering = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showRenderScreen("RENDERING", durationSec)
        renderThread = Thread {
            val outcome = runRenderJob(p, durationSec, zoom, enhance, sfxOn, textScale, sfxVol)
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
    private lateinit var renderStats: TextView
    private var tempOutput: File? = null

    private fun showRenderScreen(title: String, durationSec: Int) {
        val pad = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER_VERTICAL
        }
        // Transparent on purpose (occlusion-cull gamble reverted): the
        // ghost WebView's ON-SCREEN composite behind this screen is
        // plausibly what feeds the GL functor's content - the GPU draw
        // path reads the composited frame. An opaque screen would let the
        // RenderThread cull it. The e2e gate now decides GPU vs CPU; this
        // screen must not influence that decision.
        col.addView(monoTv(title, 16, AMBER, true))
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
        col.addView(spacer(dp(4)))
        // Realtime pipeline readout (~1 Hz from the engine): RAM, GPU busy
        // % (when the SoC exposes it), and which pipeline is live.
        renderStats = monoTv("MEM -- MB · measuring...", 11, TXT2)
        col.addView(renderStats)
        col.addView(spacer(dp(24)))
        col.addView(mkButton("CANCEL").apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
            )
            setOnClickListener {
                cancelRequested = true
                batchCancel = true
                isEnabled = false
                text = "CANCELLING..."
                renderStatus.text = "aborting - releasing encoder, deleting partial file"
            }
        })
        showScreen(col)
    }

    private fun updateRenderStats(s: String) {
        if (::renderStats.isInitialized) renderStats.text = s
    }

    private fun updateRenderProgress(f: Int, total: Int, rateFps: Double, etaSec: Long) {
        if (!::renderBar.isInitialized) return
        if (f == -1) { renderStatus.text = "AUDIO: mixing events..."; return }
        if (f == -2) { renderStatus.text = "AUDIO: encoding (aac)..."; return }
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
        durationSec: Int,
        zoom: ZoomTransform?,
        enhance: Boolean,
        sfxOn: Boolean,
        textScale: Float,
        sfxVol: String
    ): RenderEngine.RenderOutcome {
        val total = durationSec * fps
        // Section 6 baseline (16 Mbps final / 8 Mbps draft) is now the default
        // of a user-adjustable field set on the preview screen.
        val out = File(cacheDir, "render_tmp.mp4")
        tempOutput = out
        return engine.render(
            frameRect = p.frameRect,
            fps = fps,
            totalFrames = total,
            outputFile = out,
            bitRate = bitRate,
            zoom = zoom,
            enhance = enhance,
            sfxEvents = if (sfxOn) p.sfxEvents else emptyList(),
            sfxLoudness = sfxVol,
            ambienceType = if (sfxOn) p.ambienceType else null,
            ambienceGain = p.ambienceGain,
            textScale = textScale,
            onStats = { s -> runOnUiThread { updateRenderStats(s) } },
            onProgress = { f, t, rate, eta ->
                runOnUiThread { updateRenderProgress(f, t, rate, eta) }
            },
            isPaused = {
                // Section 7.15: backgrounded mid-render = graceful pause at
                // the frame boundary. Now a NON-BLOCKING flag the engine's
                // UI-side frame chain checks before issuing the next frame;
                // a blocking wait here would let the surface buffer queue
                // fill and ANR the app while backgrounded.
                renderPaused
            },
            isCancelled = { cancelRequested }
        )
    }

    // ===================== OVERNIGHT BATCH =====================

    /**
     * Start the queue. The FGS + wake lock keep the CPU alive with the
     * screen off; blocks render ONE AT A TIME (only the current block is
     * in the WebView/RAM); each finished MP4 is exported + verified before
     * the next block loads. After the last block: summary notification,
     * service stopped, app closes itself.
     */
    private fun startOvernight() {
        if (blocks.isEmpty() || batchRunning) return
        if (Build.VERSION.SDK_INT >= 33 && !postNotifAsked) {
            // Progress is visible in the notification while the screen is
            // off. Ask once; onRequestPermissionsResult re-enters here.
            postNotifAsked = true
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIF
            )
            return
        }
        batchRunning = true
        batchCancel = false
        cancelRequested = false
        rendering = true
        RenderService.ensureChannel(this)
        startForegroundService(Intent(this, RenderService::class.java))
        val queue = blocks.toList()
        renderThread = Thread {
            val done = mutableListOf<String>()
            val failed = mutableListOf<String>()
            for ((i, b) in queue.withIndex()) {
                if (batchCancel) break
                notifyBatch("Overnight render", "block ${i + 1}/${queue.size} · preparing")
                runOnUiThread { showBusy("PREPARING BLOCK ${i + 1}/${queue.size}") }
                val prep = try {
                    engine.prepare(b.file, resW, resH)
                } catch (e: Exception) {
                    RenderEngine.PrepareResult.Fail("prepare threw: ${e.message}")
                }
                val ok = prep as? RenderEngine.PrepareResult.Ok
                if (ok == null) {
                    failed.add(
                        "B${i + 1} ${b.name}: " +
                            (prep as? RenderEngine.PrepareResult.Fail)?.message.orEmpty()
                    )
                    continue
                }
                val secs = ((ok.durationMs + 999) / 1000).coerceIn(5L, 600L).toInt()
                notifyBatch(
                    "Overnight render",
                    "block ${i + 1}/${queue.size} · ${resW}x${resH} @ ${fps}fps · ${secs}s"
                )
                runOnUiThread {
                    showRenderScreen("RENDERING BLOCK ${i + 1}/${queue.size}", secs)
                }
                val out = File(cacheDir, "batch_tmp.mp4")
                val outcome = engine.render(
                    frameRect = ok.frameRect,
                    fps = fps,
                    totalFrames = secs * fps,
                    outputFile = out,
                    bitRate = bitRate,
                    zoom = ok.suggestedZoom,
                    enhance = batchEnhance,
                    sfxEvents = ok.sfxEvents,
                    sfxLoudness = ok.sfxLoudness ?: "normal",
                    ambienceType = ok.ambienceType,
                    ambienceGain = ok.ambienceGain,
                    textScale = 1f,
                    allowGpu = false, // overnight: screen off = no compositor
                    onStats = { s -> runOnUiThread { updateRenderStats(s) } },
                    onProgress = { f, t, rate, eta ->
                        runOnUiThread { updateRenderProgress(f, t, rate, eta) }
                    },
                    isCancelled = { batchCancel }
                )
                when (outcome) {
                    is RenderEngine.RenderOutcome.Completed -> {
                        val line = exportAndVerify(outcome.firstFrame, out, i + 1)
                        if (line.startsWith("OK")) done.add(line) else failed.add(line)
                    }
                    RenderEngine.RenderOutcome.Cancelled -> break
                    is RenderEngine.RenderOutcome.Failed ->
                        failed.add("B${i + 1} ${b.name}: ${outcome.message}")
                }
            }
            val wasCancelled = batchCancel
            batchRunning = false
            rendering = false
            stopService(Intent(this, RenderService::class.java))
            runOnUiThread {
                if (wasCancelled) {
                    cancelRequested = false
                    batchCancel = false
                    showQueueScreen()
                } else {
                    batchSummary(done, failed)
                }
            }
        }
        renderThread?.start()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) startOvernight() // proceed either way
    }

    /**
     * Export one rendered block to MediaStore and verify it by reading the
     * file back (worker thread). Returns "OK ..." or "FAIL ..." lines for
     * the batch summary.
     */
    private fun exportAndVerify(firstFrame: Bitmap, tempFile: File, blockNo: Int): String {
        return try {
            val uri = exportToMediaStore(firstFrame, tempFile)
                ?: return "FAIL B$blockNo: export failed"
            var line = "OK B$blockNo"
            try {
                val r = MediaMetadataRetriever()
                r.setDataSource(this, uri)
                val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                val vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                r.release()
                line += " ${vw}x${vh}" +
                    if (vw == resW && vh == resH) " VERIFIED" else " MISMATCH"
                line += " " + String.format(Locale.US, "%.1fs", durMs / 1000.0)
            } catch (e: Exception) {
                line += " (verify failed)"
            }
            line
        } catch (e: Exception) {
            "FAIL B$blockNo: ${e.message}"
        }
    }

    /** Mirror batch progress into the FGS notification (screen-off view). */
    private fun notifyBatch(title: String, text: String) {
        try {
            RenderService.ensureChannel(this)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(
                RenderService.NOTE_ID,
                Notification.Builder(this, RenderService.CH_ID)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build()
            )
        } catch (_: Exception) {
        }
    }

    /**
     * Morning report: one summary notification (tap to dismiss), then the
     * app closes itself - videos are saved and verified.
     */
    private fun batchSummary(done: List<String>, failed: List<String>) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try {
            RenderService.ensureChannel(this)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val lines = (done + failed).ifEmpty { listOf("no blocks") }
            nm.notify(
                RenderService.NOTE_ID + 1,
                Notification.Builder(this, RenderService.CH_ID)
                    .setContentTitle("Overnight batch: ${done.size} OK · ${failed.size} failed")
                    .setContentText(lines.joinToString("; "))
                    .setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n")))
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (_: Exception) {
        }
        finishAndRemoveTask()
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

    // ===================== KAGGLE SETUP SCREEN =====================

    private fun showKaggleSetupScreen() {
        val pad = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        col.addView(monoTv("KAGGLE SETUP", 22, AMBER, true))
        col.addView(monoTv("1. Go to kaggle.com, create new notebook.\n2. Select 2x T4 in Accelerator.\n3. Run these 3 scripts in 3 separate cells.", 12, TXT2))
        col.addView(spacer(dp(16)))
        
        fun addScriptBlock(title: String, code: String) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(STROKE)
                setPadding(dp(14), dp(14), dp(14), dp(14))
            }
            card.addView(monoTv(title, 14, TXT, true))
            card.addView(spacer(dp(8)))
            val codeView = TextView(this).apply {
                text = code
                textSize = 10f
                typeface = Typeface.MONOSPACE
                setTextColor(TXT2)
                setHorizontallyScrolling(true)
                maxLines = 10
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            card.addView(codeView)
            card.addView(spacer(dp(8)))
            card.addView(mkButton("COPY").apply {
                setOnClickListener {
                    val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("script", code))
                    text = "COPIED!"
                    postDelayed({ text = "COPY" }, 2000)
                }
            })
            col.addView(card)
            col.addView(spacer(dp(16)))
        }

        addScriptBlock("STEP 1: Install Essentials", remote.KaggleScripts.step1)
        addScriptBlock("STEP 2: Download Cloudflared", remote.KaggleScripts.step2)
        addScriptBlock("STEP 3: Launch Server", remote.KaggleScripts.step3)
        
        col.addView(mkButton("CONTINUE TO KAGGLE RENDER", filled = true).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
            setOnClickListener { showRemoteQueueScreen() }
        })
        col.addView(spacer(dp(8)))
        col.addView(mkButton("BACK").apply { setOnClickListener { showPickScreen() } })
        
        showScreen(android.widget.ScrollView(this).apply { addView(col) })
    }

    // ===================== REMOTE QUEUE SCREEN =====================

    private fun showRemoteQueueScreen() {
        val pad = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        col.addView(monoTv("REMOTE QUEUE (KAGGLE)", 22, AMBER, true))
        col.addView(monoTv("queue html blocks - render on server", 12, TXT2))
        col.addView(spacer(dp(16)))

        // --- Connection Settings ---
        col.addView(monoTv("SETTINGS", 11, TXT2))
        val baseUrlInput = EditText(this).apply {
            hint = "Base URL (Cloudflare Tunnel)"
            setText(securePrefs.baseUrl)
            setSingleLine(true)
            setTextColor(TXT)
            setHintTextColor(TXT2)
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        col.addView(baseUrlInput)
        val apiKeyInput = EditText(this).apply {
            hint = "API Key"
            setText(securePrefs.apiKey)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(TXT)
            setHintTextColor(TXT2)
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        col.addView(apiKeyInput)
        col.addView(spacer(dp(8)))
        col.addView(mkButton("SAVE SETTINGS").apply {
            setOnClickListener {
                securePrefs.baseUrl = baseUrlInput.text.toString().trim()
                securePrefs.apiKey = apiKeyInput.text.toString().trim()
                showRemoteQueueScreen()
            }
        })
        col.addView(mkButton("TEST CONNECTION").apply {
            setOnClickListener {
                val url = baseUrlInput.text.toString().trim()
                val key = apiKeyInput.text.toString().trim()
                if (url.isEmpty()) { return@setOnClickListener }
                showBusy("Testing...")
                Thread {
                    val api = remote.RemoteApi(url, key)
                    val (ok, latency) = api.testConnection()
                    runOnUiThread {
                        if (ok) showErrorScreen("Reachable\nLatency: ${latency}ms")
                        else showErrorScreen("Unreachable")
                    }
                }.start()
            }
        })

        col.addView(spacer(dp(20)))
        col.addView(monoTv("QUEUE · ${blocks.size} BLOCK" + if (blocks.size == 1) "" else "S", 11, TXT2))
        
        blocks.forEachIndexed { i, b ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(monoTv("B${i + 1}  ${b.name}", 12, TXT).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(mkButton("X").apply {
                setOnClickListener {
                    blocks.removeAt(i)
                    showRemoteQueueScreen()
                }
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(36))
            })
            col.addView(row)
            col.addView(spacer(dp(4)))
        }
        
        col.addView(spacer(dp(8)))
        col.addView(mkButton("+ ADD BLOCK (FILE)", filled = true).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
            setOnClickListener {
                queuePick = true
                launchPicker()
            }
        })
        col.addView(spacer(dp(8)))
        col.addView(mkButton("+ PASTE BLOCK").apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
            setOnClickListener { pasteBlock() }
        })
        if (blocks.isNotEmpty()) {
            col.addView(spacer(dp(4)))
            col.addView(mkButton("CLEAR QUEUE").apply {
                setOnClickListener {
                    blocks.clear()
                    blockDir().deleteRecursively()
                    blockSeq = 0
                    showRemoteQueueScreen()
                }
            })
        }
        col.addView(spacer(dp(24)))

        val qResRow = toggleRow(
            col, "RESOLUTION",
            listOf(
                "4K" to { resW = 3840; resH = 2160; bitRate = 40_000_000 },
                "1080p" to { resW = 1920; resH = 1080; bitRate = 16_000_000 },
                "720p" to { resW = 1280; resH = 720; bitRate = 8_000_000 },
                "480p" to { resW = 854; resH = 480; bitRate = 8_000_000 }
            ),
            if (resW == 3840) 0 else if (resW == 1920) 1 else if (resW == 1280) 2 else 3
        )
        val qFpsRow = toggleRow(
            col, "FPS",
            listOf(
                "30" to { fps = 30 },
                "24" to { fps = 24 },
                "60" to { fps = 60 }
            ),
            if (fps == 30) 0 else if (fps == 24) 1 else 2
        )
        toggleRow(
            col, "MODE",
            listOf(
                "FINAL" to {
                    qResRow.select(0); resW = 1920; resH = 1080
                    qFpsRow.select(0); fps = 30
                    bitRate = 16_000_000
                },
                "DRAFT" to {
                    qResRow.select(1); resW = 1280; resH = 720
                    qFpsRow.select(1); fps = 24
                    bitRate = 8_000_000
                }
            ),
            if (resW == 1920 && fps == 30) 0 else 1
        )
        toggleRow(
            col, "ENHANCE",
            listOf(
                "OFF" to { batchEnhance = false },
                "ON" to { batchEnhance = true }
            ),
            if (batchEnhance) 1 else 0
        )

        col.addView(spacer(dp(20)))
        col.addView(mkButton("SUBMIT REMOTE JOBS", filled = true).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
            setOnClickListener { startRemoteSubmit() }
        })
        col.addView(spacer(dp(8)))
        col.addView(mkButton("BACK").apply { setOnClickListener { showPickScreen() } })

        showScreen(android.widget.ScrollView(this).apply { addView(col) })
    }

    private fun startRemoteSubmit() {
        if (blocks.isEmpty()) {
            showErrorScreen("No blocks queued")
            return
        }
        val url = securePrefs.baseUrl
        val key = securePrefs.apiKey
        if (url.isEmpty() || key.isEmpty()) {
            showErrorScreen("Missing Base URL or API Key")
            return
        }
        
        showBusy("Submitting jobs...")
        Thread {
            val api = remote.RemoteApi(url, key)
            val resStr = "${resW}x${resH}"
            var successCount = 0
            
            blocks.forEach { block ->
                val jobId = api.submitJob(block.file, fps, resStr, 10, batchEnhance) // duration mocked to 10s for now
                if (jobId != null) {
                    val meta = remote.JobStore.JobMeta(
                        jobId = jobId,
                        fileName = block.name,
                        createdAt = System.currentTimeMillis(),
                        state = "QUEUED",
                        fps = fps,
                        resolution = resStr,
                        duration = 10,
                        enhance = batchEnhance
                    )
                    synchronized(remoteJobs) { remoteJobs.add(meta) }
                    jobStore.saveAll(remoteJobs)
                    successCount++
                }
            }
            
            runOnUiThread {
                if (successCount > 0) {
                    blocks.clear()
                    blockDir().deleteRecursively()
                    blockSeq = 0
                    startRemotePolling()
                    showRemoteJobsScreen()
                } else {
                    showErrorScreen("Failed to submit jobs. Check connection/API key.")
                }
            }
        }.start()
    }

    private fun showRemoteJobsScreen() {
        val pad = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        col.addView(monoTv("REMOTE JOBS", 22, AMBER, true))
        col.addView(spacer(dp(16)))

        synchronized(remoteJobs) {
            if (remoteJobs.isEmpty()) {
                col.addView(monoTv("No active jobs.", 12, TXT2))
            } else {
                remoteJobs.forEachIndexed { idx, job ->
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundColor(STROKE)
                        setPadding(dp(14), dp(14), dp(14), dp(14))
                    }
                    card.addView(monoTv(job.fileName, 13, TXT, true))
                    card.addView(monoTv("ID: ${job.jobId}", 10, TXT2))
                    card.addView(monoTv("State: ${job.state}", 12, AMBER))
                    
                    if (job.state == "FAILED") {
                        card.addView(mkButton("RETRY").apply {
                            setOnClickListener { retryRemoteJob(job.jobId) }
                        })
                    } else if (job.state == "DONE" && !job.downloaded) {
                        val dlBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                            max = 100
                            progress = 0
                        }
                        val dlText = monoTv("", 10, TXT2)
                        card.addView(dlBar)
                        card.addView(dlText)
                        card.addView(mkButton("DOWNLOAD", filled = true).apply {
                            setOnClickListener { startRemoteDownload(idx, dlBar, dlText) }
                        })
                    } else if (job.downloaded) {
                        card.addView(monoTv("Downloaded", 11, AMBER))
                    } else {
                        card.addView(mkButton("CANCEL").apply {
                            setOnClickListener { cancelRemoteJob(idx) }
                        })
                    }
                    col.addView(card)
                    col.addView(spacer(dp(8)))
                }
            }
        }
        
        col.addView(spacer(dp(20)))
        col.addView(mkButton("BACK").apply { setOnClickListener { showPickScreen() } })
        showScreen(android.widget.ScrollView(this).apply { addView(col) })
    }

    private fun startRemoteDownload(idx: Int, bar: ProgressBar, txt: TextView) {
        val job = synchronized(remoteJobs) { remoteJobs.getOrNull(idx) } ?: return
        showBusy("Starting download...")
        Thread {
            val url = securePrefs.baseUrl
            val key = securePrefs.apiKey
            val api = remote.RemoteApi(url, key)
            
            val tempFile = File(cacheDir, "broll_${job.jobId}.mp4")
            val ok = api.downloadFile(job.jobId, tempFile) { pct, speedKbps ->
                runOnUiThread {
                    if (pct >= 0) {
                        bar.progress = pct
                        txt.text = "$pct% - ${String.format("%.1f", speedKbps)} KB/s"
                    } else {
                        txt.text = "${String.format("%.1f", speedKbps)} KB/s"
                    }
                }
            }
            
            if (!ok) {
                runOnUiThread { showErrorScreen("Download failed for ${job.fileName}") }
                return@Thread
            }
            
            // Export to MediaStore
            val uri = exportTempToMediaStore(tempFile, job.fileName)
            tempFile.delete()
            if (uri == null) {
                runOnUiThread { showErrorScreen("Failed to save ${job.fileName} to MediaStore") }
                return@Thread
            }
            
            // Local verification
            var verified = false
            try {
                val r = MediaMetadataRetriever()
                r.setDataSource(this, uri)
                val vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                r.release()
                verified = (vw > 0 && vh > 0)
            } catch (_: Exception) {}
            
            if (verified) {
                api.cancelJob(job.jobId) // DELETE to clean up server
                synchronized(remoteJobs) {
                    remoteJobs[idx].downloaded = true
                    remoteJobs[idx].localUri = uri.toString()
                }
                jobStore.saveAll(remoteJobs)
                runOnUiThread { showRemoteJobsScreen() }
            } else {
                runOnUiThread { showErrorScreen("Verification failed for ${job.fileName}") }
            }
        }.start()
    }

    private fun exportTempToMediaStore(tempFile: File, name: String): Uri? {
        val stamp = System.currentTimeMillis()
        val displayName = "BrollRender_${stamp}.mp4"
        val vValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/BrollRender")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, vValues) ?: return null
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                tempFile.inputStream().use { it.copyTo(out) }
            }
            vValues.clear()
            vValues.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(uri, vValues, null, null)
            return uri
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            return null
        }
    }

    private fun cancelRemoteJob(idx: Int) {
        val job = synchronized(remoteJobs) { remoteJobs.getOrNull(idx) } ?: return
        showBusy("Cancelling...")
        Thread {
            val api = remote.RemoteApi(securePrefs.baseUrl, securePrefs.apiKey)
            api.cancelJob(job.jobId)
            synchronized(remoteJobs) { 
                remoteJobs[idx].state = "CANCELLED"
            }
            jobStore.saveAll(remoteJobs)
            runOnUiThread { showRemoteJobsScreen() }
        }.start()
    }

    private fun retryRemoteJob(jobId: String) {
        // Basic retry: just reset state to QUEUED and let polling pick it up.
        synchronized(remoteJobs) {
            val idx = remoteJobs.indexOfFirst { it.jobId == jobId }
            if (idx >= 0) remoteJobs[idx].state = "QUEUED"
        }
        jobStore.saveAll(remoteJobs)
        startRemotePolling()
        showRemoteJobsScreen()
    }
    private fun startRemotePolling() {
        if (remotePolling) return
        remotePolling = true
        remotePollThread = Thread {
            while (remotePolling) {
                try {
                    val url = securePrefs.baseUrl
                    val key = securePrefs.apiKey
                    if (url.isNotEmpty() && key.isNotEmpty()) {
                        val api = remote.RemoteApi(url, key)
                        var changed = false
                        val jobsCopy = synchronized(remoteJobs) { remoteJobs.toList() }
                        
                        for (job in jobsCopy) {
                            if (job.state == "DONE" || job.state == "FAILED" || job.state == "CANCELLED") continue
                            val status = api.getStatus(job.jobId)
                            if (status != null && status.state != job.state) {
                                synchronized(remoteJobs) {
                                    val idx = remoteJobs.indexOfFirst { it.jobId == job.jobId }
                                    if (idx >= 0) remoteJobs[idx].state = status.state
                                }
                                changed = true
                            }
                            Thread.sleep(2000) // 1-2s for RENDERING/ENCODING
                        }
                        if (changed) {
                            jobStore.saveAll(remoteJobs)
                            runOnUiThread { if (currentScreen is android.widget.ScrollView) showRemoteJobsScreen() }
                        }
                    }
                } catch (e: Exception) {
                    // ignore polling errors, retry next loop
                }
                Thread.sleep(3000) // 3-5s for QUEUED
            }
        }.also { it.start() }
    }

    private fun stopRemotePolling() {
        remotePolling = false
        try { remotePollThread?.join(1500) } catch (_: Exception) {}
        remotePollThread = null
    }
