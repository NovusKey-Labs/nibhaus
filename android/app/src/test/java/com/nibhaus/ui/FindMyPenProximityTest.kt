package com.nibhaus.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** UX #20b: the pure RSSI smoothing (EMA) + hot/cold bucketing behind the Find-My-Pen readout. */
class FindMyPenProximityTest {

    // --- emaRssi ---

    @Test
    fun `first sample primes the filter with no lag`() {
        assertThat(emaRssi(previous = null, raw = -60, dtMillis = 1_000)).isEqualTo(-60f)
    }

    @Test
    fun `a single sample nudges the average toward the new reading`() {
        val smoothed = emaRssi(previous = -60f, raw = -40, dtMillis = 1_000)
        assertThat(smoothed).isGreaterThan(-60f)
        assertThat(smoothed).isLessThan(-40f)
    }

    @Test
    fun `a large dt (long gap) converges close to the new raw reading`() {
        val smoothed = emaRssi(previous = -80f, raw = -40, dtMillis = 30_000)
        assertThat(smoothed).isWithin(0.5f).of(-40f)
    }

    @Test
    fun `a tiny dt barely moves the average`() {
        val smoothed = emaRssi(previous = -80f, raw = -40, dtMillis = 10)
        assertThat(smoothed).isWithin(1f).of(-80f)
    }

    @Test
    fun `steady raw readings converge to that value over repeated samples`() {
        var smoothed: Float? = null
        repeat(20) { smoothed = emaRssi(smoothed, raw = -55, dtMillis = 1_000) }
        assertThat(smoothed!!).isWithin(0.5f).of(-55f)
    }

    @Test
    fun `smooths out a single noisy spike`() {
        var smoothed: Float? = -60f
        // One-off spike to -30 for a single 1s sample, then back to steady -60 readings.
        smoothed = emaRssi(smoothed, raw = -30, dtMillis = 1_000)
        assertThat(smoothed!!).isGreaterThan(-58f) // moved, but not all the way to -30
        repeat(10) { smoothed = emaRssi(smoothed, raw = -60, dtMillis = 1_000) }
        assertThat(smoothed!!).isWithin(1f).of(-60f) // settles back down
    }

    // --- proximityLevel ---

    @Test
    fun `buckets strong signal as very close`() {
        assertThat(proximityLevel(-40f)).isEqualTo(ProximityLevel.VERY_CLOSE)
        assertThat(proximityLevel(-55f)).isEqualTo(ProximityLevel.VERY_CLOSE) // boundary inclusive
    }

    @Test
    fun `buckets mid signal as nearby`() {
        assertThat(proximityLevel(-56f)).isEqualTo(ProximityLevel.NEARBY)
        assertThat(proximityLevel(-70f)).isEqualTo(ProximityLevel.NEARBY) // boundary inclusive
    }

    @Test
    fun `buckets weaker signal as in the room`() {
        assertThat(proximityLevel(-71f)).isEqualTo(ProximityLevel.IN_THE_ROOM)
        assertThat(proximityLevel(-80f)).isEqualTo(ProximityLevel.IN_THE_ROOM) // boundary inclusive
    }

    @Test
    fun `buckets very weak signal as far`() {
        // -81 (just past the room boundary) through across-the-house readings are FAR, not in-room.
        assertThat(proximityLevel(-81f)).isEqualTo(ProximityLevel.FAR)
        assertThat(proximityLevel(-99f)).isEqualTo(ProximityLevel.FAR)
    }

    // --- proximityCloseness (drives the pin's radial position on the radar) ---

    @Test
    fun `strong signal parks the pin at center`() {
        assertThat(proximityCloseness(-50f)).isEqualTo(1f)
        assertThat(proximityCloseness(-30f)).isEqualTo(1f) // clamped above the ceiling
    }

    @Test
    fun `weak signal pushes the pin to the outer ring`() {
        assertThat(proximityCloseness(-95f)).isEqualTo(0f)
        assertThat(proximityCloseness(-120f)).isEqualTo(0f) // clamped below the floor
    }

    @Test
    fun `closeness moves monotonically as the pen gets farther`() {
        // Walking away (rising dBm magnitude) must strictly decrease closeness, so the pin travels out.
        val near = proximityCloseness(-60f)
        val mid = proximityCloseness(-75f)
        val far = proximityCloseness(-90f)
        assertThat(near).isGreaterThan(mid)
        assertThat(mid).isGreaterThan(far)
    }
}
