package com.brollrender.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class BlockStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("broll_blocks", Context.MODE_PRIVATE)

    data class BlockEntry(val path: String, val name: String)

    fun loadAll(): List<BlockEntry> {
        val json = prefs.getString("blocks", null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                BlockEntry(o.getString("path"), o.getString("name"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveAll(blocks: List<BlockEntry>) {
        val arr = JSONArray()
        blocks.forEach { b ->
            arr.put(JSONObject().apply {
                put("path", b.path)
                put("name", b.name)
            })
        }
        prefs.edit().putString("blocks", arr.toString()).apply()
    }
}
