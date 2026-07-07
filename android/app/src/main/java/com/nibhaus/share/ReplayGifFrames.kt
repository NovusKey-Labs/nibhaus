package com.nibhaus.share

/**
 * Maps GIF export frame index → replay position (ms), for [frameCount] frames spanning the whole
 * [totalMs] replay timeline. Deliberately spaced over `i / (frameCount - 1)` rather than the naive
 * `i / frameCount` so the FIRST frame lands at position 0 (blank page, nothing drawn yet) and the
 * LAST frame lands exactly at [totalMs] (the fully-drawn page) — `i / frameCount` would stop one
 * step short of the end. A single-frame export just shows the finished page.
 */
fun replayGifFramePositions(totalMs: Long, frameCount: Int): List<Long> {
    require(frameCount > 0) { "frameCount must be > 0, was $frameCount" }
    if (frameCount == 1) return listOf(totalMs)
    return (0 until frameCount).map { i -> (totalMs * i) / (frameCount - 1) }
}
