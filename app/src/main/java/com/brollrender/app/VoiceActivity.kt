package com.brollrender.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
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
    private var refTextInput: EditText? = null
    private var speakerSp: Spinner? = null
    private var customLangSp: Spinner? = null
    private var cloneLangSp: Spinner? = null
    private var designLangSp: Spinner? = null
    private var refNameTv: TextView? = null
    private var refAudioUri: Uri? = null
    private var refAudioName = ""

    // One panel per mode — visibility-toggled so typed input survives switches.
    private var customPanel: LinearLayout? = null
    private var clonePanel: LinearLayout? = null
    private var designPanel: LinearLayout? = null
    private var modeButtons = listOf<Pair<String, Button>>()

    private var generateBtn: Button? = null
    private var statusTv: TextView? = null

    @Volatile private var generating = false
    @Volatile private var voiceCancel = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        securePrefs = SecurePrefs(this)
        showMainScreen()
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
        val prevRefText = refTextInput?.text?.toString().orEmpty()

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
        refTextInput?.setText(prevRefText)
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
        val pick = mkButton("Pick reference audio (3-15 s)", filled = true)
        pick.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
        pick.setOnClickListener { pickRefAudio() }
        p.addView(pick)
        refNameTv = monoTv(
            if (refAudioName.isNotBlank()) refAudioName else "no audio picked yet",
            11, TXT2)
        p.addView(refNameTv)
        p.addView(spacer(dp(10)))
        refTextInput = fieldEt(
            "Reference transcript — the exact words spoken in the audio", "", multi = true)
        p.addView(refTextInput)
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
            refAudioUri = uri
            refAudioName = queryName(uri)
            refNameTv?.text = if (refAudioName.isNotBlank()) refAudioName else "audio selected"
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
                if (refAudioUri == null) {
                    status("pick a reference audio first", RED); return
                }
                refText = refTextInput?.text.toString().trim()
                if (refText.isEmpty()) {
                    status("type the exact transcript of the reference audio", RED); return
                }
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
                instruct, designPrompt, refText)
        }.start()
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
        refText: String
    ) {
        val api = VoiceApi(base)
        var refBytes: Pair<String, ByteArray>? = null
        if (modeUsed == "clone") {
            try {
                val bytes = contentResolver.openInputStream(refAudioUri!!)?.use { it.readBytes() }
                if (bytes == null || bytes.isEmpty()) throw RuntimeException("empty file")
                refBytes = (if (refAudioName.isNotBlank()) refAudioName else "ref.wav") to bytes
            } catch (e: Exception) {
                ui { status("cannot read reference audio: ${e.message}", RED); doneUi() }
                return
            }
        }
        val jobId = api.generate(script, modeUsed, language, speaker,
            instruct, designPrompt, refText, refBytes)
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
