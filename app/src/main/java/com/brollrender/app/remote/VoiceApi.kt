package com.brollrender.app.remote

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Network layer for the Qwen3-TTS voice server (voice/server.py, launched by
 * voice/run.sh on a Kaggle GPU notebook). Same style as RemoteApi:
 * HttpURLConnection, manual multipart, regex JSON parsing — zero deps.
 */
class VoiceApi(private val baseUrl: String) {

    data class JobStatus(
        val status: String,           // processing | done | error
        val durationSec: Double = 0.0,
        val error: String? = null
    )

    var lastError: String? = null
        private set

    private fun connect(path: String, method: String, timeout: Int = 15000): HttpURLConnection {
        val url = URL("${baseUrl.trimEnd('/')}$path")
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeout
            readTimeout = timeout
            instanceFollowRedirects = true
        }
    }

    /** GET /health. The voice server has no API key: 200 + status ok = good. */
    fun testConnection(): Pair<Boolean, Long> {
        lastError = null
        val start = System.currentTimeMillis()
        return try {
            val conn = connect("/health", "GET", 8000)
            val code = conn.responseCode
            val body = if (code in 200..299) conn.inputStream.bufferedReader().readText() else ""
            conn.disconnect()
            val ok = code in 200..299 &&
                Regex("\"status\"\\s*:\\s*\"ok\"").containsMatchIn(body)
            if (!ok) lastError = "HTTP $code"
            ok to (System.currentTimeMillis() - start)
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            false to -1L
        }
    }
}

    /**
     * POST /generate — fields mirror the tested tmp/qwen.html client exactly.
     * Returns the job id, or null with lastError set.
     */
    fun generate(
        text: String,
        mode: String,                          // custom | clone | design
        language: String,
        speaker: String,
        instruct: String,
        designPrompt: String,
        refText: String,
        refAudio: Pair<String, ByteArray>?     // (fileName, bytes) — clone mode
    ): String? {
        lastError = null
        val boundary = "voice-boundary-${System.currentTimeMillis()}"
        val conn = connect("/generate", "POST", 30000).apply {
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            doOutput = true
        }
        try {
            val out = conn.outputStream
            fun writeField(name: String, value: String) {
                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                out.write(value.toByteArray())
                out.write("\r\n".toByteArray())
            }
            writeField("text", text)
            writeField("mode", mode)
            if (mode == "custom") {
                writeField("speaker", speaker)
                writeField("language", language)
                writeField("instruct", instruct)
            } else if (mode == "clone") {
                writeField("language", language)
                writeField("ref_text", refText)
                refAudio?.let { (name, bytes) ->
                    out.write("--$boundary\r\n".toByteArray())
                    out.write(
                        ("Content-Disposition: form-data; name=\"ref_audio\"; " +
                            "filename=\"$name\"\r\n").toByteArray()
                    )
                    out.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())
                    out.write(bytes)
                    out.write("\r\n".toByteArray())
                }
            } else if (mode == "design") {
                writeField("language", language)
                writeField("design_prompt", designPrompt)
            }
            out.write("--$boundary--\r\n".toByteArray())
            out.flush()
            if (conn.responseCode !in 200..299) {
                lastError = "HTTP ${conn.responseCode}"
                return null
            }
            val resp = conn.inputStream.bufferedReader().readText()
            val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(resp)?.groupValues?.get(1)
            if (id == null) lastError = "no job id in response"
            return id
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** GET /status/<id> — processing | done | error (+ duration on done). */
    fun getStatus(jobId: String): JobStatus? {
        return try {
            val conn = connect("/status/$jobId", "GET", 10000)
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return null
            }
            val resp = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val status = Regex("\"status\"\\s*:\\s*\"([^\"]+)\"")
                .find(resp)?.groupValues?.get(1) ?: return null
            val dur = Regex("\"duration\"\\s*:\\s*([\\d.]+)")
                .find(resp)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val err = Regex("\"error\"\\s*:\\s*\"([^\"]+)\"").find(resp)?.groupValues?.get(1)
            JobStatus(status, dur, err)
        } catch (e: Exception) {
            null
        }
    }

    /** GET /download/<id> — stream the finished WAV into dest. */
    fun downloadWav(jobId: String, dest: File, onProgress: (Int) -> Unit): Boolean {
        val conn = connect("/download/$jobId", "GET", 120000)
        return try {
            if (conn.responseCode !in 200..299) {
                lastError = "HTTP ${conn.responseCode}"
                return false
            }
            val total = conn.contentLengthLong
            var read = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress((read * 100 / total).toInt())
                    }
                }
            }
            true
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            false
        } finally {
            conn.disconnect()
        }
    }
}
