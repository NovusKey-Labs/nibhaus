package com.nibhaus.capture

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Persists per-notebook capture profiles (JSON in SharedPreferences), keyed by Ncode book id. Mirrors
 * [com.nibhaus.zones.ActionZoneStore]: a synchronous [forBook] for the canvas/ingest path and a
 * reactive [profiles] for the UI.
 */
class NotebookProfileStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("nibhaus_notebook_profiles", Context.MODE_PRIVATE)
    private val ser = ListSerializer(NotebookProfile.serializer())

    private val _profiles = MutableStateFlow(load())
    val profiles: StateFlow<List<NotebookProfile>> = _profiles

    /** Synchronous snapshot — the live canvas resolves geometry on the UI thread per frame. */
    fun forBook(bookId: Int): NotebookProfile? = _profiles.value.firstOrNull { it.bookId == bookId }

    /** Upsert the profile for its book id. */
    fun save(profile: NotebookProfile) {
        val list = _profiles.value.filterNot { it.bookId == profile.bookId } + profile
        prefs.edit().putString(KEY, Json.encodeToString(ser, list)).apply()
        _profiles.value = list
    }

    private fun load(): List<NotebookProfile> = runCatching {
        prefs.getString(KEY, null)?.let { Json.decodeFromString(ser, it) } ?: emptyList()
    }.getOrDefault(emptyList())

    private companion object { const val KEY = "profiles" }
}
