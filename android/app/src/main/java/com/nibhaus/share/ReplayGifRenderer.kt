package com.nibhaus.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.nibhaus.data.Point
import com.nibhaus.data.StrokeEntity
import com.nibhaus.ui.buildReplayTimeline
import com.nibhaus.ui.pointsUpTo
import com.nibhaus.ui.replayFrameAt

/**
 * Renders a page's Handwriting Replay as a sequence of bitmap frames for GIF export. Mirrors
 * [PageRender.renderPage]'s fit-and-draw exactly (white background, same `strokeOutline` filled-path
 * ink, color-preserved: `s.color != 0 ? s.color : BLACK`) but the fit is computed ONCE from the FULL
 * page's ink bounds — like [com.nibhaus.ui.drawReplayFrame] on-screen — so frames don't rescale as
 * more ink is revealed, and each frame draws only the strokes/partial stroke [replayFrameAt] says are
 * visible at that frame's timeline position (from [replayGifFramePositions]).
 */
object ReplayGifRenderer {

    /** Cap on exported frame count — smooth enough without an oversized GIF. */
    const val MAX_FRAMES = 48

    /** Cap on the output's longest side, in px — keeps the GIF file size sane. */
    const val MAX_DIMENSION = 600

    /** Renders up to [frameCount] (capped at [MAX_FRAMES]) frames across the page's replay timeline.
     *  Empty when there's no ink to render. First frame is ~blank, last is the fully-drawn page. */
    fun renderFrames(
        strokes: List<StrokeEntity>,
        pointsOf: (StrokeEntity) -> List<Point>,
        frameCount: Int = MAX_FRAMES,
        strokeScale: Float = 1f, // the Fine/Normal/Bold handwriting-size preset (#15b) — see PageRender.renderPage.
    ): List<Bitmap> {
        if (strokes.isEmpty()) return emptyList()
        val all = strokes.map(pointsOf)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        all.forEach { it.forEach { p -> minX = minOf(minX, p.x); minY = minOf(minY, p.y); maxX = maxOf(maxX, p.x); maxY = maxOf(maxY, p.y) } }
        if (minX > maxX || minY > maxY) return emptyList() // nothing to render

        val timeline = buildReplayTimeline(strokes, pointsOf)
        val n = frameCount.coerceIn(1, MAX_FRAMES)
        val positions = replayGifFramePositions(timeline.totalMs, n)

        val spanX = (maxX - minX).coerceAtLeast(1e-3f)
        val spanY = (maxY - minY).coerceAtLeast(1e-3f)
        val (width, height) = fitDimensions(spanX, spanY)

        val pad = 0.06f
        val scale = minOf(width * (1 - 2 * pad) / spanX, height * (1 - 2 * pad) / spanY)
        val offX = (width - spanX * scale) / 2f
        val offY = (height - spanY * scale) / 2f
        val baseWidth = width * 0.0025f * strokeScale
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        // Perf audit P1-3: a finished stroke's points and fit (scale/offX/offY, computed once above
        // from the full page) never change across frames, so its tessellated outline is identical in
        // every frame it appears in — tessellate each one once here and reuse the Path, instead of
        // re-running strokeOutline() for it on all MAX_FRAMES frames. Keyed by uuid only (not fit,
        // unlike the on-screen StrokeRenderCache): this cache is local to one renderFrames() call,
        // where the fit is fixed for its whole lifetime.
        val donePaths = HashMap<String, Path>()

        fun tessellate(pts: List<Point>, width: Float): Path? {
            if (pts.isEmpty()) return null
            val xs = FloatArray(pts.size) { (pts[it].x - minX) * scale + offX }
            val ys = FloatArray(pts.size) { (pts[it].y - minY) * scale + offY }
            val pr = FloatArray(pts.size) { pts[it].pressure }
            val o = com.nibhaus.ink.strokeOutline(xs, ys, pr, width)
            if (o.size < 4) return null
            val path = Path()
            path.moveTo(o[0], o[1])
            var j = 2
            while (j < o.size) { path.lineTo(o[j], o[j + 1]); j += 2 }
            path.close()
            return path
        }

        fun render(frame: com.nibhaus.ui.ReplayFrame): Bitmap {
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)

            frame.doneStrokes.forEach { rs ->
                if (rs.points.isEmpty()) return@forEach
                val path = donePaths.getOrPut(rs.stroke.uuid) {
                    tessellate(rs.points, baseWidth * rs.stroke.width) ?: return@forEach
                }
                paint.color = if (rs.stroke.color != 0) rs.stroke.color else Color.BLACK
                canvas.drawPath(path, paint)
            }
            // The in-progress stroke's revealed length changes every frame, so it's always fresh.
            frame.activeStroke?.let { rs ->
                val pts = pointsUpTo(rs, frame.activeFraction)
                val path = tessellate(pts, baseWidth * rs.stroke.width) ?: return@let
                paint.color = if (rs.stroke.color != 0) rs.stroke.color else Color.BLACK
                canvas.drawPath(path, paint)
            }
            return bmp
        }

        return positions.map { posMs -> render(replayFrameAt(timeline, posMs)) }
    }

    /** Output dims keeping the page's aspect ratio, longest side <= [MAX_DIMENSION]. */
    private fun fitDimensions(spanX: Float, spanY: Float): Pair<Int, Int> {
        val aspect = spanX / spanY
        return if (aspect >= 1f) {
            MAX_DIMENSION to (MAX_DIMENSION / aspect).toInt().coerceAtLeast(1)
        } else {
            (MAX_DIMENSION * aspect).toInt().coerceAtLeast(1) to MAX_DIMENSION
        }
    }
}
