package com.nibhaus.zones

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Persists the user's calibrated action zones (JSON in SharedPreferences). [current] is a synchronous
 * read for the BLE/ingest thread that matches taps; [zones] is the reactive view for the UI.
 */
class ActionZoneStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("nibhaus_zones", Context.MODE_PRIVATE)
    private val ser = ListSerializer(ActionZone.serializer())

    private val _zones = MutableStateFlow(load())
    val zones: StateFlow<List<ActionZone>> = _zones

    /** Synchronous snapshot for tap matching. */
    fun current(): List<ActionZone> = _zones.value

    fun add(zone: ActionZone) = save(current() + zone)
    fun remove(id: String) = save(current().filterNot { it.id == id })

    private fun save(list: List<ActionZone>) {
        prefs.edit().putString(KEY, Json.encodeToString(ser, list)).apply()
        _zones.value = list
    }

    private fun load(): List<ActionZone> = runCatching {
        prefs.getString(KEY, null)?.let { Json.decodeFromString(ser, it) } ?: emptyList()
    }.getOrDefault(emptyList())

    private companion object { const val KEY = "zones" }
}
