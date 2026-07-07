package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.pen.CaptureSignals
import com.nibhaus.pen.PenDot
import org.junit.Test

/** The silent-capture watchdog's pure decision: stalled only while the pen is DOWN and dots stop. */
class CaptureSignalsTest {
    private fun dot(phase: PenDot.Phase) = PenDot(0, 0, 0, 0, 0f, 0f, 0f, phase, 0L, 0)

    @Test
    fun `stalls when the pen is down and dots stop arriving`() {
        var now = 1_000L
        val signals = CaptureSignals(now = { now })
        signals.onDot(dot(PenDot.Phase.DOWN)) // tip down, dot at t=1000
        assertThat(signals.isStalled(4_000)).isFalse() // just arrived
        now = 1_000L + 4_000L // 4s later, still down, no new dot
        assertThat(signals.isStalled(4_000)).isTrue()
    }

    @Test
    fun `idle (pen up) is never a stall, however long`() {
        var now = 1_000L
        val signals = CaptureSignals(now = { now })
        signals.onDot(dot(PenDot.Phase.UP)) // pen lifted
        now = 1_000L + 60_000L // a minute idle
        assertThat(signals.isStalled(4_000)).isFalse()
    }

    @Test
    fun `resumed ink clears the stall`() {
        var now = 1_000L
        val signals = CaptureSignals(now = { now })
        signals.onDot(dot(PenDot.Phase.DOWN))
        now = 6_000L
        assertThat(signals.isStalled(4_000)).isTrue()
        signals.onDot(dot(PenDot.Phase.MOVE)) // a dot arrives at t=6000
        assertThat(signals.isStalled(4_000)).isFalse()
    }
}
