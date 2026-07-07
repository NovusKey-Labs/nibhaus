package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.pen.PenConnState
import com.nibhaus.pen.PenNotifications
import org.junit.Test

class PenNotificationsTest {

    @Test
    fun `status text reflects each connection state`() {
        assertThat(PenNotifications.statusText(PenConnState.Connected("mac", "Neo M1+")))
            .contains("Neo M1+")
        assertThat(PenNotifications.statusText(PenConnState.Connecting("mac"))).contains("Connecting")
        assertThat(PenNotifications.statusText(PenConnState.Reconnecting("mac", attempt = 3, nextDelayMs = 8000)))
            .contains("attempt 3")
        assertThat(PenNotifications.statusText(PenConnState.BondedElsewhere("mac")))
            .contains("another device")
        assertThat(PenNotifications.statusText(PenConnState.Disconnected())).contains("No pen")
    }

    @Test
    fun `service stays active for every state except disconnected`() {
        assertThat(PenNotifications.isActive(PenConnState.Connected("m", "p"))).isTrue()
        assertThat(PenNotifications.isActive(PenConnState.Connecting("m"))).isTrue()
        assertThat(PenNotifications.isActive(PenConnState.Reconnecting("m", 1, 2000))).isTrue()
        assertThat(PenNotifications.isActive(PenConnState.BondedElsewhere("m"))).isTrue()
        assertThat(PenNotifications.isActive(PenConnState.Disconnected())).isFalse()
    }

    @Test
    fun `capture text shows notebook, page and stroke count when connected`() {
        assertThat(PenNotifications.captureText(PenConnState.Connected("m", "LAMY"), "Meeting", 3, 42))
            .isEqualTo("Meeting · Page 3 · 42 strokes")
        assertThat(PenNotifications.captureText(PenConnState.Connected("m", "LAMY"), "Ideas", 1, 1))
            .isEqualTo("Ideas · Page 1 · 1 stroke")
    }

    @Test
    fun `capture text falls back to status when not connected`() {
        assertThat(PenNotifications.captureText(PenConnState.Connecting("m"), "Ideas", 1, 1))
            .isEqualTo(PenNotifications.statusText(PenConnState.Connecting("m")))
    }

    @Test
    fun `alert text names pages safe and recovery state`() {
        assertThat(PenNotifications.alertText(42, reconnecting = true))
            .isEqualTo("Pen disconnected. 42 pages safe · reconnecting…")
        assertThat(PenNotifications.alertText(1, reconnecting = false))
            .isEqualTo("Pen disconnected. 1 page safe · tap to reconnect")
    }
}
