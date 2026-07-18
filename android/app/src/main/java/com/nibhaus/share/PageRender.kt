package com.nibhaus.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.nibhaus.data.Point
import com.nibhaus.data.StrokeEntity
import com.nibhaus.export.ExportArtifacts
import com.nibhaus.export.PageGeometry
import com.nibhaus.export.PageStyle
import com.nibhaus.export.PaperTemplate
import com.nibhaus.export.Ruling
import com.nibhaus.export.pageNumberAnchorUnits
import com.nibhaus.export.pageNumberText
import com.nibhaus.export.paperRowPositions
import com.nibhaus.export.rulingLinesUnits
import com.nibhaus.export.rulingSideXUnits

/**
 * White-background, auto-fit raster of a page's ink (A4 portrait), always light-mode — shared by
 * Print and the zone-triggered Share/Email. Default ink (color 0) prints black; colored ink prints
 * solid. Returns null when there's nothing to render.
 */
object PageRender {

    fun renderPage(
        strokes: List<StrokeEntity>,
        points: (StrokeEntity) -> List<Point>,
        width: Int = 1240,   // ~150dpi A4 portrait
        height: Int = 1754,
        bounds: PageGeometry? = null,
        template: PaperTemplate = PaperTemplate.BLANK, // BLANK keeps exports/prints on clean white
        ruling: Ruling? = null,            // calibrated printed ruling — replaces the generic template
        pageNumber: Int? = null,           // printed 3-digit page number, when known
        pageStyle: PageStyle = PageStyle.LINED,
        blackInk: Boolean = false,         // print/share every stroke black regardless of its color
        strokeScale: Float = 1f,           // the Fine/Normal/Bold handwriting-size preset (#15b) —
                                            // matches InkSurface.strokeBaseWidthPx so exports/shares
                                            // look like what's on screen, not always the Normal width.
    ): Bitmap? {
        val all = strokes.map(points)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        all.forEach { it.forEach { p -> minX = minOf(minX, p.x); minY = minOf(minY, p.y); maxX = maxOf(maxX, p.x); maxY = maxOf(maxY, p.y) } }
        if (minX > maxX || minY > maxY) return null // nothing to render
        // Frame the WHOLE page when the notebook's geometry is known, so a small drawing renders at
        // its true place on a full page instead of zoomed to its own ink bounds.
        if (bounds != null) {
            minX = bounds.writableX0; minY = bounds.writableY0; maxX = bounds.writableX1; maxY = bounds.writableY1
        }

        val pad = 0.06f
        val spanX = (maxX - minX).coerceAtLeast(1e-3f)
        val spanY = (maxY - minY).coerceAtLeast(1e-3f)
        val scale = minOf(width * (1 - 2 * pad) / spanX, height * (1 - 2 * pad) / spanY)
        val offX = (width - spanX * scale) / 2f
        val offY = (height - spanY * scale) / 2f

        // this ran ARGB_8888 (~8.7MB at the default 1240x1754), one bitmap per page
        // per export, with reexportAllPages() looping it across the whole notebook. RGB_565 halves
        // that (~4.2MB) and is safe here: the page is always fully opaque (white background filled
        // below, ink drawn solid on top — never a transparent pixel), so the dropped alpha channel is
        // never used downstream (PNG/PDF encode and PrintHelper.printBitmap all consume this bitmap
        // fine without it). The only real tradeoff is RGB_565's coarser 16-bit palette on the
        // antialiased edges (stroke boundaries, the faint calibrated ruling); at print/export
        // resolution those are 1-2px flat-color transitions, not the smooth multi-pixel gradients
        // where 565 banding actually becomes visible, so this is judged worth it everywhere this
        // renders (print included) rather than keeping two code paths.
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        // The REAL printed page (calibrated ruling + page number) when the notebook is known —
        // mirrors the on-screen calibrated paper, which suppresses the decorative template.
        if (bounds != null && ruling != null && pageStyle != PageStyle.COVER) {
            drawCalibratedPaper(canvas, bounds, ruling, pageNumber, pageStyle, width, minX, minY, scale, offX, offY)
        } else {
            drawPaperTemplate(canvas, template, width, height)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val baseWidth = width * 0.0025f * strokeScale
        strokes.forEachIndexed { i, s ->
            val raw = all[i]
            if (raw.isEmpty()) return@forEachIndexed
            paint.color = if (blackInk || s.color == 0) Color.BLACK else s.color
            // Same pressure-tapered outline as on screen, filled.
            val xs = FloatArray(raw.size) { (raw[it].x - minX) * scale + offX }
            val ys = FloatArray(raw.size) { (raw[it].y - minY) * scale + offY }
            val pr = FloatArray(raw.size) { raw[it].pressure }
            val o = com.nibhaus.ink.strokeOutline(xs, ys, pr, baseWidth * s.width)
            if (o.size < 4) return@forEachIndexed
            val path = Path()
            path.moveTo(o[0], o[1])
            var j = 2
            while (j < o.size) { path.lineTo(o[j], o[j + 1]); j += 2 }
            path.close()
            canvas.drawPath(path, paint)
        }
        return bmp
    }

    /**
     * The notebook's REAL printed ruling + 3-digit page number, mapped through the SAME transform as
     * the ink (minX/minY/scale/offX/offY), so paper and ink line up exactly like the physical page —
     * the raster twin of Screens.kt's drawCalibratedPaper.
     */
    private fun drawCalibratedPaper(
        canvas: Canvas,
        geometry: PageGeometry,
        ruling: Ruling,
        pageNumber: Int?,
        pageStyle: PageStyle,
        width: Int,
        minX: Float,
        minY: Float,
        scale: Float,
        offX: Float,
        offY: Float,
    ) {
        fun mx(x: Float) = (x - minX) * scale + offX
        fun my(y: Float) = (y - minY) * scale + offY
        val rows = rulingLinesUnits(geometry, ruling)
        val draw = if (pageStyle == PageStyle.FOOTER_ONLY) listOfNotNull(rows.lastOrNull()) else rows
        val (lx, rx) = rulingSideXUnits(geometry, ruling)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(46, 0, 0, 0)
            style = Paint.Style.STROKE
            strokeWidth = width * 0.001f
        }
        for (yu in draw) canvas.drawLine(mx(lx), my(yu), mx(rx), my(yu), p)
        if (pageNumber != null) {
            val (ax, ay) = pageNumberAnchorUnits(geometry, ruling)
            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(140, 0, 0, 0)
                typeface = Typeface.MONOSPACE
                // 1 mm glyph height, via the same mm→unit→px chain as the on-screen page number.
                textSize = (1f / ExportArtifacts.MM_PER_UNIT) * scale / 0.7f
            }
            canvas.drawText(pageNumberText(pageNumber), mx(ax), my(ay) + t.textSize * 0.35f, t)
        }
    }

    /** Faint paper template behind the ink, matching the on-screen look (light-gray on white). */
    private fun drawPaperTemplate(canvas: Canvas, template: PaperTemplate, width: Int, height: Int) {
        if (template == PaperTemplate.BLANK) return
        val spacing = height * 0.028f          // ~ the on-screen 15dp lattice at this raster size
        val offset = spacing
        val dotR = width * 0.0011f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(30, 0, 0, 0) }
        val ys = paperRowPositions(height.toFloat(), spacing, offset)
        when (template) {
            PaperTemplate.DOT_GRID -> {
                p.style = Paint.Style.FILL
                val xs = paperRowPositions(width.toFloat(), spacing, offset)
                for (y in ys) for (x in xs) canvas.drawCircle(x, y, dotR, p)
            }
            PaperTemplate.LINED -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = width * 0.001f
                for (y in ys) canvas.drawLine(0f, y, width.toFloat(), y, p)
            }
            PaperTemplate.BLANK -> {}
        }
    }
}
