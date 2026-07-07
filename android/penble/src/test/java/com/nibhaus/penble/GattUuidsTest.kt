package com.nibhaus.penble

import com.google.common.truth.Truth.assertThat
import com.nibhaus.pen.PenProtocol
import org.junit.Test

/** Guards the GATT UUIDs against typos — a wrong byte here means nothing ever connects. */
class GattUuidsTest {

    @Test fun `V2 profile uses the captured short UUIDs`() {
        val p = GattUuids.forProtocol(PenProtocol.V2)
        assertThat(p.service.toString()).isEqualTo("000019f1-0000-1000-8000-00805f9b34fb")
        // Verified on-device (M1+ HCI capture): 2BA0 = WRITE (0x08), 2BA1 = NOTIFY|INDICATE + CCCD.
        // The vendor SDK's constant NAMES had these reversed; this test previously asserted that
        // reversed mapping and sat unnoticed because CI never ran :penble tests.
        assertThat(p.write.toString()).isEqualTo("00002ba0-0000-1000-8000-00805f9b34fb")
        assertThat(p.notify.toString()).isEqualTo("00002ba1-0000-1000-8000-00805f9b34fb")
    }

    @Test fun `V5 profile uses the captured 128-bit UUIDs`() {
        val p = GattUuids.forProtocol(PenProtocol.V5)
        assertThat(p.service.toString()).isEqualTo("4f99f138-9d53-5bfa-9e50-b147491afe68")
        assertThat(p.write.toString()).isEqualTo("8bc8cc7d-88ca-56b0-af9a-9bf514d0d61a")
        assertThat(p.notify.toString()).isEqualTo("64cd86b1-2256-5aeb-9f04-2caf6c60ae57")
    }

    @Test fun `CCCD is the standard client-config descriptor`() {
        assertThat(GattUuids.CCCD.toString()).isEqualTo("00002902-0000-1000-8000-00805f9b34fb")
    }
}
