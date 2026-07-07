package com.nibhaus.export

import com.nibhaus.data.PageEntity
import com.nibhaus.data.Point
import com.nibhaus.data.StrokeEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Sidecar metadata written next to each export, consumed by the NAS-watcher OCR mode (Phase 4) and
 * by anything indexing the folder. Fields per DESIGN.md: pageId, notebook/Ncode id, pen id, capture
 * timestamp — plus what's needed to make export idempotent and auditable.
 */
@Serializable
data class ExportSidecar(
    val pageId: String,
    val notebookNcodeId: String,
    val penId: String,
    val captureTimestamp: Long,
    val strokeCount: Int,
    val contentHash: String,
    val formats: List<String>,
    val exportedAt: Long,
    // Provenance + physical scale for the vault / OCR watcher (added; defaults keep old readers happy).
    val notebookType: String? = null,   // NotebookType.id, e.g. "professional" (null = unrecognised)
    val notebookLabel: String? = null,  // the per-notebook label (Work, School)
    val mmPerUnit: Float = 0f,           // Ncode-unit → mm, so consumers can size the page physically
)

/**
 * Turns a page's strokes into export artifacts. Pure (no Android, no I/O) so the export format is
 * unit-tested directly. SVG keeps the ink as vectors (DESIGN.md prefers SVG); the bytes are written
 * by whichever [StorageProvider] is selected. The raster formats (PNG for OCR, PDF as a portable
 * copy) are injected into [ExportEngine] by the app, since they need Android's Bitmap/PdfDocument.
 */
object ExportArtifacts {
    private val json = Json { prettyPrint = true }

    /**
     * Measured Ncode-unit → millimetre scale (the dot grid is isotropic). Originally set to 2.32 from
     * ruler traces (a "10 cm" trace that was actually 9 cm, giving 4.32 units/cm) cross-checked against
     * the Standard page's writable width. Corrected to 2.36 (2026-07-03): the ruler-measured printed
     * ruling pitch (7.0 mm/line, Neo N Professional) derives ~2.36 mm/unit — much closer to Neo's own
     * documented 56/600 inch (≈2.37 mm/unit) scale than the earlier estimate. Used to stamp true
     * physical dimensions on the exported SVG. Note: re-derive with
     * android/tools/calibrate_ncode.py if a pen/paper changes the scale; it prints MM_PER_UNIT.
     */
    const val MM_PER_UNIT = 2.36f

    /**
     * A stable hash of the page's content, so an unchanged page is never re-written. The transcript
     * is folded in so that importing an OCR result (which changes no strokes) still re-exports the
     * page, refreshing its Markdown note.
     */
    fun contentHash(strokes: List<StrokeEntity>, transcript: String? = null): String {
        val key = strokes.sortedBy { it.uuid }
            .joinToString("|") { "${it.uuid}:${it.endedAt}:${it.color}:${it.width}" } +
            "|t:" + (transcript?.hashCode() ?: 0)
        // Note: hashCode is plenty to detect "did this page change?"; swap to SHA-256 only if a
        // cryptographic guarantee is ever required.
        return Integer.toHexString(key.hashCode())
    }

    fun renderSvg(
        strokes: List<StrokeEntity>,
        decode: (StrokeEntity) -> List<Point>,
        geometry: PageGeometry? = null,
        ruling: Ruling? = null,            // the notebook's calibrated printed ruling, when known
        pageNumber: Int? = null,           // the printed 3-digit page number, when known
        pageStyle: PageStyle = PageStyle.LINED,
    ): String {
        val paths = StringBuilder()
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (s in strokes) {
            val pts = decode(s)
            if (pts.size < 2) continue
            for (p in pts) {
                if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x
                if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y
            }
            val coords = simplify(pts, SVG_SIMPLIFY_EPS).joinToString(" ") { "${fmt(it.x)},${fmt(it.y)}" }
            paths.append("  <polyline points=\"").append(coords)
                .append("\" fill=\"none\" stroke=\"").append(hexColor(s.color))
                .append("\" stroke-width=\"").append(fmt(0.3f * s.width))
                .append("\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>\n")
        }
        // Fall back to a nominal box when there's nothing to bound (e.g. all 1-point strokes).
        if (minX > maxX) { minX = 0f; minY = 0f; maxX = 100f; maxY = 100f }
        val pad = 1f
        // The viewBox stays in raw Ncode units (vectors byte-for-byte unchanged); the mm width/height
        // make it render at TRUE physical size. With a known notebook [geometry] the box is the WHOLE
        // sheet, so ink sits exactly where it physically is — the archival/full-page export. Otherwise
        // it's cropped to the ink's bounding box (Share/Print use that path). A 5 cm line prints 5 cm.
        val vb: String; val wMm: Float; val hMm: Float
        if (geometry != null) {
            val pageWu = geometry.pageWidthMm / MM_PER_UNIT
            val pageHu = geometry.pageHeightMm / MM_PER_UNIT
            // Centre the measured writable area within the sheet (per-axis symmetric border). Note:
            // if a notebook's binding margin differs left/right, store that offset rather than centring.
            val x0 = geometry.writableX0 - (pageWu - (geometry.writableX1 - geometry.writableX0)) / 2f
            val y0 = geometry.writableY0 - (pageHu - (geometry.writableY1 - geometry.writableY0)) / 2f
            vb = "${fmt(x0)} ${fmt(y0)} ${fmt(pageWu)} ${fmt(pageHu)}"
            wMm = geometry.pageWidthMm; hMm = geometry.pageHeightMm
        } else {
            val wUnits = maxX - minX + 2 * pad
            val hUnits = maxY - minY + 2 * pad
            vb = "${fmt(minX - pad)} ${fmt(minY - pad)} ${fmt(wUnits)} ${fmt(hUnits)}"
            wMm = wUnits * MM_PER_UNIT; hMm = hUnits * MM_PER_UNIT
        }
        // The REAL printed paper — calibrated rules + page number — emitted before the ink so it sits
        // underneath, mirroring the on-screen calibrated paper (COVER pages carry neither).
        val paper = StringBuilder()
        if (geometry != null && ruling != null && pageStyle != PageStyle.COVER) {
            val rows = rulingLinesUnits(geometry, ruling)
            val draw = if (pageStyle == PageStyle.FOOTER_ONLY) listOfNotNull(rows.lastOrNull()) else rows
            val (lx, rx) = rulingSideXUnits(geometry, ruling)
            for (y in draw) {
                paper.append("  <line x1=\"").append(fmt(lx)).append("\" y1=\"").append(fmt(y))
                    .append("\" x2=\"").append(fmt(rx)).append("\" y2=\"").append(fmt(y))
                    .append("\" stroke=\"#00000022\" stroke-width=\"0.15\"/>\n")
            }
            if (pageNumber != null) {
                val (ax, ay) = pageNumberAnchorUnits(geometry, ruling)
                val fs = 1f / MM_PER_UNIT // 1 mm glyph height in Ncode user units, as on the page
                paper.append("  <text x=\"").append(fmt(ax)).append("\" y=\"").append(fmt(ay + fs * 0.35f))
                    .append("\" font-family=\"monospace\" font-size=\"").append(fmt(fs))
                    .append("\" fill=\"#00000088\">").append(pageNumberText(pageNumber)).append("</text>\n")
            }
        }
        return buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"")
                .append(fmt(wMm)).append("mm\" height=\"")
                .append(fmt(hMm)).append("mm\" viewBox=\"").append(vb).append("\">\n")
            append(paper)
            append(paths)
            append("</svg>\n")
        }
    }

    /**
     * InkML (W3C) — the platform-neutral *online* ink format: per-stroke traces carrying X, Y,
     * pressure (F) and time (T). This is what an online-HTR / OCR model wants (stroke order + timing
     * beat a flat image), and it makes the captured ink portable to other tools. Pure string build.
     */
    fun renderInkML(
        strokes: List<StrokeEntity>,
        decode: (StrokeEntity) -> List<Point>,
        notebookType: String? = null,
        label: String? = null,
    ): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<ink xmlns=\"http://www.w3.org/2003/InkML\">\n")
        append("  <definitions>\n    <context xml:id=\"ctx0\">\n")
        // Self-describing physical scale + provenance, so the online-ink file stands alone for HTR.
        // X/Y are in Ncode units; 1 unit = MM_PER_UNIT mm (isotropic).
        append("      <annotation type=\"mmPerUnit\">").append(MM_PER_UNIT).append("</annotation>\n")
        if (!notebookType.isNullOrBlank()) {
            append("      <annotation type=\"notebookType\">").append(notebookType).append("</annotation>\n")
        }
        if (!label.isNullOrBlank()) {
            append("      <annotation type=\"label\">").append(label).append("</annotation>\n")
        }
        append("      <inkSource xml:id=\"src0\">\n")
        append("        <traceFormat>\n")
        append("          <channel name=\"X\" type=\"decimal\"/>\n")
        append("          <channel name=\"Y\" type=\"decimal\"/>\n")
        append("          <channel name=\"F\" type=\"decimal\"/>\n")
        append("          <channel name=\"T\" type=\"integer\"/>\n")
        append("        </traceFormat>\n      </inkSource>\n    </context>\n  </definitions>\n")
        for (s in strokes) {
            val pts = decode(s)
            if (pts.isEmpty()) continue
            append("  <trace>")
            append(pts.joinToString(", ") { "${fmt(it.x)} ${fmt(it.y)} ${fmt(it.pressure)} ${it.t}" })
            append("</trace>\n")
        }
        append("</ink>\n")
    }

    /**
     * Obsidian/Markdown note for the page: YAML frontmatter (ids + capture time, for Dataview-style
     * queries), an embed of the page raster, and the OCR transcript as searchable body text. Pairs
     * with the `.png` written beside it so a synced vault renders the handwriting and its
     * transcription together; re-exported (new hash) whenever a transcript lands.
     */
    fun renderMarkdown(
        pageId: String,
        page: PageEntity?,
        transcript: String?,
        imageName: String = "$pageId.png",
        notebookType: String? = null,
        label: String? = null,
        tags: List<String> = emptyList(),
    ): String = buildString {
        append("---\n")
        append("pageId: ").append(pageId).append('\n')
        append("ncode: ").append(page?.addressKey ?: "").append('\n')
        append("notebook: ").append(page?.notebookId ?: "").append('\n')
        // Optional, queryable in an Obsidian/Dataview vault; emitted only when known so the default
        // output (and existing tests) are unchanged.
        if (!label.isNullOrBlank()) append("label: ").append(label).append('\n')
        if (!notebookType.isNullOrBlank()) append("type: ").append(notebookType).append('\n')
        append("page: ").append(page?.page ?: "").append('\n')
        append("captured: ").append(page?.lastInkAt ?: 0L).append('\n')
        if (tags.isNotEmpty()) append("tags: [").append(tags.joinToString(", ")).append("]\n")
        append("---\n\n")
        append("![[").append(imageName).append("]]\n\n")
        // Links to the page's vector + online-ink artifacts (same basename, same folder).
        val base = imageName.removeSuffix(".png")
        append("[vector](").append(base).append(".svg) · [online ink](").append(base).append(".inkml)\n\n")
        append(transcript?.takeIf { it.isNotBlank() } ?: "*(not yet transcribed)*").append('\n')
    }

    fun sidecarJson(
        pageId: String,
        page: PageEntity?,
        penId: String,
        strokeCount: Int,
        contentHash: String,
        exportedAt: Long,
        formats: List<String> = emptyList(),
        notebookType: String? = null,
        notebookLabel: String? = null,
        mmPerUnit: Float = MM_PER_UNIT,
    ): String = json.encodeToString(
        ExportSidecar(
            pageId = pageId,
            notebookNcodeId = page?.addressKey ?: "",
            penId = penId,
            captureTimestamp = page?.lastInkAt ?: exportedAt,
            strokeCount = strokeCount,
            contentHash = contentHash,
            formats = formats,
            exportedAt = exportedAt,
            notebookType = notebookType,
            notebookLabel = notebookLabel,
            mmPerUnit = mmPerUnit,
        ),
    )

    // Drop points that lie within this many Ncode units of the line they'd interpolate — shrinks the
    // exported SVG without visibly changing the ink (~0.35 mm at 2.36 mm/unit). Endpoints are always kept.
    private const val SVG_SIMPLIFY_EPS = 0.15f

    /** Ramer–Douglas–Peucker polyline simplification. Pure; keeps the first and last point. */
    internal fun simplify(pts: List<Point>, eps: Float): List<Point> {
        if (pts.size < 3) return pts
        val keep = BooleanArray(pts.size)
        keep[0] = true; keep[pts.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>().apply { addLast(0 to pts.size - 1) }
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var maxD = 0f; var idx = -1
            for (i in a + 1 until b) {
                val d = perpDistance(pts[i], pts[a], pts[b])
                if (d > maxD) { maxD = d; idx = i }
            }
            if (idx != -1 && maxD > eps) {
                keep[idx] = true
                stack.addLast(a to idx); stack.addLast(idx to b)
            }
        }
        return pts.filterIndexed { i, _ -> keep[i] }
    }

    /** Perpendicular distance from [p] to the line through [a]–[b] (|cross| / |ab|). */
    private fun perpDistance(p: Point, a: Point, b: Point): Float {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (len == 0f) return hypot(p.x - a.x, p.y - a.y)
        return abs(dx * (a.y - p.y) - (a.x - p.x) * dy) / len
    }

    private fun hexColor(c: Int): String = "#%06X".format(0xFFFFFF and c)

    // Trim trailing ".0" so the SVG stays small and stable across runs.
    private fun fmt(v: Float): String =
        if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()
}

/**
 * Physical geometry of a known Ncode notebook, so the archival SVG can place ink at its true
 * position on the full sheet. The writable (dot) area is measured in Ncode units from corner/edge
 * traces; the sheet size is the physical paper. An unknown book → null → the SVG falls back to
 * ink-cropped. Note: add one row per notebook as each is measured (tools/calibrate_ncode.py).
 */
/** Pen-traced Ncode anchors of the printed ruling (measured, not derived): the first/last rule's
 *  Y and the rules' X extent. Kills the "dot area is centered in the page" assumption — rules and
 *  ink share the same traced coordinate space, so alignment is exact. */
@kotlinx.serialization.Serializable
data class RulingAnchors(val firstY: Float, val lastY: Float, val leftX: Float, val rightX: Float)

@kotlinx.serialization.Serializable
data class PageGeometry(
    val writableX0: Float,
    val writableY0: Float,
    val writableX1: Float,
    val writableY1: Float,
    val pageWidthMm: Float,
    val pageHeightMm: Float,
    val anchors: RulingAnchors? = null, // default keeps old persisted profiles parseable
) {
    companion object {
        /** Geometry for a notebook's Ncode book id — the measured layout lives on [NotebookType]. */
        fun forBook(book: Int?): PageGeometry? = NotebookType.forBook(book)?.geometry
    }
}
