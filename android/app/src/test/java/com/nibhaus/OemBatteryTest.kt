package com.nibhaus

import android.provider.Settings
import com.google.common.truth.Truth.assertThat
import com.nibhaus.pen.OemBattery
import org.junit.Test

/** Pure intent-choice logic only — [OemBattery.isIgnoring] wraps PowerManager and isn't unit-tested. */
class OemBatteryTest {
    @Test fun prefersTheDirectConsentDialog_whenTheDeviceResolvesIt() {
        val chosen = OemBattery.chooseAction { action -> action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS }
        assertThat(chosen).isEqualTo(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
    }

    @Test fun fallsBackToTheGeneralSettingsScreen_whenTheDirectDialogDoesNotResolve() {
        val chosen = OemBattery.chooseAction { action -> action == Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS }
        assertThat(chosen).isEqualTo(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    @Test fun returnsNull_whenNeitherActionResolves() {
        val chosen = OemBattery.chooseAction { false }
        assertThat(chosen).isNull()
    }

    @Test fun preferenceOrder_directDialogWinsEvenWhenBothResolve() {
        val chosen = OemBattery.chooseAction { true }
        assertThat(chosen).isEqualTo(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
    }
}
