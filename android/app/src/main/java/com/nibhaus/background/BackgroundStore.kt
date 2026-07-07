package com.nibhaus.background

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Per-notebook background template image (a persisted `content://` URI), rendered behind the ink —
 * the "template layer". Keyed by notebook because a physical notebook has one page layout. Stored in
 * SharedPreferences (a tiny map); the UI observes [map], the renderer reads [uriFor] synchronously.
 */
class BackgroundStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("nibhaus_backgrounds", Context.MODE_PRIVATE)
    private val ser = MapSerializer(String.serializer(), String.serializer())

    private val _map = MutableStateFlow(load())
    val map: StateFlow<Map<String, String>> = _map

    fun uriFor(notebookId: String): String? = _map.value[notebookId]
    fun set(notebookId: String, uri: String) = save(_map.value + (notebookId to uri))
    fun clear(notebookId: String) = save(_map.value - notebookId)

    private fun save(m: Map<String, String>) {
        prefs.edit().putString(KEY, Json.encodeToString(ser, m)).apply()
        _map.value = m
    }

    private fun load(): Map<String, String> = runCatching {
        prefs.getString(KEY, null)?.let { Json.decodeFromString(ser, it) } ?: emptyMap()
    }.getOrDefault(emptyMap())

    private companion object { const val KEY = "backgrounds" }
}
