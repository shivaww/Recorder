package com.brollrender.app.remote

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class JobStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("broll_remote_jobs", Context.MODE_PRIVATE)

    data class JobMeta(
        val jobId: String,
        val fileName: String,
        val createdAt: Long,
        var state: String,
        val fps: Int,
        val resolution: String,
        val duration: Int,
        val enhance: Boolean,
        var downloaded: Boolean = false,
        var localUri: String? = null
    )

    fun loadAll(): MutableList<JobMeta> {
        val json = prefs.getString("jobs", null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<JobMeta>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    JobMeta(
                        jobId = o.getString("jobId"),
                        fileName = o.getString("fileName"),
                        createdAt = o.getLong("createdAt"),
                        state = o.getString("state"),
                        fps = o.getInt("fps"),
                        resolution = o.getString("resolution"),
                        duration = o.getInt("duration"),
                        enhance = o.getBoolean("enhance"),
                        downloaded = o.optBoolean("downloaded", false),
                        localUri = o.optString("localUri", null)
                    )
                )
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveAll(jobs: List<JobMeta>) {
        val arr = JSONArray()
        jobs.forEach { j ->
            arr.put(JSONObject().apply {
                put("jobId", j.jobId)
                put("fileName", j.fileName)
                put("createdAt", j.createdAt)
                put("state", j.state)
                put("fps", j.fps)
                put("resolution", j.resolution)
                put("duration", j.duration)
                put("enhance", j.enhance)
                put("downloaded", j.downloaded)
                put("localUri", j.localUri)
            })
        }
        prefs.edit().putString("jobs", arr.toString()).apply()
    }
}