package com.trivideo.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class VideoSet(val id: Long, val name: String, val uris: List<String>)

object VideoSetsStore {
    private const val PREFS_NAME = "trivideo_prefs"
    private const val VIDEO_SETS_KEY = "video_sets"

    fun load(context: Context): MutableList<VideoSet> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(VIDEO_SETS_KEY, null) ?: return mutableListOf()
        return try {
            val array = JSONArray(raw)
            MutableList(array.length()) { i ->
                val obj = array.getJSONObject(i)
                val urisArray = obj.getJSONArray("uris")
                val uris = List(urisArray.length()) { j -> urisArray.getString(j) }
                VideoSet(obj.getLong("id"), obj.getString("name"), uris)
            }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun save(context: Context, sets: List<VideoSet>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        for (set in sets) {
            val obj = JSONObject()
            obj.put("id", set.id)
            obj.put("name", set.name)
            obj.put("uris", JSONArray(set.uris))
            array.put(obj)
        }
        prefs.edit().putString(VIDEO_SETS_KEY, array.toString()).apply()
    }
}
