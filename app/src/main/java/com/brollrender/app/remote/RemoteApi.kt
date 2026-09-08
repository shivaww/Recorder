package com.brollrender.app.remote

import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Network layer for Kaggle remote rendering.
 * Uses HttpURLConnection and manual multipart encoding.
 */
class RemoteApi(private val baseUrl: String, private val apiKey: String) {

    data class JobStatus(
        val state: String,
        val frame: Int = 0,
        val totalFrames: Int = 0,
        val pct: Int = 0,
        val etaSec: Int = -1,
        val renderFps: Double = 0.0,
        val error: String? = null,
        val validationReason: String? = null
    )

    private fun connect(path: String, method: String, timeout: Int = 15000): HttpURLConnection {
        val url = URL("${baseUrl.trimEnd('/')}$path")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeout
            readTimeout = timeout
            setRequestProperty("X-API-Key", apiKey)
            instanceFollowRedirects = true
        }
        return conn
    }

    /** Returns (Reachable, LatencyMs) */
    fun testConnection(): Pair<Boolean, Long> {
        val start = System.currentTimeMillis()
        return try {
            val conn = connect("/health", "GET", 5000)
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            ok to (System.currentTimeMillis() - start)
        } catch (e: Exception) {
            false to -1L
        }
    }

    // Multipart and other verbs appended below...

    var lastError: String? = null
        private set

    fun submitJob(htmlFile: File, fps: Int, resolution: String, duration: Int, enhance: Boolean, onProgress: ((Int) -> Unit)? = null): String? {
        lastError = null
        val boundary = "broll-boundary-${System.currentTimeMillis()}"
        val conn = connect("/jobs", "POST", 30000).apply {
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            doOutput = true
        }
        
        try {
            val out = conn.outputStream
            fun writeField(name: String, value: Any) {
                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                out.write(value.toString().toByteArray())
                out.write("\r\n".toByteArray())
            }
            
            writeField("fps", fps)
            writeField("resolution", resolution)
            writeField("duration", duration)
            writeField("enhance", enhance)
            
            out.write("--$boundary\r\n".toByteArray())
            out.write("Content-Disposition: form-data; name=\"html\"; filename=\"${htmlFile.name}\"\r\n".toByteArray())
            out.write("Content-Type: text/html\r\n\r\n".toByteArray())
            val totalBytes = htmlFile.length()
            var sent = 0L
            htmlFile.inputStream().use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sent += n
                    if (totalBytes > 0) onProgress?.invoke((sent * 100 / totalBytes).toInt())
                }
            }
            out.write("\r\n".toByteArray())
            
            out.write("--$boundary--\r\n".toByteArray())
            out.flush()
            
            if (conn.responseCode !in 200..299) {
                lastError = "HTTP ${conn.responseCode}"
                return null
            }
            
            val resp = conn.inputStream.bufferedReader().readText()
            // Expects: {"job_id": "abc123"}
            val regex = Regex("\"job_id\"\\s*:\\s*\"([^\"]+)\"")
            return regex.find(resp)?.groupValues?.get(1)
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            return null
        } finally {
            conn.disconnect()
        }
    }
    
    fun getStatus(jobId: String): JobStatus? {
        val conn = connect("/jobs/$jobId/status", "GET", 10000)
        return try {
            if (conn.responseCode !in 200..299) return null
            val resp = conn.inputStream.bufferedReader().readText()
            val state = Regex("\"state\"\\s*:\\s*\"([^\"]+)\"").find(resp)?.groupValues?.get(1) ?: return null
            val frame = Regex("\"frame\"\\s*:\\s*(\\d+)").find(resp)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val total = Regex("\"total_frames\"\\s*:\\s*(\\d+)").find(resp)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val error = Regex("\"error\"\\s*:\\s*\"([^\"]+)\"").find(resp)?.groupValues?.get(1)
            val pct = Regex("\"pct\"\\s*:\\s*(\\d+)").find(resp)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val eta = Regex("\"eta_sec\"\\s*:\\s*(-?\\d+)").find(resp)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val rfps = Regex("\"render_fps\"\\s*:\\s*([\\d.]+)").find(resp)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val vReason = Regex("\"reason\"\\s*:\\s*\"([^\"]+)\"").find(resp)?.groupValues?.get(1)
            JobStatus(state, frame, total, pct, eta, rfps, error, vReason)
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
    
    fun downloadFile(jobId: String, dest: File, onProgress: (Int, Double) -> Unit): Boolean {
        val conn = connect("/jobs/$jobId/download", "GET", 60000)
        return try {
            if (conn.responseCode !in 200..299) return false
            val total = conn.contentLengthLong
            var bytesRead = 0L
            var lastTime = System.currentTimeMillis()
            var lastBytes = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        bytesRead += n
                        val now = System.currentTimeMillis()
                        if (now - lastTime >= 1000) {
                            val speed = (bytesRead - lastBytes) / 1024.0 / ((now - lastTime) / 1000.0)
                            val pct = if (total > 0) (bytesRead * 100 / total).toInt() else -1
                            onProgress(pct, speed)
                            lastTime = now
                            lastBytes = bytesRead
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }
    
    fun startJob(jobId: String): Boolean {
        val conn = connect("/jobs/$jobId/start", "POST", 10000)
        return try {
            conn.responseCode in 200..299
        } catch (e: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    data class GpuInfo(val id: Int, val utilPct: Int, val memUsedMb: Int, val memTotalMb: Int, val tempC: Int)

    fun getGpuUsage(): List<GpuInfo> {
        val conn = connect("/gpu", "GET", 5000)
        return try {
            if (conn.responseCode !in 200..299) return emptyList()
            val resp = conn.inputStream.bufferedReader().readText()
            val list = mutableListOf<GpuInfo>()
            Regex("\\{[^}]*\\}").findAll(resp).forEach { m ->
                val s = m.value
                fun num(key: String): Int = Regex("\"$key\"\\s*:\\s*(\\d+)").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                list.add(GpuInfo(num("id"), num("util_pct"), num("mem_used_mb"), num("mem_total_mb"), num("temp_c")))
            }
            list
        } catch (e: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    /** Returns (httpCode, status). 404 = job gone (server restarted); -1 = network error. */
    fun getStatusDetailed(jobId: String): Pair<Int, JobStatus?> {
        return try {
            val conn = connect("/jobs/$jobId/status", "GET", 10000)
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return code to null
            }
            val resp = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val state = Regex("\"state\"\\s*:\\s*\"([^\"]+)\"").find(resp)?.groupValues?.get(1) ?: return code to null
            val frame = Regex("\"frame\"\\s*:\\s*(\\d+)").find(resp)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val total = Regex("\"total_frames\"\\s*:\\s*(\\d+)").find(resp)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val pct = Regex("\"pct\"\\s*:\\s*(\\d+)").find(resp)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val eta = Regex("\"eta_sec\"\\s*:\\s*(-?\\d+)").find(resp)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val rfps = Regex("\"render_fps\"\\s*:\\s*([\\d.]+)").find(resp)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val error = Regex("\"error\"\\s*:\\s*\"([^\"]+)\"").find(resp)?.groupValues?.get(1)
            val vReason = Regex("\"reason\"\\s*:\\s*\"([^\"]+)\"").find(resp)?.groupValues?.get(1)
            code to JobStatus(state, frame, total, pct, eta, rfps, error, vReason)
        } catch (e: Exception) {
            -1 to null
        }
    }

    fun cancelJob(jobId: String): Boolean {
        val conn = connect("/jobs/$jobId", "DELETE", 10000)
        return try {
            conn.responseCode in 200..299
        } catch (e: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }
}
