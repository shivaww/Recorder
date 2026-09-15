package com.brollrender.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Saved clone voices: WAV files in filesDir/voice_refs plus a JSON manifest.
 * Recorded in-app or picked from storage; selected by name at generate time.
 */
class VoiceStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("nexon_voice_refs", Context.MODE_PRIVATE)
    private val dir: File =
        File(context.filesDir, "voice_refs").apply { if (!exists()) mkdirs() }

    data class Voice(
        val id: String,
        val name: String,
        val file: File,
        val createdAt: Long
    )

    fun list(): MutableList<Voice> {
        val json = prefs.getString("voices", null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(json)
            val out = mutableListOf<Voice>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val f = File(dir, o.getString("file"))
                if (f.isFile) {
                    out.add(Voice(o.getString("id"), o.getString("name"), f, o.getLong("createdAt")))
                }
            }
            out.sortByDescending { it.createdAt }
            out
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun persist(list: List<Voice>) {
        val arr = JSONArray()
        list.forEach { v ->
            arr.put(JSONObject().apply {
                put("id", v.id)
                put("name", v.name)
                put("file", v.file.name)
                put("createdAt", v.createdAt)
            })
        }
        prefs.edit().putString("voices", arr.toString()).apply()
    }

    /** Copies src into the store under a fresh id. Returns the stored voice. */
    fun add(name: String, src: File): Voice {
        val id = UUID.randomUUID().toString().substring(0, 8)
        val dest = File(dir, "$id.wav")
        src.copyTo(dest, overwrite = true)
        val v = Voice(id, name.trim().ifEmpty { "My voice" }, dest, System.currentTimeMillis())
        persist(list() + v)
        return v
    }

    fun remove(id: String) {
        val keep = list().filter { it.id != id }
        File(dir, "$id.wav").delete()
        persist(keep)
    }
}
