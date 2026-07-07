package com.nibhaus.ui

import com.nibhaus.data.Point
import com.nibhaus.data.StrokeEntity

/**
 * Handwriting Replay's pure timeline model — no Compose/Android here so it's plain-JVM testable.
 * Strokes carry their own timestamps ([Point.t], epoch ms); replay is driven by that, independent
 * of any voice recording (contrast with [com.nibhaus.audio.Pencast], which is audio-coupled).
 *
 * A page can be written over hours with long idle gaps between strokes, so the raw wall-clock
 * timeline isn't watchable. [buildReplayTimeline] compresses any inter-stroke idle gap down to
 * [REPLAY_MAX_GAP_MS] while preserving within-stroke timing (and short gaps) untouched, so the
 * replay plays back in roughly the time it took to actually draw each stroke, with dead air capped.
 */
private const val REPLAY_MAX_GAP_MS = 700L

/** One stroke placed on the compressed replay timeline, with its points for rendering. */
data class ReplayStroke(
    val stroke: StrokeEntity,
    val points: List<Point>,
    val startMs: Long,
    val endMs: Long,
)

/** The whole page laid out on a compressed timeline, strokes ordered by first-point time. */
data class ReplayTimeline(val totalMs: Long, val strokes: List<ReplayStroke>)

/** What to render at a given playback position: strokes drawn in full, plus the one stroke
 *  currently being drawn (if any) and how far through it we are (0f..1f, by point time). */
data class ReplayFrame(
    val doneStrokes: List<ReplayStroke>,
    val activeStroke: ReplayStroke?,
    val activeFraction: Float,
)

/**
 * Builds a [ReplayTimeline] from [strokes], resolving each stroke's points via [pointsOf] (mirrors
 * how `drawStrokes` resolves points via `vm::strokesFlowOf`). Strokes with no points are dropped
 * (nothing to replay). Idle gaps between strokes over [maxGapMs] are compressed to [maxGapMs].
 */
fun buildReplayTimeline(
    strokes: List<StrokeEntity>,
    pointsOf: (StrokeEntity) -> List<Point>,
    maxGapMs: Long = REPLAY_MAX_GAP_MS,
): ReplayTimeline {
    val withPoints = strokes
        .map { it to pointsOf(it) }
        .filter { it.second.isNotEmpty() }
        .sortedBy { it.second.first().t }
    if (withPoints.isEmpty()) return ReplayTimeline(0L, emptyList())

    val out = ArrayList<ReplayStroke>(withPoints.size)
    var cursor = 0L
    var prevRealEnd: Long? = null
    for ((stroke, points) in withPoints) {
        val realStart = points.first().t
        val realEnd = points.last().t
        val gap = prevRealEnd?.let { (realStart - it).coerceAtLeast(0L) } ?: 0L
        cursor += gap.coerceAtMost(maxGapMs)
        val startMs = cursor
        val endMs = startMs + (realEnd - realStart).coerceAtLeast(0L)
        out.add(ReplayStroke(stroke, points, startMs, endMs))
        cursor = endMs
        prevRealEnd = realEnd
    }
    return ReplayTimeline(cursor, out)
}

/**
 * Maps a playback position (clamped to `0..timeline.totalMs`) to what should be visible: every
 * stroke whose window has fully elapsed, plus the single stroke currently mid-draw (if the
 * position falls inside its window) with its progress fraction. A position before a stroke's
 * window, or after all windows, yields no active stroke.
 */
fun replayFrameAt(timeline: ReplayTimeline, positionMs: Long): ReplayFrame {
    val pos = positionMs.coerceIn(0L, timeline.totalMs)
    val done = ArrayList<ReplayStroke>()
    var active: ReplayStroke? = null
    var fraction = 0f
    for (s in timeline.strokes) {
        when {
            pos >= s.endMs -> done.add(s)
            pos < s.startMs -> Unit // not reached yet
            else -> {
                active = s
                val dur = s.endMs - s.startMs
                fraction = if (dur <= 0L) 1f else ((pos - s.startMs).toFloat() / dur).coerceIn(0f, 1f)
            }
        }
    }
    return ReplayFrame(done, active, fraction)
}

/**
 * [stroke]'s own points, truncated to [fraction] (0f..1f) of its duration by point timestamp —
 * the partial path drawn for the in-progress stroke. Always at least the first point once
 * fraction > 0; the full list at fraction >= 1.
 */
fun pointsUpTo(stroke: ReplayStroke, fraction: Float): List<Point> {
    val pts = stroke.points
    if (pts.isEmpty()) return pts
    if (fraction <= 0f) return listOf(pts.first())
    if (fraction >= 1f) return pts
    val cutT = pts.first().t + ((pts.last().t - pts.first().t) * fraction).toLong()
    val idx = pts.indexOfLast { it.t <= cutT }.coerceAtLeast(0)
    return pts.subList(0, idx + 1)
}
