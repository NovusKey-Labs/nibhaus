package com.nibhaus.pen

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A pen this device has successfully connected to before — drives the Pens screen's saved-pen
 * reconnect tiles. [spp] is the pen's stable NeoLAB identity ([PenTarget.sppAddress]),
 * NEVER the rotating LE advertising address — that goes stale on power-cycle, so reconnecting a saved
 * pen must re-scan for [spp], not redial a cached address.
 */
@Serializable
data class SavedPen(val name: String, val spp: String, val lastConnectedAt: Long)

/** Remembers the last-paired pen so the app can auto-reconnect, plus every pen this
 *  device has ever successfully connected to (saved-pen tiles). */
interface PenPrefs {
    var lastPenMac: String?
    /** Previously connected pens, most-recently-connected first. */
    var savedPens: List<SavedPen>
}

class SharedPrefsPenPrefs(context: Context) : PenPrefs {
    private val sp: SharedPreferences =
        context.getSharedPreferences("pen_prefs", Context.MODE_PRIVATE)
    private val savedPensSerializer = ListSerializer(SavedPen.serializer())

    override var lastPenMac: String?
        get() = sp.getString(KEY_MAC, null)
        set(value) = sp.edit().putString(KEY_MAC, value).apply()

    override var savedPens: List<SavedPen>
        get() {
            val raw = sp.getString(KEY_SAVED_PENS, null) ?: return emptyList()
            return runCatching { Json.decodeFromString(savedPensSerializer, raw) }.getOrDefault(emptyList())
        }
        set(value) = sp.edit().putString(KEY_SAVED_PENS, Json.encodeToString(savedPensSerializer, value)).apply()

    private companion object {
        const val KEY_MAC = "last_pen_mac"
        const val KEY_SAVED_PENS = "saved_pens"
    }
}

/** In-memory prefs for tests. */
class InMemoryPenPrefs(initial: String? = null, initialSavedPens: List<SavedPen> = emptyList()) : PenPrefs {
    override var lastPenMac: String? = initial
    override var savedPens: List<SavedPen> = initialSavedPens
}

/**
 * Upsert [pen] into [pens] by [SavedPen.spp] — the dedupe key (a stable identity; the LE address
 * rotates and is never used here). An already-saved pen refreshes its name/lastConnectedAt and moves
 * to the front (most-recently-connected first) rather than duplicating. Pure — unit-testable without
 * a SharedPreferences round-trip.
 */
fun upsertSavedPen(pens: List<SavedPen>, pen: SavedPen): List<SavedPen> =
    listOf(pen) + pens.filter { it.spp != pen.spp }

/** Drop the saved pen identified by [spp] (the Pens screen's long-press "forget" affordance).
 *  No-op if it isn't in the list. */
fun forgetSavedPen(pens: List<SavedPen>, spp: String): List<SavedPen> =
    pens.filter { it.spp != spp }
