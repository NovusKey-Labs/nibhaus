package com.nibhaus.gesture

/**
 * A margin "hot-zone" in page-normalized coordinates (0..1, origin top-left). A stroke counts as a
 * command only when it is fully contained in the zone AND matches a gesture template — both
 * conservative on purpose, so ordinary writing is never hijacked. Zones are opt-in (Phase D).
 *
 * Normalization note: the caller maps raw Ncode dots to 0..1 against the page extent before
 * hit-testing. Until the page's true dot dimensions are calibrated on hardware, normalize against
 * the page's observed ink bounds; the geometry here is independent of that choice.
 */
data class HotZone(
    val id: String,
    val label: String,
    val action: ZoneAction,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun contains(nx: Float, ny: Float): Boolean = nx in left..right && ny in top..bottom
}

/** What a recognized command-mark in a zone does. */
enum class ZoneAction { FLAG_IMPORTANT, FAVORITE, MAKE_CHECKBOX }
object HotZones {
    /** Conservative default: just the top-right corner. */
    val TOP_RIGHT = HotZone("top_right", "Top-right", ZoneAction.FLAG_IMPORTANT, 0.80f, 0.0f, 1.0f, 0.15f)

    val DEFAULTS = listOf(TOP_RIGHT)

    /** Which gesture, drawn inside a zone, maps to which action. */
    fun actionFor(gesture: String): ZoneAction? = when (gesture) {
        "check" -> ZoneAction.FLAG_IMPORTANT
        "star" -> ZoneAction.FAVORITE
        "box" -> ZoneAction.MAKE_CHECKBOX
        else -> null
    }

    /**
     * True only if every normalized point of the stroke lies within [zone] — a command must be
     * wholly inside the margin, never straddling the writing area.
     */
    fun strokeInside(zone: HotZone, normalizedPoints: List<Pair<Float, Float>>): Boolean =
        normalizedPoints.isNotEmpty() && normalizedPoints.all { (x, y) -> zone.contains(x, y) }

    /** The first zone that fully contains the stroke, or null. */
    fun zoneFor(zones: List<HotZone>, normalizedPoints: List<Pair<Float, Float>>): HotZone? =
        zones.firstOrNull { strokeInside(it, normalizedPoints) }
}
