package com.nibhaus.audio

import com.nibhaus.data.RecordingEntity
import com.nibhaus.data.StrokeEntity

/**
 * Pure ink↔audio mapping. Strokes and recordings share one wall clock, so a stroke's audio offset
 * is simply `stroke.startedAt − recording.startedAt`. These functions back the four interactive
 * behaviours (tap-to-play, playback highlight, markers, scrub) without any Android dependency.
 */

/** The recording whose span covers [stroke]'s time, plus the stroke's offset into it (ms), or null. */
fun recordingForStroke(recordings: List<RecordingEntity>, stroke: StrokeEntity): Pair<RecordingEntity, Long>? {
    val rec = recordings.firstOrNull { r ->
        // durationMs == 0 means still-in-progress → treat as open-ended.
        stroke.startedAt >= r.startedAt && (r.durationMs <= 0L || stroke.startedAt <= r.startedAt + r.durationMs)
    } ?: return null
    return rec to (stroke.startedAt - rec.startedAt).coerceAtLeast(0L)
}

/** True if [stroke] had already been written by [positionMs] into [recording] (playback highlight). */
fun isStrokeSpoken(recording: RecordingEntity, stroke: StrokeEntity, positionMs: Long): Boolean {
    val offset = stroke.startedAt - recording.startedAt
    return offset in 0..positionMs
}

/** The earliest stroke captured during [recording] — where its on-page marker is anchored. */
fun firstStrokeDuring(recording: RecordingEntity, strokes: List<StrokeEntity>): StrokeEntity? =
    strokes.filter {
        it.startedAt >= recording.startedAt &&
            (recording.durationMs <= 0L || it.startedAt <= recording.startedAt + recording.durationMs)
    }.minByOrNull { it.startedAt }

/**
 * Where to drop voice-note bookmarks on the page: the first stroke of the recording, plus the first
 * stroke after each writing pause longer than [gapMs]. So if you record, pause, then resume writing
 * elsewhere, that resumption gets its own marker you can tap to jump straight to.
 */
fun markerStrokes(
    recording: RecordingEntity,
    strokes: List<StrokeEntity>,
    gapMs: Long = 7_000L,
): List<StrokeEntity> {
    val within = strokes.filter {
        it.startedAt >= recording.startedAt &&
            (recording.durationMs <= 0L || it.startedAt <= recording.startedAt + recording.durationMs)
    }.sortedBy { it.startedAt }
    val out = ArrayList<StrokeEntity>()
    var prevEnd = Long.MIN_VALUE
    for (s in within) {
        if (out.isEmpty() || s.startedAt - prevEnd >= gapMs) out.add(s)
        prevEnd = maxOf(prevEnd, s.endedAt)
    }
    return out
}
