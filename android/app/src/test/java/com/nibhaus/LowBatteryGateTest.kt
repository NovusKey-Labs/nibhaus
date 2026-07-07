package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.pen.LowBatteryGate
import org.junit.Test

/** UX #10: fires once per connection session on the low-battery crossing, re-arms on recovery/reconnect. */
class LowBatteryGateTest {

    @Test
    fun `fires the first time battery crosses to or below 15 percent`() {
        val gate = LowBatteryGate()
        assertThat(gate.onBattery(20, isCharging = false)).isFalse()
        assertThat(gate.onBattery(15, isCharging = false)).isTrue()
    }

    @Test
    fun `does not fire while still above 15 percent`() {
        val gate = LowBatteryGate()
        assertThat(gate.onBattery(16, isCharging = false)).isFalse()
    }

    @Test
    fun `does not refire on repeated low readings within the same session`() {
        val gate = LowBatteryGate()
        assertThat(gate.onBattery(12, isCharging = false)).isTrue()
        assertThat(gate.onBattery(11, isCharging = false)).isFalse()
        assertThat(gate.onBattery(10, isCharging = false)).isFalse()
    }

    @Test
    fun `re-arms once the battery recovers above 20 percent`() {
        val gate = LowBatteryGate()
        assertThat(gate.onBattery(10, isCharging = false)).isTrue()
        assertThat(gate.onBattery(21, isCharging = false)).isFalse() // recovery itself never fires
        assertThat(gate.onBattery(15, isCharging = false)).isTrue() // dropped again -> fires again
    }

    @Test
    fun `20 percent exactly does not re-arm (needs to be above)`() {
        val gate = LowBatteryGate()
        assertThat(gate.onBattery(10, isCharging = false)).isTrue()
        assertThat(gate.onBattery(20, isCharging = false)).isFalse()
        assertThat(gate.onBattery(14, isCharging = false)).isFalse() // still armed from the first fire
    }

    @Test
    fun `charging suppresses the warning and re-arms immediately`() {
        val gate = LowBatteryGate()
        assertThat(gate.onBattery(10, isCharging = false)).isTrue()
        assertThat(gate.onBattery(11, isCharging = true)).isFalse() // still low, but charging: re-arm, no fire
        assertThat(gate.onBattery(10, isCharging = false)).isTrue() // charging stopped, still low -> fires again
    }

    @Test
    fun `never warns while charging even if newly low`() {
        val gate = LowBatteryGate()
        assertThat(gate.onBattery(9, isCharging = true)).isFalse()
    }

    @Test
    fun `reset re-arms for a new connection session without recovery`() {
        val gate = LowBatteryGate()
        assertThat(gate.onBattery(8, isCharging = false)).isTrue()
        assertThat(gate.onBattery(7, isCharging = false)).isFalse() // same session, no refire

        gate.reset() // pen reconnected

        assertThat(gate.onBattery(7, isCharging = false)).isTrue() // still low, new session -> fires again
    }

    @Test
    fun `custom thresholds are honored`() {
        val gate = LowBatteryGate(lowPct = 30, clearPct = 40)
        assertThat(gate.onBattery(35, isCharging = false)).isFalse()
        assertThat(gate.onBattery(30, isCharging = false)).isTrue()
        assertThat(gate.onBattery(41, isCharging = false)).isFalse()
        assertThat(gate.onBattery(30, isCharging = false)).isTrue()
    }
}
