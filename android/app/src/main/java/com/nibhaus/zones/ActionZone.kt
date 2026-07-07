package com.nibhaus.zones

import kotlinx.serialization.Serializable

/** What a tapped printed icon triggers. Share/Email of the current page — either at a fixed format,
 *  or (SHARE/EMAIL) asking PNG-vs-PDF in-app after the tap. */
@Serializable
enum class ZoneAction(val label: String) {
    SHARE_PNG("Share · PNG"),
    SHARE_PDF("Share · PDF"),
    EMAIL_PNG("Email · PNG"),
    EMAIL_PDF("Email · PDF"),
    SHARE("Share · ask format"),
    EMAIL("Email · ask format"),
}

/**
 * Built-in zones for the printed action buttons of known notebook products — the user's measured
 * button positions mapped through the pen-traced ruling anchors (page 33/34 traces), so no
 * calibration session is needed. User-calibrated zones win on overlap (list order in [matchZone]).
 * 438: Share 4x4mm centred 9mm from top / 23mm from right; Email 5(w)x3(h)mm at 9mm / 13mm (the
 * envelope is WIDER than tall — user correction 2026-07-02) — boxes padded ~1mm per side for
 * pen-tap tolerance. upm = 0.42274 units/mm from the traces.
 */
object BuiltinZones {
    val ALL: List<ActionZone> = listOf(
        ActionZone("builtin-438-share", ZoneAction.SHARE, left = 50.73f, top = 6.17f, right = 53.27f, bottom = 8.71f, book = 438),
        ActionZone("builtin-438-email", ZoneAction.EMAIL, left = 54.74f, top = 6.38f, right = 57.70f, bottom = 8.50f, book = 438),
    )
}

/**
 * A rectangular tap target bound to an app action, in RAW Ncode coordinates. The box is learned by
 * tracing the printed icon (so its extent is exact, not guessed). A printed icon sits at the same
 * place on every page of a notebook, so matching ignores the page id and works notebook-wide.
 */
@Serializable
data class ActionZone(
    val id: String,
    val action: ZoneAction,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** The Ncode book id this icon belongs to; 0 = legacy/any (matches every notebook). */
    val book: Int = 0,
) {
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

/**
 * The zone a tap at ([x],[y]) on notebook [book] hits. A book-scoped zone matches its own book; a
 * book-0 zone is legacy/any; and an unrecognized tap ([book] <= 0, before the pen locks onto the
 * Ncode pattern) falls back to position-only so a cold tap on a printed icon still fires.
 */
fun matchZone(zones: List<ActionZone>, book: Int, x: Float, y: Float): ActionZone? =
    zones.firstOrNull { (book <= 0 || it.book == 0 || it.book == book) && it.contains(x, y) }

/**
 * If [pts] is a "tap" — every point within [eps] of the others (a near-stationary press, not a
 * stroke of writing) — return its centre; otherwise null. Pure so it can be unit-tested.
 */
fun tapCentre(pts: List<Pair<Float, Float>>, eps: Float): Pair<Float, Float>? {
    if (pts.isEmpty()) return null
    val minX = pts.minOf { it.first }; val maxX = pts.maxOf { it.first }
    val minY = pts.minOf { it.second }; val maxY = pts.maxOf { it.second }
    if (maxX - minX > eps || maxY - minY > eps) return null
    return (minX + maxX) / 2f to (minY + maxY) / 2f
}

/** Bounding box of [pts] as [left, top, right, bottom], or null if empty (the traced calibration). */
fun boundsOf(pts: List<Pair<Float, Float>>): List<Float>? {
    if (pts.isEmpty()) return null
    return listOf(pts.minOf { it.first }, pts.minOf { it.second }, pts.maxOf { it.first }, pts.maxOf { it.second })
}
