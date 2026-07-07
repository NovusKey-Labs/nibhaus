package com.nibhaus.pen

/**
 * Abstraction over the Neo smartpen SDK.
 *
 * This intentionally mirrors the shape of the real `kr.neolab.sdk` API
 * (`PenCtrl` + `IPenMsgListener` + `IPenDotListener`) so the production
 * implementation [NeoSdkAdapter] is a thin translation layer, and so the rest
 * of the app can be developed and unit-tested against [FakeNeoPenSdk] with no
 * hardware.
 *
 * Discovery (BLE scanning) is handled by [PenConnectionManager], matching how
 * the real SDK delegates scanning to the host app.
 */
interface NeoPenSdk {

    /** Connect to a scanned pen. Result is reported via [PenListener]. See [PenTarget]. */
    fun connect(target: PenTarget)

    /** Disconnect the current pen, if any. */
    fun disconnect()

    /** Ask the pen to report its status; the battery reading arrives via [PenMessage.Battery]. */
    fun requestStatus()

    /** Answer a [PenMessage.PasswordRequired]. Locked pens emit no ink until the password is right. */
    fun inputPassword(password: String)

    /** Change the pen's unlock password. Result arrives as [PenMessage.PasswordResult]. */
    fun changePassword(oldPassword: String, newPassword: String)

    /** Turn the pen's unlock password off entirely. Result arrives as [PenMessage.PasswordResult]. */
    fun disablePassword(currentPassword: String)

    /**
     * Flash a firmware image [file] to the pen. Progress arrives as [PenMessage.FirmwareProgress]
     * and the outcome as [PenMessage.FirmwareResult]. Caller must supply an official image; there is
     * no online update path (NeoLAB ships firmware through its own app, and a bad image bricks the
     * pen), so this is a deliberate, user-driven action.
     */
    fun updateFirmware(file: java.io.File)

    /**
     * Some Neo pens bond to a single device at a time. When a connect fails
     * because the pen is bonded elsewhere, the host can call this to force the
     * pen to release its existing bond ("take over").
     */
    fun releaseExistingBond(macAddress: String)

    fun setListener(listener: PenListener)
    fun setDotListener(listener: (PenDot) -> Unit)

    // --- RSSI (Find My Pen, #20b) — an OPTIONAL capability ---
    // Only a GATT-backed driver (:penble's PenBleSdk) can read RSSI; other drivers (FakeNeoPenSdk,
    // any future non-BLE transport) just never call the listener, and PenConnectionManager.rssiDbm
    // stays null forever for them — which the UI already treats as "unavailable". Default no-op
    // bodies keep this off the required-implementation surface.

    /** Start ~1 Hz RSSI polling. Callers own the lifecycle (poll only while something wants it, e.g.
     *  the Find-My-Pen screen while visible) — this SDK does not poll on its own. */
    fun startRssiPolling() {}

    /** Stop polling. */
    fun stopRssiPolling() {}

    /** Readings arrive here (dBm), not via [PenMessage] — RSSI isn't part of the connection state
     *  machine, it's a side channel a driver may or may not support. */
    fun setRssiListener(listener: (Int) -> Unit) {}

    // --- Offline page retrieval from pen memory (Phase 2; verified PenCtrl surface) ---
    // The pen stores ~1,000 (M1+) / ~160 (LAMY) A4 pages offline. These pull them on reconnect.
    // Maps to PenCtrl.setAllowOfflineData / reqOfflineDataList / reqOfflineData(...).

    fun setAllowOfflineData(allow: Boolean)
    fun requestOfflineDataList()
    /** Request stored strokes for a notebook. deleteOnFinished mirrors the SDK flag. */
    fun requestOfflineData(section: Int, owner: Int, note: Int, deleteOnFinished: Boolean)
    fun setOfflineListener(listener: (OfflineBatch) -> Unit)
}

/**
 * Everything the NeoLAB SDK needs to open a GATT link to one pen. A bare MAC is NOT enough: the
 * real `connect(sppAddress, leAddress, uuidVer, …)` needs three distinct things, and getting any of
 * them wrong fast-fails the connection (verified against the SDK source):
 *
 *  - [sppAddress] — the pen's stable NeoLAB-OUI identity (`9C:7B:D2:…`), derived from the
 *    advertisement's manufacturer data. The SDK keys/reports the pen by this; it is NOT the address
 *    Android shows for the device.
 *  - [leAddress]  — the BLE advertising address (often a rotating *random* address). This is what
 *    actually drives `connectGatt(TRANSPORT_LE)`.
 *  - [protocol]   — which GATT service the pen exposes. After connecting, the SDK looks up its
 *    service by this; pick the wrong one and `onServicesDiscovered` finds nothing and tears the link
 *    down. Decided by the advertised service UUID (V2 = `000019F1…`, V5 = `4f99f138…`). The LAMY
 *    safari is V5; the M1+ is V2.
 */
data class PenTarget(
    val sppAddress: String,
    val leAddress: String,
    val protocol: PenProtocol,
) {
    /** The pen's stable identity, used for display and for [PenPrefs.lastPenMac]. */
    val id: String get() = sppAddress

    companion object {
        /** A target with no LE/identity split — for the fake pen, tests, and the legacy `-PpenMac`. */
        fun legacy(address: String) = PenTarget(address, address, PenProtocol.V2)
    }
}

/** Neo BLE GATT generation, selected from the advertised service UUID. */
enum class PenProtocol { V2, V5 }

/**
 * One delivery of offline strokes pulled from pen memory, mirroring
 * IOfflineDataListener.onReceiveOfflineStrokes(extra, penAddress, Stroke[], section, owner, note, Symbol[]).
 * [com.nibhaus.ingest.OfflineSync] ingests these idempotently (no page loss; safe to re-request).
 */
data class OfflineBatch(
    val penAddress: String,
    val strokes: List<OfflineStroke>,
)

/** A complete stored stroke from the pen, with its Ncode location and sampled points. */
data class OfflineStroke(
    val section: Int,
    val owner: Int,
    val note: Int,
    val page: Int,
    val color: Int,
    val points: List<OfflinePoint>,
)

data class OfflinePoint(val x: Float, val y: Float, val pressure: Float, val t: Long)

/** Connection / lifecycle messages from the pen, equivalent to the SDK's `PenMsg`. */
sealed interface PenMessage {
    data class Connected(val macAddress: String, val penName: String) : PenMessage
    data object Disconnected : PenMessage
    data class ConnectFailed(val reason: FailureReason) : PenMessage
    /** Locked pen is asking for its password; answer via [NeoPenSdk.inputPassword].
     *  [wrongAttempt] is true when this prompt follows a rejected password (vs. the initial lock
     *  prompt), and [attemptsRemaining] is `10 - attemptsUsed` from the pen's reply, when known. */
    data class PasswordRequired(val wrongAttempt: Boolean = false, val attemptsRemaining: Int? = null) : PenMessage
    /** Result of a [NeoPenSdk.changePassword] / [NeoPenSdk.disablePassword] request. */
    data class PasswordResult(val success: Boolean) : PenMessage
    /** The pen's reported firmware version (from its connect handshake). */
    data class FirmwareVersion(val version: String) : PenMessage
    /** Progress of an in-flight [NeoPenSdk.updateFirmware], 0–100. */
    data class FirmwareProgress(val percent: Int) : PenMessage
    /** Outcome of a [NeoPenSdk.updateFirmware]. */
    data class FirmwareResult(val success: Boolean) : PenMessage
    /** Pen reports the count of offline strokes it has buffered, ready for download. */
    data class OfflineDataAvailable(val strokeCount: Int) : PenMessage
    /** Pen battery level (0–100), from its status report. */
    data class Battery(val percent: Int) : PenMessage
    data object BatteryLow : PenMessage

    enum class FailureReason { TIMEOUT, BONDED_ELSEWHERE, AUTH_FAILED, UNKNOWN }
}

fun interface PenListener {
    fun onMessage(message: PenMessage)
}
