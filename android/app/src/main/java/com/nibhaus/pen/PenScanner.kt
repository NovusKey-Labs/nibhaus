package com.nibhaus.pen

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A pen discovered during a BLE scan, ready to connect (carries its full [PenTarget]). */
data class ScannedPen(val target: PenTarget, val name: String, val rssi: Int) {
    /** The pen's stable identity (9C:7B:D2:…), shown in the list. */
    val mac: String get() = target.sppAddress
}

/**
 * Feature 2 (Pens screen saved-pen tiles): tap-to-reconnect state, set only by
 * [com.nibhaus.di.ServiceLocator.connectSaved] — distinct from [PenConnState], which tracks the
 * actual pen link once a target is found and handed to [PenConnectionManager]. Drives a saved-pen
 * tile's transient "searching…" / "not found — is the pen on?" chrome while the reconnect scan runs.
 */
sealed interface SavedPenConnectState {
    data object Idle : SavedPenConnectState
    data class Searching(val spp: String) : SavedPenConnectState
    data class NotFound(val spp: String) : SavedPenConnectState
}

/**
 * BLE scan for Ncode pens, exactly as NeoLAB's own `DeviceListActivity` does it — anything else
 * fails to connect, which cost us a lot of debugging:
 *
 *  1. **Filter by SERVICE UUID, not by name.** The pen is matched by the GATT service it advertises
 *     (V2 `000019F1…` or V5 `4f99f138…`), which also tells us its [PenProtocol] — the SDK needs that
 *     to find its service after connecting.
 *  2. **Derive the SPP identity from the advertisement.** `device.address` is a *random* advertising
 *     address; the pen's real identity (`9C:7B:D2:…`) is packed into the manufacturer-data field. We
 *     surface that as [PenTarget.sppAddress] and keep the advertising address as [PenTarget.leAddress]
 *     (the one that actually drives connectGatt).
 *
 * Re-implemented here in plain Android so `:app` stays free of any `kr.neolab.sdk` import (the SDK is
 * quarantined in `:neosdk`); the byte layout mirrors the SDK's `UuidUtil`.
 */
class PenScanner(context: Context? = null) {
    private val scanner =
        (context?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter?.bluetoothLeScanner

    private val _results = MutableStateFlow<List<ScannedPen>>(emptyList())
    val results: StateFlow<List<ScannedPen>> = _results.asStateFlow()

    private val found = LinkedHashMap<String, ScannedPen>()
    @Volatile private var callback: ScanCallback? = null

    @SuppressLint("MissingPermission") // BLUETOOTH_SCAN is granted before scanning (MainActivity)
    fun start() {
        val s = scanner ?: return
        stop()
        found.clear()
        _results.value = emptyList()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                val raw = record.bytes ?: return
                // The pen's real identity lives in the advertisement's manufacturer data; without it
                // this packet (e.g. a bare scan response) isn't actionable — wait for the next one.
                val spp = sppAddressFrom(raw) ?: return
                val protocol = protocolOf(record.serviceUuids) ?: return
                val name = record.deviceName ?: result.device.name ?: "Neo smartpen"
                val target = PenTarget(spp, result.device.address, protocol)
                found[spp] = ScannedPen(target, name, result.rssi)
                _results.value = found.values.sortedByDescending { it.rssi }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w("Nibhaus", "pen scan failed: $errorCode")
            }
        }
        callback = cb
        // SCAN_MODE_LOW_LATENCY + service-UUID filters: identical to NeoLAB's DeviceListActivity.
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_V2)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_V5)).build(),
        )
        s.startScan(filters, settings, cb)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        callback?.let { scanner?.stopScan(it) }
        callback = null
    }

    private fun protocolOf(serviceUuids: List<ParcelUuid>?): PenProtocol? = when {
        serviceUuids == null -> null
        serviceUuids.any { it.uuid == SERVICE_V5 } -> PenProtocol.V5
        serviceUuids.any { it.uuid == SERVICE_V2 } -> PenProtocol.V2
        else -> null
    }

    /**
     * Walks the raw advertisement AD structures for the manufacturer-specific field (type `0xFF`);
     * its first 6 bytes are the pen's public `9C:7B:D2:…` MAC. Mirrors
     * `kr.neolab.sdk.util.UuidUtil.changeAddressFromLeToSpp`.
     */
    private fun sppAddressFrom(record: ByteArray): String? {
        var i = 0
        while (i < record.size) {
            val len = record[i].toInt() and 0xFF
            if (len == 0 || i + 1 >= record.size) return null
            val type = record[i + 1].toInt() and 0xFF
            if (type == 0xFF) {
                if (i + 2 + 6 > record.size) return null
                return (0 until 6).joinToString(":") { "%02X".format(record[i + 2 + it]) }
            }
            i += len + 1
        }
        return null
    }

    private companion object {
        val SERVICE_V2: UUID = UUID.fromString("000019F1-0000-1000-8000-00805F9B34FB")
        val SERVICE_V5: UUID = UUID.fromString("4f99f138-9d53-5bfa-9e50-b147491afe68")
    }
}
