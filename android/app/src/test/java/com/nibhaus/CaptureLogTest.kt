package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.capture.CapturedDot
import com.nibhaus.capture.traceSpan
import org.junit.Test

class CaptureLogTest {

    private fun dot(x: Float, y: Float) = CapturedDot(0, 0, 0, 0, x, y, 0f, "MOVE", 0L)

    @Test
    fun `span is the bounding-box diagonal of the trace`() {
        // A 3-4-5 right triangle of points → diagonal 5.
        val dots = listOf(dot(0f, 0f), dot(3f, 0f), dot(3f, 4f))
        assertThat(traceSpan(dots)).isWithin(0.001f).of(5f)
    }

    @Test
    fun `fewer than two points spans zero`() {
        assertThat(traceSpan(listOf(dot(1f, 1f)))).isEqualTo(0f)
        assertThat(traceSpan(emptyList())).isEqualTo(0f)
    }
}
