package com.nibhaus.export

/**
 * Physical page layout of a known notebook, in raw Ncode units, so the app can draw the *real* ruled
 * lines and page number over the ink (and in exports) instead of a generic decorative template. All
 * derived from the measured [PageGeometry] + [Ruling] via the same sheet framing the SVG export uses,
 * so screen and export agree. Pure — unit-tested without Compose. See the page-fidelity design spec.
 */

/**
 * A notebook product's printed ruling, in millimetres from the sheet edges. The first line sits [topMm]
 * below the top edge, the last [bottomMm] above the bottom edge, with [lineCount] lines evenly spaced
 * between — so the pitch is *derived* from the exact (countable) line count, not assumed. [sideMm] insets
 * the rules from each side.
 */
data class Ruling(val topMm: Float, val bottomMm: Float, val lineCount: Int, val sideMm: Float)

/**
 * How a page within a notebook is ruled — a notebook is NOT uniform (e.g. Professional: 001 is a cover,
 * 002–129 are fully lined, 130–256 keep only the bottom line). Page number + action buttons follow:
 * present on [LINED] and [FOOTER_ONLY], absent on [COVER].
 */
enum class PageStyle { COVER, LINED, FOOTER_ONLY }

/** A contiguous run of page numbers [from]..[to] (inclusive) that share a [style]. */
data class PageBand(val from: Int, val to: Int, val style: PageStyle)

/** The style for [page] from a notebook's [bands]; defaults to [PageStyle.LINED] when no band matches
 *  (the common writing page), so an unmeasured/out-of-range page still renders sensibly. */
fun pageStyleFor(bands: List<PageBand>, page: Int): PageStyle =
    bands.firstOrNull { page in it.from..it.to }?.style ?: PageStyle.LINED

/**
 * The sheet's extent in raw Ncode units: origin [x0],[y0] and size [wUnits]×[hUnits], with the writable
 * dot area centred inside it (per-axis symmetric border) — the framing `ExportArtifacts.renderSvg` uses.
 */
data class SheetFrame(val x0: Float, val y0: Float, val wUnits: Float, val hUnits: Float)

fun sheetFrame(geometry: PageGeometry): SheetFrame {
    val u = ExportArtifacts.MM_PER_UNIT
    val wUnits = geometry.pageWidthMm / u
    val hUnits = geometry.pageHeightMm / u
    val x0 = geometry.writableX0 - (wUnits - (geometry.writableX1 - geometry.writableX0)) / 2f
    val y0 = geometry.writableY0 - (hUnits - (geometry.writableY1 - geometry.writableY0)) / 2f
    return SheetFrame(x0, y0, wUnits, hUnits)
}

/** Ruled-line Y positions in Ncode units, top→bottom: [Ruling.lineCount] lines evenly spread from the
 *  top margin (first line) to the bottom margin (last line). Empty if the count is non-positive. */
fun rulingLinesUnits(geometry: PageGeometry, ruling: Ruling): List<Float> {
    if (ruling.lineCount <= 0) return emptyList()
    // Pen-traced anchors win over any frame derivation: first/last rule at their REAL Ncode Y.
    geometry.anchors?.let { a ->
        if (ruling.lineCount == 1) return listOf(a.firstY)
        val step = (a.lastY - a.firstY) / (ruling.lineCount - 1)
        return List(ruling.lineCount) { a.firstY + it * step }
    }
    val u = ExportArtifacts.MM_PER_UNIT
    val f = sheetFrame(geometry)
    val first = f.y0 + ruling.topMm / u
    if (ruling.lineCount == 1) return listOf(first)
    val last = f.y0 + f.hUnits - ruling.bottomMm / u
    val step = (last - first) / (ruling.lineCount - 1)
    return List(ruling.lineCount) { first + it * step }
}

/** The (left, right) Ncode X the rules span — the sheet inset by the side margin on each edge. */
fun rulingSideXUnits(geometry: PageGeometry, ruling: Ruling): Pair<Float, Float> {
    geometry.anchors?.let { return it.leftX to it.rightX } // traced rule extent
    val u = ExportArtifacts.MM_PER_UNIT
    val f = sheetFrame(geometry)
    return (f.x0 + ruling.sideMm / u) to (f.x0 + f.wUnits - ruling.sideMm / u)
}

/** The printed page number: 3-digit zero-padded (page 36 → "036"). */
fun pageNumberText(page: Int): String = "%03d".format(page)

/**
 * Bottom-left anchor of the printed page number in Ncode units: left edge flush with the ruling's left
 * inset, vertical centre [centerFromBottomMm] above the sheet bottom.
 */
fun pageNumberAnchorUnits(
    geometry: PageGeometry,
    ruling: Ruling,
    centerFromBottomMm: Float = 6.5f,
): Pair<Float, Float> {
    // Anchored: place relative to the traced last rule using the trace-derived units-per-mm scale.
    geometry.anchors?.let { a ->
        val upm = (a.lastY - a.firstY) / (geometry.pageHeightMm - ruling.topMm - ruling.bottomMm)
        return a.leftX to (a.lastY + (ruling.bottomMm - centerFromBottomMm) * upm)
    }
    val u = ExportArtifacts.MM_PER_UNIT
    val f = sheetFrame(geometry)
    return (f.x0 + ruling.sideMm / u) to (f.y0 + f.hUnits - centerFromBottomMm / u)
}
