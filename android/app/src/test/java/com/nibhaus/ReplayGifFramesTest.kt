package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.share.replayGifFramePositions
import org.junit.Test

/**
 * [replayGifFramePositions] maps GIF export frame index -> replay position (ms). Pins down the
 * contract [com.nibhaus.share.ReplayGifRenderer] relies on: first frame blank (position 0), last
 * frame the fully-drawn page (position == totalMs), evenly spaced in between.
 */
class ReplayGifFramesTest {

    @Test fun firstFrame_isPositionZero() {
        val positions = replayGifFramePositions(totalMs = 10_000L, frameCount = 5)
        assertThat(positions.first()).isEqualTo(0L)
    }

    @Test fun lastFrame_isTotalMs() {
        val positions = replayGifFramePositions(totalMs = 10_000L, frameCount = 5)
        assertThat(positions.last()).isEqualTo(10_000L)
    }

    @Test fun framesAreEvenlySpacedAndMonotonic() {
        val positions = replayGifFramePositions(totalMs = 1_000L, frameCount = 5)
        assertThat(positions).containsExactly(0L, 250L, 500L, 750L, 1_000L).inOrder()
    }

    @Test fun frameCountMatchesRequestedLength() {
        val positions = replayGifFramePositions(totalMs = 5_000L, frameCount = 48)
        assertThat(positions).hasSize(48)
        assertThat(positions.first()).isEqualTo(0L)
        assertThat(positions.last()).isEqualTo(5_000L)
    }

    @Test fun singleFrame_isTheFullPage() {
        val positions = replayGifFramePositions(totalMs = 3_000L, frameCount = 1)
        assertThat(positions).containsExactly(3_000L)
    }

    @Test fun zeroLengthTimeline_allFramesAtZero() {
        val positions = replayGifFramePositions(totalMs = 0L, frameCount = 4)
        assertThat(positions).containsExactly(0L, 0L, 0L, 0L)
    }

    @Test fun invalidFrameCount_throws() {
        try {
            replayGifFramePositions(totalMs = 1_000L, frameCount = 0)
            error("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
