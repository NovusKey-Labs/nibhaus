package com.nibhaus.capture

import com.nibhaus.export.PageGeometry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A normalised writable dot-area rectangle in raw Ncode units (what the corner taps capture). */
data class PageBounds(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

/** Two corner taps in any order → a normalised writable rectangle (min/max per axis). */
fun cornerBounds(ax: Float, ay: Float, bx: Float, by: Float): PageBounds =
    PageBounds(minOf(ax, bx), minOf(ay, by), maxOf(ax, bx), maxOf(ay, by))

/** mm per Ncode unit, derived from a line of known physical length; null if the span isn't usable. */
fun mmPerUnitOf(spanUnits: Float, knownMm: Float): Float? =
    if (spanUnits > 0f && knownMm > 0f) knownMm / spanUnits else null

/** Assemble a [PageGeometry] from captured writable bounds + the physical sheet size. */
fun assembleGeometry(bounds: PageBounds, sheetWmm: Float, sheetHmm: Float): PageGeometry =
    PageGeometry(bounds.x0, bounds.y0, bounds.x1, bounds.y1, sheetWmm, sheetHmm)

/**
 * A per-notebook profile produced on-device by the capture wizard, keyed by Ncode book id. Carries
 * the writable [geometry] and the per-notebook [mmPerUnit] scale today; cells / planner layout arrive
 * with later pieces. Persisted as JSON (see NotebookProfileStore).
 */
@Serializable
data class NotebookProfile(
    val bookId: Int,
    val geometry: PageGeometry? = null,
    val mmPerUnit: Float? = null,
)

/** A captured profile's geometry wins over the built-in [NotebookType] geometry. */
fun resolveGeometry(profile: NotebookProfile?, builtin: PageGeometry?): PageGeometry? =
    profile?.geometry ?: builtin

/** Serialize a profile for sharing (export to a file). */
fun encodeProfile(profile: NotebookProfile): String =
    Json.encodeToString(NotebookProfile.serializer(), profile)

/** Parse a shared profile (import), or null if the text isn't a valid profile. */
fun decodeProfile(json: String): NotebookProfile? =
    runCatching { Json.decodeFromString(NotebookProfile.serializer(), json) }.getOrNull()
