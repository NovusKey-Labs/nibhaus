package com.nibhaus.pen

/**
 * In-memory pen used for development, previews, and unit tests. Lets the entire
 * ingestion → organize → sync pipeline run with no hardware.
 *
 * Tests drive it directly via [emitConnected], [emitDot], [emitDisconnected].
 */
class FakeNeoPenSdk : NeoPenSdk {
    private var listener: PenListener? = null
    private var dotListener: ((PenDot) -> Unit)? = null

    var connectShouldFailWith: PenMessage.FailureReason? = null
    var lastConnectedMac: String? = null
        private set
    /** The full target of the most recent connect attempt — lets tests see which (rotating) LE
     *  address was dialed, e.g. to prove a reconnect re-scanned for the pen's current address. */
    var lastConnectedTarget: PenTarget? = null
        private set
    var connectAttempts = 0
        private set

    override fun connect(target: PenTarget) {
        connectAttempts++
        lastConnectedTarget = target
        val failure = connectShouldFailWith
        if (failure != null) {
            listener?.onMessage(PenMessage.ConnectFailed(failure))
            return
        }
        lastConnectedMac = target.sppAddress
        listener?.onMessage(PenMessage.Connected(target.sppAddress, "Neo smartpen (fake)"))
    }

    override fun disconnect() {
        lastConnectedMac = null
        listener?.onMessage(PenMessage.Disconnected)
    }

    override fun requestStatus() { /* no-op; tests drive battery via emitBattery */ }
    fun emitBattery(percent: Int) = listener?.onMessage(PenMessage.Battery(percent))

    override fun releaseExistingBond(macAddress: String) {
        connectShouldFailWith = null
    }

    /** Treat any non-empty password as correct → the pen authorizes and connects. */
    override fun inputPassword(password: String) {
        if (password.isNotEmpty()) {
            listener?.onMessage(PenMessage.Connected(lastConnectedMac ?: "", "Neo smartpen (fake)"))
        }
    }

    /** The fake pen's current unlock password (null = none set). Tests can seed it. */
    var fakePassword: String? = null

    override fun changePassword(oldPassword: String, newPassword: String) {
        val ok = fakePassword == null || fakePassword == oldPassword
        if (ok) fakePassword = newPassword
        listener?.onMessage(PenMessage.PasswordResult(ok))
    }

    override fun disablePassword(currentPassword: String) {
        val ok = fakePassword == null || fakePassword == currentPassword
        if (ok) fakePassword = null
        listener?.onMessage(PenMessage.PasswordResult(ok))
    }

    /** The firmware "image" never actually flashes the fake pen — it just reports a clean result. */
    override fun updateFirmware(file: java.io.File) {
        listener?.onMessage(PenMessage.FirmwareProgress(100))
        listener?.onMessage(PenMessage.FirmwareResult(file.exists()))
    }

    fun emitFirmwareVersion(version: String) =
        listener?.onMessage(PenMessage.FirmwareVersion(version))

    override fun setListener(listener: PenListener) { this.listener = listener }
    override fun setDotListener(listener: (PenDot) -> Unit) { this.dotListener = listener }

    // --- RSSI (Find My Pen, #20b) — no real GATT to poll, but wired enough for PenConnectionManager
    // tests to assert the "poll only while asked" contract via emitRssi()/rssiPollingActive.
    private var rssiListener: ((Int) -> Unit)? = null
    var rssiPollingActive = false; private set

    override fun startRssiPolling() { rssiPollingActive = true }
    override fun stopRssiPolling() { rssiPollingActive = false }
    override fun setRssiListener(listener: (Int) -> Unit) { rssiListener = listener }

    /** Test hook: simulate an RSSI reading arriving from the GATT layer. */
    fun emitRssi(dbm: Int) = rssiListener?.invoke(dbm)

    private var offlineListener: ((OfflineBatch) -> Unit)? = null
    var allowOfflineData = false; private set

    /** Strokes the fake pen "has stored offline" — seed these in tests. */
    val offlineStore = mutableListOf<OfflineStroke>()

    override fun setAllowOfflineData(allow: Boolean) { allowOfflineData = allow }
    override fun requestOfflineDataList() {
        if (offlineStore.isNotEmpty()) offlineListener?.invoke(OfflineBatch("fake", offlineStore.toList()))
    }
    override fun requestOfflineData(section: Int, owner: Int, note: Int, deleteOnFinished: Boolean) {
        val match = offlineStore.filter { it.section == section && it.owner == owner && it.note == note }
        offlineListener?.invoke(OfflineBatch("fake", match))
        if (deleteOnFinished) offlineStore.removeAll(match)
    }
    override fun setOfflineListener(listener: (OfflineBatch) -> Unit) { offlineListener = listener }

    // --- test/dev hooks ---
    fun emitOfflineBatch(batch: OfflineBatch) = offlineListener?.invoke(batch)
    fun emitConnected(mac: String = "00:11:22:33:44:55") =
        listener?.onMessage(PenMessage.Connected(mac, "Neo smartpen (fake)"))

    fun emitDisconnected() = listener?.onMessage(PenMessage.Disconnected)

    fun emitPasswordRequired(wrongAttempt: Boolean = false, attemptsRemaining: Int? = null) =
        listener?.onMessage(PenMessage.PasswordRequired(wrongAttempt, attemptsRemaining))

    fun emitDot(dot: PenDot) = dotListener?.invoke(dot)
}
