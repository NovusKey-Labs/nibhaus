package com.nibhaus.pen.replay

import com.nibhaus.pen.PenDot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Replays a recorded pen session through the same dot path the live pen uses, so the full
 * capture pipeline can be tested against REAL data with no hardware in the loop.
 *
 * Flow: capture once on real hardware (the Phase 0 spike logs each dot) → save as a `.jsonl`
 * fixture → replay it here and in CI forever. Because [PenDot] is exactly what
 * [com.nibhaus.pen.NeoPenSdk] emits, the replayed dots exercise ingestion, organization, and
 * crash-recovery identically to a live pen.
 *
 * Fixture format: one JSON object per line ([ReplayDot]). Example line:
 *   {"section":3,"owner":27,"note":603,"page":1,"x":12.5,"y":40.0,"pressure":0.6,"phase":"DOWN","t":1718900000000,"color":-16777216}
 */
class ReplayPenSource(private val json: Json = Json { ignoreUnknownKeys = true }) {

    /** Parse a `.jsonl` session into ordered [PenDot]s. Blank lines and `#` comments are ignored. */
    fun parse(jsonl: String): List<PenDot> =
        jsonl.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { json.decodeFromString(ReplayDot.serializer(), it).toPenDot() }
            .toList()

    /** Replay each dot into [onDot], preserving order. Returns the count emitted. */
    fun replayInto(jsonl: String, onDot: (PenDot) -> Unit): Int {
        val dots = parse(jsonl)
        dots.forEach(onDot)
        return dots.size
    }
}

@Serializable
data class ReplayDot(
    val section: Int,
    val owner: Int,
    val note: Int,
    val page: Int,
    val x: Float,
    val y: Float,
    val pressure: Float = 0f,
    val phase: String,            // DOWN | MOVE | UP
    val t: Long = 0,
    val color: Int = 0xFF000000.toInt(),
) {
    fun toPenDot() = PenDot(
        section = section, owner = owner, book = note, page = page,
        x = x, y = y, pressure = pressure,
        phase = PenDot.Phase.valueOf(phase.uppercase()),
        timestamp = t, color = color,
    )
}
