package com.brollrender.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.brollrender.app.remote.SecurePrefs
import com.brollrender.app.remote.VoiceApi
import java.io.File
import kotlin.math.roundToInt

/**
 * Voice Studio — Qwen3-TTS client for the voice server (voice/run.sh on a
 * Kaggle GPU notebook). Mirrors the tested tmp/qwen.html flow: custom voice,
 * voice cloning, voice design; job polling; WAV export to Music/NexonStudio.
 * Programmatic UI, same console aesthetic as MainActivity.
 */
class VoiceActivity : Activity() {

    companion object {
        private const val VOID = Console.VOID
        private const val AMBER = Console.AMBER
        private const val TXT = Console.CHALK
        private const val TXT2 = Console.CHALK_DIM
        private const val RED = Console.ALERT
        private const val TEAL = Console.TEAL
        private const val REQ_REF_AUDIO = 11
        private const val REQ_PERM_MIC = 12

        /** The paragraph the user reads aloud for voice cloning. Sent to the
         *  server as the reference transcript — always English. */
        val CLONE_SCRIPT = "Hello, and welcome. I\u2019m here to help you turn your ideas into clear, natural, and confident conversations. Whether you\u2019re exploring something exciting, explaining a complex thought, or simply enjoying a quiet moment, I\u2019ll keep my voice warm, steady, and easy to follow. Listen to the subtle changes in rhythm, emphasis, and expression as each sentence flows naturally into the next."

        /** One command that boots the voice server on a Kaggle GPU notebook. */
        const val BOOTSTRAP_CMD =
            "!rm -rf /kaggle/working/V && git clone --depth 1 " +
                "https://github.com/shivaww/Recorder.git /kaggle/working/V && " +
                "cd /kaggle/working/V/voice && bash run.sh"
    }

    private lateinit var securePrefs: SecurePrefs
    private var mode = "custom"

    // Input fields — built by showMainScreen, kept for generate time.
    private var baseUrlInput: EditText? = null
    private var textInput: EditText? = null
    private var instructInput: EditText? = null
    private var designInput: EditText? = null
    private var speakerSp: Spinner? = null
    private var customLangSp: Spinner? = null
    private var cloneLangSp: Spinner? = null
    private var designLangSp: Spinner? = null
    // One panel per mode — visibility-toggled so typed input survives switches.
    private var customPanel: LinearLayout? = null
    private var clonePanel: LinearLayout? = null
    private var designPanel: LinearLayout? = null
    private var modeButtons = listOf<Pair<String, Button>>()

    private var generateBtn: Button? = null
    private var statusTv: TextView? = null

    // Output settings (persisted in nexon_voice_settings).
    private var speed = 1.0
    private var loudness = 1.0

    // Speaker preview playback.
    private var previewMp: MediaPlayer? = null

    // Saved clone voices + current selection.
    private lateinit var voiceStore: VoiceStore
    private var savedVoices: MutableList<VoiceStore.Voice> = mutableListOf()
    private var selectedVoice: VoiceStore.Voice? = null
    private var voiceListHost: LinearLayout? = null

    // Microphone recording.
    private var recThread: Thread? = null
    private var recording = false
    @Volatile private var recCancelled = false

    @Volatile private var generating = false
    @Volatile private var voiceCancel = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Console.init(this)
        securePrefs = SecurePrefs(this)
        voiceStore = VoiceStore(this)
        savedVoices = voiceStore.list()
        val settings = getSharedPreferences("nexon_voice_settings", MODE_PRIVATE)
        speed = settings.getFloat("speed", 1.0f).toDouble()
        loudness = settings.getFloat("loudness", 1.0f).toDouble()
        showMainScreen()
    }

    override fun onDestroy() {
        super.onDestroy()
        previewMp?.release()
        previewMp = null
        stopRecorder()
    }

    // ---------------- ui plumbing ----------------

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).roundToInt()

    private fun spacer(h: Int): View =
        View(this).apply { layoutParams = LinearLayout.LayoutParams(1, h) }

    private fun ui(r: () -> Unit) = runOnUiThread(r)

    private fun status(msg: String, color: Int) {
        statusTv?.text = msg
        statusTv?.setTextColor(color)
    }

    private fun doneUi() {
        generating = false
        generateBtn?.isEnabled = true
    }

    /** DATA role: numbers, ids, log lines. */
    private fun monoTv(text: String, sizeSp: Int, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp.toFloat()
            setTextColor(color)
            typeface = Console.data(bold)
        }

    /** DISPLAY role: titles and headers (auto-uppercase). */
    private fun displayTv(text: String, sizeSp: Int, color: Int): TextView =
        TextView(this).apply {
            this.text = text.uppercase()
            textSize = sizeSp.toFloat()
            setTextColor(color)
            typeface = Console.display()
            letterSpacing = 0.05f
        }

    /** BODY role: helper sentences. */
    private fun bodyTv(text: String, sizeSp: Int, color: Int): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp.toFloat()
            setTextColor(color)
            typeface = Console.body()
            setLineSpacing(0f, 1.15f)
        }

    private fun styleButton(b: Button, filled: Boolean, danger: Boolean = false) {
        b.background = Console.buttonBg(this, filled, danger)
        b.setTextColor(if (filled) VOID else if (danger) RED else AMBER)
    }

    private fun mkButton(
        label: String,
        filled: Boolean = false,
        danger: Boolean = false
    ): Button =
        Button(this).apply {
            text = label.uppercase()
            textSize = 13f
            isAllCaps = false
            letterSpacing = 0.06f
            setPadding(dp(14), dp(11), dp(14), dp(11))
            typeface = Console.display()
            styleButton(this, filled, danger)
        }

    /** DATA-role input on a panel surface; multi=true for script-sized text. */
    private fun fieldEt(hint: String, current: String, multi: Boolean = false): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(current)
            if (multi) {
                minLines = 4
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            } else {
                setSingleLine(true)
            }
            setTextColor(TXT)
            setHintTextColor(TXT2)
            textSize = 12f
            typeface = Console.data()
            background = Console.panelBg(context)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

    private fun labelRow(label: String, control: View): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@VoiceActivity).apply {
                text = label.uppercase()
                textSize = 11f
                setTextColor(TXT2)
                typeface = Console.display()
            })
            addView(spacer(dp(6)))
            addView(control)
        }

    private fun spinner(options: List<String>): Spinner {
        val adp = object : ArrayAdapter<String>(
            this@VoiceActivity, android.R.layout.simple_spinner_item, options
        ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = super.getView(position, convertView, parent)
                    (v as? TextView)?.apply {
                        textSize = 12f
                        setTextColor(TXT)
                        typeface = Console.data()
                    }
                    return v
                }
        }
        adp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        return Spinner(this).apply {
            adapter = adp
            background = Console.panelBg(context)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
    }

    // ---------------- main screen ----------------

    private fun showMainScreen() {
        // Preserve typed input across "Generate another" rebuilds.
        val prevScript = textInput?.text?.toString().orEmpty()
        val prevInstruct = instructInput?.text?.toString().orEmpty()
        val prevDesign = designInput?.text?.toString().orEmpty()

        val pad = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        col.addView(displayTv("Voice studio", 26, AMBER))
        col.addView(spacer(dp(6)))
        col.addView(bodyTv(
            "Qwen3-TTS — preset speakers, 3-second voice cloning and natural-language voice design, generated on your Kaggle GPU notebook.",
            13, TXT2))
        col.addView(spacer(dp(16)))
        buildConnectionCard(col)
        col.addView(spacer(dp(14)))
        buildBootstrapCard(col)
        col.addView(spacer(dp(14)))
        buildModeArea(col)
        instructInput?.setText(prevInstruct)
        designInput?.setText(prevDesign)
        col.addView(spacer(dp(14)))
        buildScriptArea(col)
        textInput?.setText(prevScript)
        col.addView(spacer(dp(10)))
        statusTv = bodyTv("", 11, TXT2)
        col.addView(statusTv)
        col.addView(spacer(dp(10)))
        col.addView(mkButton("Back to menu").apply { setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun buildConnectionCard(col: LinearLayout) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Console.panelBg(context)
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }
        card.addView(displayTv("Connection", 13, TXT))
        card.addView(spacer(dp(6)))
        card.addView(bodyTv(
            "Paste the BASE URL the voice server printed in its READY block. It changes every Kaggle session.",
            11, TXT2))
        card.addView(spacer(dp(8)))
        baseUrlInput = fieldEt("Base URL (Cloudflare tunnel)", securePrefs.voiceBaseUrl)
        card.addView(baseUrlInput)
        card.addView(spacer(dp(8)))
        val connStatus = bodyTv("", 11, TXT2)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(mkButton("Save settings", filled = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
            setOnClickListener {
                securePrefs.voiceBaseUrl = baseUrlInput?.text.toString().trim()
                connStatus.text = "Saved on this device."
                connStatus.setTextColor(TEAL)
            }
        })
        row.addView(spacer(dp(8)))
        row.addView(mkButton("Test connection").apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
            setOnClickListener {
                val url = baseUrlInput?.text.toString().trim()
                if (url.isEmpty()) return@setOnClickListener
                connStatus.text = "Testing..."
                connStatus.setTextColor(TXT2)
                Thread {
                    val api = VoiceApi(url)
                    val (ok, latency) = api.testConnection()
                    ui {
                        connStatus.text = if (ok)
                            "Connected in ${latency}ms — voice server is alive."
                        else
                            "Cannot reach the voice server. Check the BASE URL and that the Kaggle cell is still running."
                        connStatus.setTextColor(if (ok) TEAL else RED)
                    }
                }.start()
            }
        })
        card.addView(row)
        card.addView(spacer(dp(6)))
        card.addView(connStatus)
        col.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun buildBootstrapCard(col: LinearLayout) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Console.panelBg(context)
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }
        card.addView(displayTv("Start the voice server (Kaggle)", 12, TXT))
        card.addView(spacer(dp(6)))
        card.addView(bodyTv(
            "New Kaggle notebook → Settings → Accelerator → GPU T4 x2, then paste this into a cell and run. When it prints READY, copy the BASE URL into Connection above.",
            11, TXT2))
        card.addView(spacer(dp(8)))
        card.addView(TextView(this).apply {
            text = BOOTSTRAP_CMD
            textSize = 10f
            typeface = Console.data()
            setTextColor(TXT2)
            setHorizontallyScrolling(true)
            maxLines = 4
        })
        card.addView(spacer(dp(10)))
        card.addView(mkButton("Copy command", filled = true).apply {
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("voice-bootstrap", BOOTSTRAP_CMD))
                text = "COPIED"
                postDelayed({ text = "COPY COMMAND" }, 2000)
            }
        })
        col.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // ---------------- mode panels ----------------

    private fun buildModeArea(col: LinearLayout) {
        col.addView(displayTv("Mode", 13, TXT))
        col.addView(spacer(dp(8)))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val defs = listOf("custom" to "Custom", "clone" to "Clone", "design" to "Design")
        val btns = mutableListOf<Pair<String, Button>>()
        defs.forEach { (m, label) ->
            val b = mkButton(label)
            b.layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
            b.setOnClickListener { selectMode(m) }
            row.addView(b)
            btns.add(m to b)
        }
        modeButtons = btns
        col.addView(row)
        col.addView(spacer(dp(10)))
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        customPanel = buildCustomPanel()
        clonePanel = buildClonePanel()
        designPanel = buildDesignPanel()
        host.addView(customPanel)
        host.addView(clonePanel)
        host.addView(designPanel)
        col.addView(host)
        selectMode(mode)
    }

    private fun selectMode(m: String) {
        mode = m
        modeButtons.forEach { (k, b) -> styleButton(b, filled = (k == m)) }
        customPanel?.visibility = if (m == "custom") View.VISIBLE else View.GONE
        clonePanel?.visibility = if (m == "clone") View.VISIBLE else View.GONE
        designPanel?.visibility = if (m == "design") View.VISIBLE else View.GONE
    }

    private fun buildCustomPanel(): LinearLayout {
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        speakerSp = spinner(
            listOf("Ryan", "Vivian", "Aiden", "Serena", "Uncle_Fu",
                "Ono_Anna", "Sohee", "Eric", "Dylan")
        )
        p.addView(labelRow("Speaker", speakerSp!!))
        val prevBtn = mkButton("Listen to this voice")
        prevBtn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
        prevBtn.setOnClickListener { previewSpeaker() }
        p.addView(prevBtn)
        p.addView(spacer(dp(10)))
        customLangSp = spinner(
            listOf("English", "Chinese", "Japanese", "Korean", "German", "French",
                "Russian", "Portuguese", "Spanish", "Italian", "Auto")
        )
        p.addView(labelRow("Language", customLangSp!!))
        p.addView(spacer(dp(10)))
        instructInput = fieldEt("Instruct — tone / emotion (optional)", "")
        p.addView(instructInput)
        return p
    }

    private fun buildClonePanel(): LinearLayout {
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        voiceListHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        p.addView(voiceListHost!!)
        refreshSavedVoices()
        p.addView(spacer(dp(10)))
        val add = mkButton("Add my voice (record or pick)", filled = true)
        add.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
        add.setOnClickListener { showCloneSetupScreen() }
        p.addView(add)
        p.addView(spacer(dp(10)))
        cloneLangSp = spinner(listOf("English", "Chinese", "Japanese", "Korean", "Auto"))
        p.addView(labelRow("Language", cloneLangSp!!))
        return p
    }

    private fun buildDesignPanel(): LinearLayout {
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        designInput = fieldEt(
            "Describe the voice — e.g. a warm, mature female narrator, calm, slightly husky, speaking slowly",
            "", multi = true)
        p.addView(designInput)
        p.addView(spacer(dp(10)))
        designLangSp = spinner(listOf("English", "Chinese", "Japanese", "Auto"))
        p.addView(labelRow("Language", designLangSp!!))
        return p
    }

    // ---------------- script + generate ----------------

    private fun buildScriptArea(col: LinearLayout) {
        col.addView(displayTv("Script", 13, TXT))
        col.addView(spacer(dp(8)))
        textInput = fieldEt(
            "Text to speak. Use {here complete voice over} to mark narration start.",
            "", multi = true)
        col.addView(textInput)
        col.addView(spacer(dp(14)))
        buildSettingsCard(col)
        col.addView(spacer(dp(14)))
        generateBtn = mkButton("Generate voice", filled = true).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
            setOnClickListener { generateVoice() }
        }
        col.addView(generateBtn)
    }

    private fun pickRefAudio() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        startActivityForResult(intent, REQ_REF_AUDIO)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_REF_AUDIO && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val tmp = File(cacheDir, "picked_ref.wav")
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                } ?: throw RuntimeException("cannot open the picked file")
                askNameAndSave(tmp)
            } catch (e: Exception) {
                status("cannot read picked audio: ${e.message}", RED)
            }
        }
    }

    private fun queryName(uri: Uri): String = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else ""
        } ?: ""
    } catch (_: Exception) {
        ""
    }

    // ---------------- output settings (speed / loudness) ----------------

    /** One slider row: label, SeekBar, live value label. Returns (row, valueTv). */
    private fun sliderRow(
        label: String, lo: Double, hi: Double, cur: Double, onChange: (Double) -> Unit
    ): Pair<View, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(monoTv(label, 12, TXT2).apply {
            layoutParams = LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        val tv = monoTv(fmt2(cur), 12, TXT)
        val bar = android.widget.SeekBar(this).apply {
            max = 100
            progress = ((cur - lo) / (hi - lo) * 100).roundToInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    sb: android.widget.SeekBar?, p: Int, fromUser: Boolean
                ) {
                    val v = lo + (hi - lo) * p / 100.0
                    tv.text = fmt2(v)
                    onChange(v)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) { persistSettings() }
            })
        }
        row.addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(tv, LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))
        return row to tv
    }

    private fun fmt2(v: Double): String =
        String.format(java.util.Locale.US, "%.2f", v)

    private fun persistSettings() {
        getSharedPreferences("nexon_voice_settings", MODE_PRIVATE).edit()
            .putFloat("speed", speed.toFloat())
            .putFloat("loudness", loudness.toFloat())
            .apply()
    }

    private fun buildSettingsCard(col: LinearLayout) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Console.panelBg(context)
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }
        card.addView(displayTv("Output settings", 13, TXT))
        card.addView(spacer(dp(8)))
        val s = sliderRow("Speed", 0.5, 2.0, speed) { v -> speed = v }
        card.addView(s.first)
        card.addView(spacer(dp(6)))
        val l = sliderRow("Loudness", 0.3, 2.5, loudness) { v -> loudness = v }
        card.addView(l.first)
        card.addView(spacer(dp(10)))
        card.addView(mkButton("Set defaults (1.00 / 1.00)").apply {
            setOnClickListener {
                speed = 1.0
                loudness = 1.0
                persistSettings()
                showMainScreen()
            }
        })
        col.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // ---------------- preview playback ----------------

    /** Play a bundled speaker preview from assets (copied to cache first so
     *  playback works regardless of APK asset compression). */
    private fun previewSpeaker() {
        val name = speakerSp?.selectedItem as? String ?: return
        try {
            val cache = File(cacheDir, "preview_$name.wav")
            if (!cache.exists() || cache.length() == 0L) {
                assets.open("audio/$name.wav").use { input ->
                    cache.outputStream().use { input.copyTo(it) }
                }
            }
            playFile(cache)
        } catch (_: Exception) {
            status("no preview bundled for $name", RED)
        }
    }

    private fun playFile(f: File) {
        try {
            previewMp?.release()
            previewMp = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                prepare()
                start()
            }
        } catch (_: Exception) {
        }
    }

    // ---------------- saved clone voices ----------------

    /** Rows for each saved voice: preview / use / delete. */
    private fun refreshSavedVoices() {
        val host = voiceListHost ?: return
        host.removeAllViews()
        if (savedVoices.isEmpty()) {
            host.addView(bodyTv(
                "No saved voices yet — record your voice once and it stays saved on this phone.",
                11, TXT2))
            return
        }
        host.addView(displayTv("Saved voices", 12, TXT))
        host.addView(spacer(dp(6)))
        savedVoices.forEach { v ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Console.panelBg(context)
                setPadding(dp(10), dp(6), dp(6), dp(6))
            }
            row.addView(monoTv(v.name, 12, TXT).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(mkButton("Play").apply { setOnClickListener { playFile(v.file) } })
            row.addView(mkButton(if (selectedVoice?.id == v.id) "Using" else "Use").apply {
                setOnClickListener {
                    selectedVoice = v
                    refreshSavedVoices()
                }
            })
            row.addView(mkButton("X", danger = true).apply {
                setOnClickListener {
                    voiceStore.remove(v.id)
                    savedVoices = voiceStore.list()
                    if (selectedVoice?.id == v.id) selectedVoice = null
                    refreshSavedVoices()
                }
            })
            host.addView(row)
            host.addView(spacer(dp(6)))
        }
    }

    // ---------------- generation ----------------

    private fun generateVoice() {
        if (generating) return
        val base = baseUrlInput?.text.toString().trim()
        if (base.isEmpty()) {
            status("enter the BASE URL first", RED); return
        }
        securePrefs.voiceBaseUrl = base
        val script = textInput?.text.toString().trim()
        if (script.isEmpty()) {
            status("paste the script to speak", RED); return
        }
        var language = "English"
        var speakerSel = ""
        var designPrompt = ""
        var refText = ""
        when (mode) {
            "clone" -> {
                if (selectedVoice == null) {
                    status("add or select a saved voice first", RED); return
                }
                refText = CLONE_SCRIPT
                language = cloneLangSp?.selectedItem as? String ?: "English"
            }
            "design" -> {
                designPrompt = designInput?.text.toString().trim()
                if (designPrompt.isEmpty()) {
                    status("enter a voice design prompt", RED); return
                }
                language = designLangSp?.selectedItem as? String ?: "English"
            }
            else -> {
                speakerSel = speakerSp?.selectedItem as? String ?: "Ryan"
                language = customLangSp?.selectedItem as? String ?: "English"
            }
        }
        generating = true
        voiceCancel = false
        generateBtn?.isEnabled = false
        status("submitting job...", TXT2)
        val finalMode = mode
        val instruct = instructInput?.text.toString()
        Thread {
            runVoiceJob(base, script, finalMode, language, speakerSel,
                instruct, designPrompt, refText, speed, loudness)
        }.start()
    }

    // ---------------- clone setup screen (record / pick) ----------------

    private fun showCloneSetupScreen() {
        val pad = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        col.addView(displayTv("Add my voice", 24, AMBER))
        col.addView(spacer(dp(6)))
        col.addView(bodyTv(
            "Record in a quiet place, in exactly your own tone and style. " +
                "Read the text below out loud exactly as written — no need to finish the whole " +
                "paragraph, the first 15 seconds are enough, and no need to rush. Read at your " +
                "comfortable pace.",
            13, TXT2))
        col.addView(spacer(dp(12)))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Console.panelBg(context)
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }
        card.addView(displayTv("Read aloud", 12, TXT))
        card.addView(spacer(dp(6)))
        card.addView(bodyTv(CLONE_SCRIPT, 13, TXT))
        col.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(spacer(dp(14)))
        val recBtn = mkButton("Record my voice", filled = true).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
            setOnClickListener { startRecording(this) }
        }
        col.addView(recBtn)
        col.addView(spacer(dp(8)))
        col.addView(mkButton("Pick an audio file instead").apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
            setOnClickListener { pickRefAudio() }
        })
        col.addView(spacer(dp(8)))
        statusTv = bodyTv("", 11, TXT2)
        col.addView(statusTv)
        col.addView(spacer(dp(18)))
        col.addView(mkButton("Back to voice studio").apply {
            setOnClickListener { showMainScreen() }
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }

    // ---------------- microphone recording (AudioRecord -> WAV) ----------------

    private fun startRecording(btn: Button) {
        if (recording) { recCancelled = true; return }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_PERM_MIC)
            return
        }
        val f = File(cacheDir, "rec_${System.currentTimeMillis()}.wav")
        recording = true
        recCancelled = false
        btn.text = "STOP RECORDING"
        status("recording — up to 15 s, tap stop when done", TXT2)
        recThread = Thread {
            try {
                val rate = 16000
                val minBuf = android.media.AudioRecord.getMinBufferSize(
                    rate, android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT
                )
                val rec = android.media.AudioRecord(
                    MediaRecorder.AudioSource.MIC, rate,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
                )
                val maxBytes = rate * 2 * 15
                val data = java.io.ByteArrayOutputStream()
                val buf = ShortArray(2048)
                try {
                    rec.startRecording()
                    while (!recCancelled && data.size() < maxBytes) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n > 0) {
                            val b = ByteArray(n * 2)
                            java.nio.ByteBuffer.wrap(b)
                                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                .asShortBuffer().put(buf, 0, n)
                            data.write(b)
                        }
                    }
                } finally {
                    try { rec.stop() } catch (_: Exception) {}
                    rec.release()
                }
                writeWav(f, data.toByteArray(), rate)
                val secs = data.size() / (rate * 2.0)
                ui {
                    recording = false
                    btn.text = "RECORD MY VOICE"
                    if (secs >= 3.0) {
                        playFile(f)
                        askNameAndSave(f)
                        status(String.format(java.util.Locale.US,
                            "recorded %.1f s — listen, then save", secs), TEAL)
                    } else {
                        status("too short — read at least a few seconds", RED)
                    }
                }
            } catch (e: Exception) {
                ui {
                    recording = false
                    btn.text = "RECORD MY VOICE"
                    status("recording failed: ${e.message}", RED)
                }
            }
        }.also { it.start() }
    }

    private fun stopRecorder() {
        recCancelled = true
        try { recThread?.join(1000) } catch (_: Exception) {}
        recThread = null
        recording = false
    }

    /** Minimal WAV writer: 16-bit PCM mono header + payload. */
    private fun writeWav(f: File, pcm: ByteArray, rate: Int) {
        java.io.FileOutputStream(f).use { w ->
            w.write("RIFF".toByteArray()); w32(w, 36 + pcm.size)
            w.write("WAVE".toByteArray()); w.write("fmt ".toByteArray())
            w32(w, 16); w16(w, 1); w16(w, 1)
            w32(w, rate); w32(w, rate * 2); w16(w, 2); w16(w, 16)
            w.write("data".toByteArray()); w32(w, pcm.size)
            w.write(pcm)
        }
    }

    private fun w16(w: java.io.OutputStream, v: Int) {
        w.write(v and 0xFF); w.write((v shr 8) and 0xFF)
    }

    private fun w32(w: java.io.OutputStream, v: Int) {
        w16(w, v); w16(w, v shr 16)
    }

    /** Name dialog -> save into VoiceStore -> select + refresh. */
    private fun askNameAndSave(src: File) {
        val input = fieldEt("Voice name (e.g. My narration voice)", "")
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        wrap.addView(input)
        android.app.AlertDialog.Builder(this)
            .setTitle("Save this voice")
            .setMessage("Saved voices stay on this phone — pick them anytime without recording again.")
            .setView(wrap)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "My voice" }
                val v = voiceStore.add(name, src)
                savedVoices = voiceStore.list()
                selectedVoice = v
                refreshSavedVoices()
                status("saved \"$name\" — selected for cloning", TEAL)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM_MIC) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                status("mic ready — tap Record my voice", TEAL)
            } else {
                status("mic denied — use Pick an audio file instead", RED)
            }
        }
    }

    /** Background job: submit → poll → download → export. Updates via ui{}. */
    private fun runVoiceJob(
        base: String,
        script: String,
        modeUsed: String,
        language: String,
        speaker: String,
        instruct: String,
        designPrompt: String,
        refText: String,
        speedUsed: Double,
        loudnessUsed: Double
    ) {
        val api = VoiceApi(base)
        var refBytes: Pair<String, ByteArray>? = null
        if (modeUsed == "clone") {
            try {
                val v = selectedVoice ?: throw RuntimeException("no voice selected")
                val bytes = v.file.readBytes()
                if (bytes.isEmpty()) throw RuntimeException("empty voice file")
                refBytes = "${v.name}.wav" to bytes
            } catch (e: Exception) {
                ui { status("cannot read saved voice: ${e.message}", RED); doneUi() }
                return
            }
        }
        val jobId = api.generate(script, modeUsed, language, speaker,
            instruct, designPrompt, refText, refBytes, speedUsed, loudnessUsed)
        if (jobId == null) {
            ui { status("submit failed: ${api.lastError}", RED); doneUi() }
            return
        }
        ui { status("job $jobId ($modeUsed) — processing...", TXT2) }
        var dur = 0.0
        var nulls = 0
        while (!voiceCancel) {
            Thread.sleep(2000)
            val s = api.getStatus(jobId)
            if (s == null) {
                if (++nulls > 10) {
                    ui { status("lost the server (poll failed 10x). Is the Kaggle cell alive?", RED); doneUi() }
                    return
                }
                continue
            }
            nulls = 0
            if (s.status == "error") {
                ui { status("server error: ${s.error}", RED); doneUi() }
                return
            }
            if (s.status == "done") {
                dur = s.durationSec
                break
            }
            ui { status("job $jobId — still processing...", TXT2) }
        }
        if (voiceCancel) {
            ui { status("cancelled", TXT2); doneUi() }
            return
        }
        ui { status("done — ${String.format(java.util.Locale.US, "%.1f", dur)}s. downloading...", TXT2) }
        val tmp = File(cacheDir, "voice_$jobId.wav")
        val okDl = api.downloadWav(jobId, tmp) { p -> ui { status("downloading $p%", TXT2) } }
        if (!okDl || !tmp.exists() || tmp.length() == 0L) {
            ui { status("download failed: ${api.lastError}", RED); doneUi() }
            return
        }
        val uri = exportWav(tmp)
        ui {
            if (uri != null) showDoneScreen(uri, tmp, dur, modeUsed)
            else {
                status("saved to app cache only — MediaStore export failed", RED)
                doneUi()
            }
        }
    }

    /** WAV into Music/NexonStudio via MediaStore, IS_PENDING pattern. */
    private fun exportWav(file: File): Uri? {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "NexonStudio_voice_$stamp.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/NexonStudio")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        return try {
            contentResolver.openOutputStream(uri)?.use { outs ->
                file.inputStream().use { it.copyTo(outs) }
            } ?: throw RuntimeException("cannot open output stream")
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- done screen ----------------

    private fun showDoneScreen(uri: Uri, file: File, durSec: Double, modeUsed: String) {
        doneUi()
        val pad = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        col.addView(displayTv("Voice done", 26, AMBER))
        col.addView(spacer(dp(6)))
        col.addView(bodyTv("Qwen3-TTS synthesis finished and saved to Music/NexonStudio.", 13, TXT2))
        col.addView(spacer(dp(14)))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Console.panelBg(context)
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }
        card.addView(monoTv("mode      $modeUsed", 12, TXT2))
        card.addView(monoTv(
            "duration  ${String.format(java.util.Locale.US, "%.1f", durSec)} s", 12, TXT2))
        card.addView(monoTv("size      ${file.length() / 1024} KB", 12, TXT2))
        card.addView(monoTv("saved     Music/NexonStudio (WAV)", 11, TXT2))
        col.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(spacer(dp(16)))
        col.addView(mkButton("Play", filled = true).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "audio/wav")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                } catch (_: Exception) {
                }
            }
        })
        col.addView(spacer(dp(8)))
        col.addView(mkButton("Share").apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
            setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "audio/wav"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, "Share voice"))
            }
        })
        col.addView(spacer(dp(8)))
        col.addView(mkButton("Generate another").apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
            setOnClickListener { showMainScreen() }
        })
        col.addView(spacer(dp(8)))
        col.addView(mkButton("Back to menu").apply { setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { addView(col) })
    }
}
