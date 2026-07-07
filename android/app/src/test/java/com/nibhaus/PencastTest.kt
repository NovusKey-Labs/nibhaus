package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.audio.firstStrokeDuring
import com.nibhaus.audio.isStrokeSpoken
import com.nibhaus.audio.markerStrokes
import com.nibhaus.audio.recordingForStroke
import com.nibhaus.data.RecordingEntity
import com.nibhaus.data.StrokeEntity
import com.nibhaus.data.SyncState
import org.junit.Test

class PencastTest {
    private fun stroke(uuid: String, startedAt: Long) = StrokeEntity(
        uuid = uuid, pageId = "p", color = 0, startedAt = startedAt, endedAt = startedAt + 10,
        pointsJson = "[]", syncState = SyncState.PENDING,
    )

    private val rec = RecordingEntity(id = "r1", pageId = "p", path = "/x", startedAt = 1_000, durationMs = 5_000)

    @Test fun strokeWithinSpan_mapsToOffset() {
        val (r, offset) = recordingForStroke(listOf(rec), stroke("s", 3_500))!!
        assertThat(r.id).isEqualTo("r1")
        assertThat(offset).isEqualTo(2_500) // 3500 - 1000
    }

    @Test fun strokeOutsideSpan_mapsToNothing() {
        assertThat(recordingForStroke(listOf(rec), stroke("before", 500))).isNull()
        assertThat(recordingForStroke(listOf(rec), stroke("after", 9_999))).isNull()
    }

    @Test fun inProgressRecording_isOpenEnded() {
        val live = rec.copy(durationMs = 0)
        assertThat(recordingForStroke(listOf(live), stroke("s", 99_999))).isNotNull()
    }

    @Test fun spokenStroke_tracksPosition() {
        val s = stroke("s", 3_000) // offset 2000ms
        assertThat(isStrokeSpoken(rec, s, positionMs = 1_500)).isFalse()
        assertThat(isStrokeSpoken(rec, s, positionMs = 2_500)).isTrue()
    }

    @Test fun firstStrokeDuring_picksEarliest() {
        val strokes = listOf(stroke("late", 4_000), stroke("early", 2_000), stroke("before", 0))
        assertThat(firstStrokeDuring(rec, strokes)?.uuid).isEqualTo("early")
    }

    @Test fun markerStrokes_marksStartAndResumeAfterPause() {
        // rec spans 1000..6000. Strokes: a@1200, b@1300 (continuous), big pause, c@5000 (resume).
        val strokes = listOf(stroke("a", 1_200), stroke("b", 1_300), stroke("c", 5_000))
        val markers = markerStrokes(rec, strokes, gapMs = 2_000).map { it.uuid }
        assertThat(markers).containsExactly("a", "c").inOrder() // start + post-pause resume
    }

    @Test fun markerStrokes_noPause_onlyStartMarker() {
        val strokes = listOf(stroke("a", 1_200), stroke("b", 1_300), stroke("d", 1_350))
        assertThat(markerStrokes(rec, strokes, gapMs = 2_000).map { it.uuid }).containsExactly("a")
    }
}
