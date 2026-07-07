package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.data.Point
import com.nibhaus.data.StrokeEntity
import com.nibhaus.data.SyncState
import com.nibhaus.ui.ReplayStroke
import com.nibhaus.ui.buildReplayTimeline
import com.nibhaus.ui.pointsUpTo
import com.nibhaus.ui.replayFrameAt
import org.junit.Test

/**
 * Handwriting Replay's pure timeline model: gap compression + position→visible-strokes mapping.
 * No Compose/Android involved — [com.nibhaus.ui.ReplayTimeline] is plain-JVM logic.
 */
class ReplayTimelineTest {

    private fun pt(t: Long) = Point(0f, 0f, 1f, t)

    private fun stroke(uuid: String, points: List<Point>) = StrokeEntity(
        uuid = uuid,
        pageId = "p",
        color = 0,
        startedAt = points.firstOrNull()?.t ?: 0L,
        endedAt = points.lastOrNull()?.t ?: 0L,
        pointsJson = "[]",
        syncState = SyncState.PENDING,
    )

    private fun pointsMap(vararg entries: Pair<StrokeEntity, List<Point>>): (StrokeEntity) -> List<Point> {
        val map = entries.associate { it.first.uuid to it.second }
        return { s -> map[s.uuid].orEmpty() }
    }

    // ── buildReplayTimeline ──────────────────────────────────────────────────────────────────

    @Test fun emptyStrokes_yieldsEmptyTimeline() {
        val timeline = buildReplayTimeline(emptyList(), pointsMap())
        assertThat(timeline.totalMs).isEqualTo(0L)
        assertThat(timeline.strokes).isEmpty()
    }

    @Test fun singleStroke_startsAtZero_totalIsItsDuration() {
        val s1 = stroke("s1", listOf(pt(1_000), pt(1_300)))
        val timeline = buildReplayTimeline(listOf(s1), pointsMap(s1 to listOf(pt(1_000), pt(1_300))))
        assertThat(timeline.strokes).hasSize(1)
        assertThat(timeline.strokes[0].startMs).isEqualTo(0L)
        assertThat(timeline.strokes[0].endMs).isEqualTo(300L)
        assertThat(timeline.totalMs).isEqualTo(300L)
    }

    @Test fun strokeWithNoPoints_isDropped() {
        val s1 = stroke("s1", listOf(pt(0), pt(100)))
        val empty = stroke("empty", emptyList())
        val timeline = buildReplayTimeline(
            listOf(s1, empty),
            pointsMap(s1 to listOf(pt(0), pt(100)), empty to emptyList()),
        )
        assertThat(timeline.strokes.map { it.stroke.uuid }).containsExactly("s1")
    }

    @Test fun strokesOutOfOrderInput_areOrderedByFirstPointTime() {
        val s1 = listOf(pt(0), pt(100))       // earlier
        val s2 = listOf(pt(5_000), pt(5_100)) // later
        val a = stroke("a", s2)
        val b = stroke("b", s1)
        // Passed in reverse chronological order.
        val timeline = buildReplayTimeline(listOf(a, b), pointsMap(a to s2, b to s1))
        assertThat(timeline.strokes.map { it.stroke.uuid }).containsExactly("b", "a").inOrder()
    }

    @Test fun bigIdleGap_isCappedToMaxGap() {
        val s1pts = listOf(pt(0), pt(200))           // duration 200
        val s2pts = listOf(pt(50_200), pt(50_350))   // 50s gap after s1 ends, duration 150
        val s1 = stroke("s1", s1pts)
        val s2 = stroke("s2", s2pts)
        val timeline = buildReplayTimeline(listOf(s1, s2), pointsMap(s1 to s1pts, s2 to s2pts), maxGapMs = 700L)

        assertThat(timeline.strokes[0].startMs).isEqualTo(0L)
        assertThat(timeline.strokes[0].endMs).isEqualTo(200L)
        // Gap capped at 700ms instead of the real 50_000ms.
        assertThat(timeline.strokes[1].startMs).isEqualTo(900L)
        assertThat(timeline.strokes[1].endMs).isEqualTo(1_050L)
        assertThat(timeline.totalMs).isEqualTo(1_050L)
    }

    @Test fun shortGap_isPreservedUncompressed() {
        val s1pts = listOf(pt(0), pt(100))     // duration 100
        val s2pts = listOf(pt(400), pt(450))   // 300ms gap after s1 ends, duration 50
        val s1 = stroke("s1", s1pts)
        val s2 = stroke("s2", s2pts)
        val timeline = buildReplayTimeline(listOf(s1, s2), pointsMap(s1 to s1pts, s2 to s2pts), maxGapMs = 700L)

        assertThat(timeline.strokes[1].startMs).isEqualTo(400L) // 100 + 300, untouched
        assertThat(timeline.strokes[1].endMs).isEqualTo(450L)
        assertThat(timeline.totalMs).isEqualTo(450L)
    }

    // ── replayFrameAt ────────────────────────────────────────────────────────────────────────

    private fun threeStrokeTimeline(): com.nibhaus.ui.ReplayTimeline {
        // s1: 0..100, short 50ms gap, s2: 150..250, short 50ms gap, s3: single point (300..300).
        val s1pts = listOf(pt(0), pt(100))
        val s2pts = listOf(pt(150), pt(250))
        val s3pts = listOf(pt(300))
        val s1 = stroke("s1", s1pts)
        val s2 = stroke("s2", s2pts)
        val s3 = stroke("s3", s3pts)
        return buildReplayTimeline(
            listOf(s1, s2, s3),
            pointsMap(s1 to s1pts, s2 to s2pts, s3 to s3pts),
            maxGapMs = 700L,
        )
    }

    @Test fun positionZero_firstStrokeActiveAtZeroFraction() {
        val timeline = threeStrokeTimeline()
        val frame = replayFrameAt(timeline, 0L)
        assertThat(frame.doneStrokes).isEmpty()
        assertThat(frame.activeStroke?.stroke?.uuid).isEqualTo("s1")
        assertThat(frame.activeFraction).isEqualTo(0f)
    }

    @Test fun midPosition_correctDoneCountAndActiveFraction() {
        val timeline = threeStrokeTimeline()
        val frame = replayFrameAt(timeline, 175L) // inside s2's [150,250] window, 25% through
        assertThat(frame.doneStrokes.map { it.stroke.uuid }).containsExactly("s1")
        assertThat(frame.activeStroke?.stroke?.uuid).isEqualTo("s2")
        assertThat(frame.activeFraction).isWithin(1e-3f).of(0.25f)
    }

    @Test fun endPosition_allStrokesDoneNoActive() {
        val timeline = threeStrokeTimeline()
        val frame = replayFrameAt(timeline, timeline.totalMs)
        assertThat(frame.doneStrokes.map { it.stroke.uuid }).containsExactly("s1", "s2", "s3")
        assertThat(frame.activeStroke).isNull()
    }

    @Test fun positionBeyondTotal_clampsToEnd() {
        val timeline = threeStrokeTimeline()
        val frame = replayFrameAt(timeline, timeline.totalMs + 10_000L)
        assertThat(frame.doneStrokes).hasSize(3)
        assertThat(frame.activeStroke).isNull()
    }

    @Test fun negativePosition_clampsToStart() {
        val timeline = threeStrokeTimeline()
        val frame = replayFrameAt(timeline, -500L)
        assertThat(frame.doneStrokes).isEmpty()
        assertThat(frame.activeStroke?.stroke?.uuid).isEqualTo("s1")
        assertThat(frame.activeFraction).isEqualTo(0f)
    }

    @Test fun emptyTimeline_yieldsNoActiveNoDone() {
        val timeline = buildReplayTimeline(emptyList(), pointsMap())
        val frame = replayFrameAt(timeline, 0L)
        assertThat(frame.doneStrokes).isEmpty()
        assertThat(frame.activeStroke).isNull()
    }

    // ── pointsUpTo ───────────────────────────────────────────────────────────────────────────

    @Test fun pointsUpTo_zeroFraction_yieldsOnlyFirstPoint() {
        val pts = listOf(pt(0), pt(100), pt(200), pt(300))
        val rs = ReplayStroke(stroke("s", pts), pts, startMs = 0L, endMs = 300L)
        assertThat(pointsUpTo(rs, 0f).map { it.t }).containsExactly(0L)
    }

    @Test fun pointsUpTo_fullFraction_yieldsAllPoints() {
        val pts = listOf(pt(0), pt(100), pt(200), pt(300))
        val rs = ReplayStroke(stroke("s", pts), pts, startMs = 0L, endMs = 300L)
        assertThat(pointsUpTo(rs, 1f)).isEqualTo(pts)
    }

    @Test fun pointsUpTo_midFraction_truncatesByPointTime() {
        val pts = listOf(pt(0), pt(100), pt(200), pt(300))
        val rs = ReplayStroke(stroke("s", pts), pts, startMs = 0L, endMs = 300L)
        val visible = pointsUpTo(rs, 0.5f) // cut at t=150 → points at t=0,100
        assertThat(visible.map { it.t }).containsExactly(0L, 100L).inOrder()
    }

    @Test fun pointsUpTo_emptyStroke_yieldsEmpty() {
        val rs = ReplayStroke(stroke("s", emptyList()), emptyList(), startMs = 0L, endMs = 0L)
        assertThat(pointsUpTo(rs, 0.5f)).isEmpty()
    }
}
