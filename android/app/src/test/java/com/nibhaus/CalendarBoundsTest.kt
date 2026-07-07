package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.ui.eventBounds
import org.junit.Test

class CalendarBoundsTest {

    @Test
    fun `a timed event spans exactly its duration`() {
        val start = 1_700_000_000_000L
        val (s, e) = eventBounds(start, durationMin = 90, allDay = false)
        assertThat(s).isEqualTo(start)
        assertThat(e).isEqualTo(start + 90 * 60_000L)
    }

    @Test
    fun `an all-day event is a single 24h block`() {
        val (s, e) = eventBounds(System.currentTimeMillis(), durationMin = 60, allDay = true)
        assertThat(e - s).isEqualTo(24 * 60 * 60_000L)
    }
}
