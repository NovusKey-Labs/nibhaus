package com.nibhaus.penble

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nibhaus.pen.PenProtocol
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Retains the framework device produced by an active LE scan. Required on API 24-32 because those
 * releases cannot reconstruct a RANDOM-address device with an explicit address type. */
object ScannedBluetoothDevices {
    private val byAddress = ConcurrentHashMap<String, BluetoothDevice>()

    fun remember(device: BluetoothDevice) { byAddress[device.address] = device }
    internal fun find(address: String): BluetoothDevice? = byAddress[address]
    fun clear() = byAddress.clear()
}

@TargetApi(Build.VERSION_CODES.TIRAMISU)
@SuppressLint("UseRequiresApi") // penble deliberately has no AndroidX annotation dependency.
private fun remoteRandomDevice(adapter: android.bluetooth.BluetoothAdapter, address: String): BluetoothDevice =
    adapter.getRemoteLeDevice(address, BluetoothDevice.ADDRESS_TYPE_RANDOM)

@SuppressLint("InlinedApi") // Values are passed only on API levels selected by forApi().
internal object GattDeviceSelection {
    const val UNAVAILABLE = 0
    const val SCANNED_DEVICE = 1
    const val REMOTE_LE_RANDOM = 2
    const val remoteAddressType = BluetoothDevice.ADDRESS_TYPE_RANDOM

    fun forApi(api: Int, hasScannedDevice: Boolean): Int = when {
        hasScannedDevice -> SCANNED_DEVICE
        api >= Build.VERSION_CODES.TIRAMISU -> REMOTE_LE_RANDOM
        else -> UNAVAILABLE
    }
}

/** GATT service + characteristic UUIDs for one pen protocol generation (V2 = M1+/short UUIDs, V5 = LAMY). */
internal data class GattProfile(val service: UUID, val write: UUID, val notify: UUID)

/** The exact UUIDs the pen advertises, extracted from the SDK's BTLEAdt. */
internal object GattUuids {
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    private val V2 = GattProfile(
        service = UUID.fromString("000019F1-0000-1000-8000-00805F9B34FB"),
        // Verified on-device (Neosmartpen M1+): 2BA1 has props NOTIFY|INDICATE (0x30) + the CCCD;
        // 2BA0 is WRITE (0x08). The SDK's BTLEAdt constant NAMES had write/notify reversed here too
        // (same trap as V5 below) — swapping them fixes the M1+ "cccd-missing" connect-retry loop.
        write = UUID.fromString("00002BA0-0000-1000-8000-00805F9B34FB"),
        notify = UUID.fromString("00002BA1-0000-1000-8000-00805F9B34FB"),
    )
    private val V5 = GattProfile(
        service = UUID.fromString("4f99f138-9d53-5bfa-9e50-b147491afe68"),
        // Verified on-device: 8bc8cc7d has props WRITE (0x08); 64cd86b1 has NOTIFY|INDICATE (0x30)
        // plus the CCCD. (The SDK's BTLEAdt constant NAMES had these two reversed.)
        write = UUID.fromString("8bc8cc7d-88ca-56b0-af9a-9bf514d0d61a"),
        notify = UUID.fromString("64cd86b1-2256-5aeb-9f04-2caf6c60ae57"),
    )

    fun forProtocol(p: PenProtocol): GattProfile = if (p == PenProtocol.V5) V5 else V2
}

/** Transport-level events. All callbacks arrive on the GATT binder thread — hop to your own scope. */
interface NeoTransportListener {
    /** GATT connected, service discovered, notifications enabled — ready to [NeoGattTransport.send]. */
    fun onReady()
    /** One complete inbound frame body `[cmd, error, lenLo, lenHi, payload…]` (already un-stuffed). */
    fun onFrame(body: ByteArray)
    fun onDisconnected()
    fun onError(stage: String, status: Int)
    /** Result of a [NeoGattTransport.startRssiPolling] read (dBm). Default no-op for listeners that
     *  don't care about RSSI. Delivered already marshalled onto [NeoGattTransport]'s main-thread
     *  handler (unlike the raw-binder-thread [onFrame]/[onReady]/etc. above) — see `onReadRemoteRssi`. */
    fun onRssi(rssi: Int) {}
}

/**
 * Clean-room BLE transport for the NeoLAB pen — a plain Android [BluetoothGatt] client that replaces
 * the GPL SDK's BTLEAdt. It connects on the pen's (rotating) LE address, discovers the V2/V5 service,
 * enables the notify characteristic, negotiates a large MTU, and turns the raw notification stream
 * into framed packets via [NeoFrameDecoder]. Outbound writes are serialized (BLE allows one GATT op
 * in flight). It knows nothing about commands or decoding — just bytes in, bytes out.
 *
 * Permissions (BLUETOOTH_CONNECT on API 31+) are granted by the app before any connect, exactly as
 * PenScanner requires for scanning.
 */
@SuppressLint("MissingPermission")
class NeoGattTransport(
    private val context: Context,
    private val protocol: PenProtocol,
    private val listener: NeoTransportListener,
) {
    private val tag = "NeoGatt"
    private val profile = GattUuids.forProtocol(protocol)
    private val decoder = NeoFrameDecoder()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    @Volatile private var gattConnected = false
    @Volatile private var rssiPolling = false

    private val handler = Handler(Looper.getMainLooper())
    private var leAddr: String? = null
    private var connectAttempts = 0

    private val txLock = Any()
    private val txQueue = ArrayDeque<ByteArray>()
    private var writing = false

    /** Connect to the pen at [leAddress] (a freshly-scanned address — it rotates, so don't cache it). */
    fun connect(leAddress: String) {
        decoder.reset()
        leAddr = leAddress
        connectAttempts = 0
        doConnect()
    }

    /** One connectGatt attempt. Establishment failures (status 133 / HCI 0x3e) are transient on
     *  Android BLE, so [onConnectionStateChange] retries this a few times before giving up. */
    private fun doConnect() {
        val leAddress = leAddr ?: return
        connectAttempts++
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: run { listener.onError("adapter", -1); return }
        val scannedDevice = ScannedBluetoothDevices.find(leAddress)
        // getRemoteLeDevice is API 33. Before that, only the BluetoothDevice returned by the active
        // scan preserves the pen's RANDOM address type; getRemoteDevice would assume PUBLIC.
        val device = when (GattDeviceSelection.forApi(Build.VERSION.SDK_INT, scannedDevice != null)) {
            GattDeviceSelection.SCANNED_DEVICE -> scannedDevice
            GattDeviceSelection.REMOTE_LE_RANDOM ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    remoteRandomDevice(adapter, leAddress)
                } else null
            else -> null
        } ?: run { listener.onError("scanned-device", -1); return }
        gatt = device.connectGatt(context, /* autoConnect = */ false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    /** Queue a framed packet for transmission (already wrapped by NeoFraming). */
    fun send(frame: ByteArray) {
        synchronized(txLock) { txQueue.addLast(frame); pump() }
    }

    /** Start ~1 Hz [BluetoothGatt.readRemoteRssi] polling; readings arrive via
     *  [NeoTransportListener.onRssi]. Read-only and safe (no protocol command involved). Safe to call
     *  before/while the link is down — [gatt] is null-checked each tick, so it just resumes reading
     *  the moment a connection exists, with no separate "start once connected" wiring needed. */
    fun startRssiPolling() {
        if (rssiPolling) return
        rssiPolling = true
        pollRssiOnce()
    }

    /** Stop polling (the caller — [NeoTransportListener] — no longer wants RSSI). */
    fun stopRssiPolling() { rssiPolling = false }

    private fun pollRssiOnce() {
        if (!rssiPolling) return
        gatt?.readRemoteRssi()
        handler.postDelayed({ pollRssiOnce() }, RSSI_POLL_MS)
    }

    fun close() {
        leAddr = null // stop any pending establishment retries
        rssiPolling = false
        handler.removeCallbacksAndMessages(null)
        runCatching { gatt?.close() }
        gatt = null
        writeChar = null
        gattConnected = false
        synchronized(txLock) { txQueue.clear(); writing = false }
    }

    /** Send the next queued packet if the link is idle. Caller holds [txLock]. */
    @Suppress("DEPRECATION")
    private fun pump() {
        if (writing) return
        val g = gatt ?: return
        val ch = writeChar ?: return
        val next = txQueue.removeFirstOrNull() ?: return
        writing = true
        ch.writeType = writeType
        ch.value = next
        if (!g.writeCharacteristic(ch)) {
            // Couldn't dispatch — unwind so a later send retries rather than dead-locking the queue.
            writing = false
            listener.onError("write-dispatch", -1)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gattConnected = true
                    connectAttempts = 0 // reached the device — reset the establishment-retry budget
                    Log.i(tag, "connected (status=$status) — requesting MTU")
                    if (!g.requestMtu(MAX_MTU)) g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val established = gattConnected
                    gattConnected = false
                    runCatching { g.close() }
                    gatt = null
                    // Retry ONLY a transient pre-connect establishment failure; a drop AFTER we were
                    // connected is a real disconnect for the manager (auto-reconnect) to handle.
                    if (!established && status != BluetoothGatt.GATT_SUCCESS &&
                        leAddr != null && connectAttempts < MAX_CONNECT_ATTEMPTS
                    ) {
                        Log.i(tag, "establishment failed (status=$status) — retry ${connectAttempts + 1}/$MAX_CONNECT_ATTEMPTS")
                        handler.postDelayed({ doConnect() }, RETRY_DELAY_MS)
                    } else {
                        Log.i(tag, "disconnected (status=$status)")
                        listener.onDisconnected()
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(tag, "mtu=$mtu (status=$status) — discovering services")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { listener.onError("discover", status); return }
            val svc = g.getService(profile.service) ?: run { listener.onError("service-missing", -1); return }
            val w = svc.getCharacteristic(profile.write)
            val n = svc.getCharacteristic(profile.notify)
            if (w == null || n == null) { listener.onError("chars-missing", -1); return }
            writeChar = w
            // Prefer write-without-response if the char supports it (the pen's data char does); the
            // protocol carries its own RES acknowledgements, so we don't need ATT-level confirmation.
            writeType = if (w.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            g.setCharacteristicNotification(n, true)
            val cccd = n.getDescriptor(GattUuids.CCCD) ?: run { listener.onError("cccd-missing", -1); return }
            @Suppress("DEPRECATION")
            run {
                cccd.value = if (n.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                }
                g.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != GattUuids.CCCD) return
            if (status == BluetoothGatt.GATT_SUCCESS) { Log.i(tag, "notifications on — ready"); listener.onReady() }
            else listener.onError("cccd-write", status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            synchronized(txLock) { writing = false; pump() }
        }

        // RX. Override BOTH overloads: API 33+ delivers the (…, value) variant, older delivers the
        // deprecated one — overriding both means each platform fires exactly one, no double-read.
        @Suppress("DEPRECATION") // pre-API-33 RX callback; kept for minSdk 24
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid == profile.notify) onRx(ch.value)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (ch.uuid == profile.notify) onRx(value)
        }

        // Arrives on the GATT binder thread like every callback above — but unlike those (which are
        // dispatched straight through), RSSI feeds a StateFlow the UI collects, so marshal it onto
        // the same main-thread handler already used for connect retries, to keep that write off the
        // binder thread.
        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            handler.post { listener.onRssi(rssi) }
        }
    }

    private fun onRx(data: ByteArray?) {
        if (data == null || data.isEmpty()) return
        for (body in decoder.feed(data)) listener.onFrame(body)
    }

    private companion object {
        const val MAX_MTU = 512
        const val MAX_CONNECT_ATTEMPTS = 4
        const val RETRY_DELAY_MS = 400L
        const val RSSI_POLL_MS = 1_000L
    }
}
