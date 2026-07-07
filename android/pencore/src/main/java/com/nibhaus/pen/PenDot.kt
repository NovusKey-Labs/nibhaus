package com.nibhaus.pen

/**
 * A single sampled point from the pen, normalized away from the raw SDK type.
 *
 * Mirrors the data carried by the Neo SDK's `kr.neolab.sdk.ink.structure.Dot`:
 * a coordinate, pressure, a stroke-phase, a timestamp, and the four Ncode
 * identifiers that locate the dot on physical paper.
 */
data class PenDot(
    val section: Int,
    val owner: Int,
    val book: Int,
    val page: Int,
    val x: Float,
    val y: Float,
    val pressure: Float,
    val phase: Phase,
    val timestamp: Long,
    val color: Int,
) {
    /** The Ncode page this dot belongs to. */
    val address: NcodeAddress get() = NcodeAddress(section, owner, book, page)

    enum class Phase { DOWN, MOVE, UP }
}

/**
 * The Ncode address `(section, owner, book, page)` uniquely identifies a physical
 * page of Ncode paper. This is the key the auto-organizer files notes by — see
 * [com.nibhaus.organize.AutoOrganizer].
 */
data class NcodeAddress(
    val section: Int,
    val owner: Int,
    val book: Int,
    val page: Int,
) {
    /** Stable string key for maps / DB columns. */
    val key: String get() = "$section.$owner.$book.$page"

    /** A real, resolved Ncode page — the SDK reports negatives for unrecognized paper. */
    val isValid: Boolean get() = section >= 0 && owner >= 0 && book >= 0 && page >= 0
}
