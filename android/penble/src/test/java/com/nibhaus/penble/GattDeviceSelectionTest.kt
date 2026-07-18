package com.nibhaus.penble

import android.bluetooth.BluetoothDevice
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GattDeviceSelectionTest {
    @Test fun `API 24 through 32 require the device retained from scanning`() {
        listOf(24, 30, 31, 32).forEach { api ->
            assertThat(GattDeviceSelection.forApi(api, hasScannedDevice = true))
                .isEqualTo(GattDeviceSelection.SCANNED_DEVICE)
            assertThat(GattDeviceSelection.forApi(api, hasScannedDevice = false))
                .isEqualTo(GattDeviceSelection.UNAVAILABLE)
        }
    }

    @Test fun `API 33 and newer may reconstruct with explicit random address type`() {
        listOf(33, 34, 37).forEach { api ->
            assertThat(GattDeviceSelection.forApi(api, hasScannedDevice = false))
                .isEqualTo(GattDeviceSelection.REMOTE_LE_RANDOM)
        }
        assertThat(GattDeviceSelection.remoteAddressType).isEqualTo(BluetoothDevice.ADDRESS_TYPE_RANDOM)
    }

    @Test fun `scanned device remains preferred on API 33 and newer`() {
        assertThat(GattDeviceSelection.forApi(33, hasScannedDevice = true))
            .isEqualTo(GattDeviceSelection.SCANNED_DEVICE)
    }
}
