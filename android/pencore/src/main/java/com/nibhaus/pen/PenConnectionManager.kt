package com.nibhaus.pen

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the pen connection lifecycle and fixes complaint #4 (fragile pairing /
 * no reconnect). Instead of a one-shot connect, this is an explicit state
 * machine with auto-reconnect-with-backoff to the last-known pen.
 *
 *   DISCONNECTED ─connect()→ CONNECTING ─ok→ CONNECTED
 *        ▲                        │ fail        │ link lost
 *        └──── backoff(2..16s) ◄ RECONNECTING ◄─┘
 *
 * Every [PenDot] received while connected is forwarded to [onDot] — which in
 * production is [com.nibhaus.ingest.StrokeIngestor.onDot], persisting it
 * immediately (complaint #1).
 *
 * @param backoffSchedule reconnection delays in ms; the last value repeats.
 */
class PenConnectionManager(
    private val sdk: NeoPenSdk,
    private val prefs: PenPrefs,
    private val scope: CoroutineScope,
    private val onDot: (PenDot) -> Unit,
    /**
     * Called with a password the user typed that the pen then accepted (a successful unlock). The
     * host persists it to its secure store so the next connect auto-unlocks ("enter once"). NOT
     * called for passwords the pen auto-tried from storage — those are already stored.
     */
    private val onPasswordAccepted: (String) -> Unit = {},
    /** Called when the pen's password is turned off — the host clears its stored secret. */
    private val onPasswordCleared: () -> Unit = {},
    /** The pen's current unlock password from the host's secure store, if it has one saved — used as
     *  a fallback "old" for a set/change when this session didn't capture it (e.g. auto-reconnect). */
    private val storedPassword: () -> String? = { null },
    private val backoffSchedule: List<Long> = listOf(2_000, 4_000, 8_000, 16_000),
    /** Stop auto-reconnecting after this many failed attempts and return to Disconnected. */
    private val maxReconnectAttempts: Int = 6,
    private val now: () -> Long = System::currentTimeMillis,
    /** How often to poll the pen for a fresh battery reading while connected. */
    private val batteryPollMs: Long = 60_000L,
    /**
     * Reacquire the pen's CURRENT LE target by scanning for its stable identity ([PenTarget.sppAddress])
     * for up to the given window, returning the fresh target when the pen reappears or null on timeout.
     * The LE address is a rotating random address that changes when the pen power-cycles, so reconnect
     * must RE-SCAN rather than redial the now-dead cached address (which is what a manual tap-to-connect
     * does, and why that works when the in-cycle retries don't). A null hook (tests / the fake SDK) ⇒
     * fall back to redialing the cached target.
     */
    private val rescan: (suspend (sppAddress: String, windowMs: Long) -> PenTarget?)? = null,
) {
    private val _state = MutableStateFlow<PenConnState>(PenConnState.Disconnected())
    val state: StateFlow<PenConnState> = _state.asStateFlow()

    /** Latest pen battery (with a charge-time estimate when it's rising), or null when unknown. */
    private val _battery = MutableStateFlow<BatteryStatus?>(null)
    val battery: StateFlow<BatteryStatus?> = _battery.asStateFlow()
    private val batterySamples = ArrayDeque<Pair<Long, Int>>() // (timeMs, percent)
    private var pollJob: Job? = null

    /** Live RSSI (dBm) while [startRssiPolling] is active; null when not polling, disconnected, or
     *  the active driver has no GATT access (e.g. [FakeNeoPenSdk]) — the Find-My-Pen screen already
     *  treats null as "unavailable" and shows its searching state. */
    private val _rssiDbm = MutableStateFlow<Int?>(null)
    val rssiDbm: StateFlow<Int?> = _rssiDbm.asStateFlow()

    /** Crossing/re-arm decision for the low-battery notification (UX #10); see [LowBatteryGate]. */
    private val lowBatteryGate = LowBatteryGate()
    private val _lowBatteryAlert = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    /** Emits the battery percent once per connection session when it crosses into low-battery
     *  territory (see [LowBatteryGate]). The host turns this into a notification. */
    val lowBatteryAlert: SharedFlow<Int> = _lowBatteryAlert.asSharedFlow()

    // @Volatile: mutated on the caller (main) thread and the GATT-callback thread, read by the
    // reconnect loop on Dispatchers.Default — needs visibility so a tapped Disconnect is seen promptly.
    @Volatile private var reconnecting = false

    /**
     * Set the moment the user taps Disconnect (the user-facing [disconnect] path), cleared by any
     * user-facing connect ([connect]/[takeOver]) — never by a mere link loss. While true, suppresses
     * ALL auto-reconnect: this manager's own [scheduleReconnect] retry/rescan loop, AND
     * [autoConnect] (the redial [com.nibhaus.pen.PenForegroundService.onStartCommand] issues on
     * every service (re)start). Fixes the live-test bug where tapping Disconnect immediately
     * reconnected — link LOSS (pen power-off / out of range) is unaffected and still auto-reconnects,
     * since that path never sets this flag.
     */
    @Volatile private var userDisconnected = false

    /** A password the user just typed via the prompt, held until the pen accepts it (then persisted). */
    @Volatile private var pendingPassword: String? = null

    /** Result of an in-flight change/disable-password request, for the Settings UI. */
    private val _passwordOp = MutableStateFlow<PasswordOpState>(PasswordOpState.Idle)
    val passwordOp: StateFlow<PasswordOpState> = _passwordOp.asStateFlow()
    private var pendingNewPassword: String? = null // the new password to persist if a change succeeds
    // The password that unlocked this session — reused as the "old" field for a change (Neo Studio 2
    // never re-asks), null on a passwordless pen (→ the pen's blank-default "0000").
    private var sessionPassword: String? = null
    private var passwordOpJob: Job? = null // times out a set/change so the UI never hangs on "Working"
    private var pendingDisable = false             // true while a disable-password op is in flight

    /** The connected pen's firmware version, or null until it reports one. */
    private val _firmwareVersion = MutableStateFlow<String?>(null)
    val firmwareVersion: StateFlow<String?> = _firmwareVersion.asStateFlow()

    /** Live mirror of [PenPrefs.savedPens] (Feature 2) — refreshed on every successful connect (see
     *  [onPenMessage]'s Connected branch) and on [forgetPen], so the Pens screen's saved-pen tiles
     *  update reactively instead of polling SharedPreferences. */
    private val _savedPens = MutableStateFlow(prefs.savedPens)
    val savedPens: StateFlow<List<SavedPen>> = _savedPens.asStateFlow()

    /** Forget a previously saved pen (the Pens screen's long-press "forget" affordance). */
    fun forgetPen(spp: String) {
        prefs.savedPens = forgetSavedPen(prefs.savedPens, spp)
        _savedPens.value = prefs.savedPens
        if (spp == lastTarget?.sppAddress) sessionPassword = null // don't reuse a forgotten pen's pw
    }

    /** Progress/result of an in-flight firmware update, for the Settings UI. */
    private val _firmwareUpdate = MutableStateFlow<FirmwareUpdateState>(FirmwareUpdateState.Idle)
    val firmwareUpdate: StateFlow<FirmwareUpdateState> = _firmwareUpdate.asStateFlow()

    /**
     * The last pen we were asked to connect to, kept so reconnect uses the SAME spp/le/protocol.
     * The LE address is a rotating random address, so this is only valid within a session — across
     * a process restart you must re-scan (a bare persisted MAC can't be GATT-connected).
     */
    private var lastTarget: PenTarget? = null

    init {
        sdk.setDotListener(onDot)
        sdk.setListener(::onPenMessage)
        sdk.setRssiListener { _rssiDbm.value = it }
    }

    /** Start ~1 Hz RSSI polling (Find My Pen, #20b). No-op forever on drivers without GATT access. */
    fun startRssiPolling() = sdk.startRssiPolling()

    /** Stop polling and clear the last reading. */
    fun stopRssiPolling() {
        sdk.stopRssiPolling()
        _rssiDbm.value = null
    }

    /**
     * Connect to an explicitly chosen pen (e.g. from a scan result). Deliberately does NOT write
     * [PenPrefs.lastPenMac] here: that field is only ever set from a confirmed [PenMessage.Connected]
     * (below) — proof a pen was actually paired — never from a merely-attempted connect that might
     * fail, so it can't be poisoned into naming a pen this device never successfully reached. In-flight
     * UI states that need to show a mac before a confirmation ([PasswordRequired]/[BondedElsewhere]/the
     * password-retry [Connecting]) read [lastTarget] instead, which IS safe to set optimistically
     * since it's never persisted.
     */
    fun connect(target: PenTarget) {
        userDisconnected = false // a fresh user-facing connect always re-arms auto-reconnect
        // A different pen than the one that set sessionPassword → its password is not the "old" for
        // this one; drop it so a Set/Change doesn't send pen A's password as pen B's current one.
        if (target.sppAddress != lastTarget?.sppAddress) sessionPassword = null
        lastTarget = target
        _state.value = PenConnState.Connecting(target.id)
        sdk.connect(target)
    }

    /** Convenience for tests / the legacy `-PpenMac` path, where only a bare address is known. */
    fun connect(macAddress: String) = connect(PenTarget.legacy(macAddress))

    /**
     * Auto-reconnect to the last pen of this session — including the "service-level redial"
     * [com.nibhaus.pen.PenForegroundService.onStartCommand] issues on every (re)start. No-op after
     * an explicit [disconnect] (see [userDisconnected]): the user asked to be disconnected, so a
     * service restart must not silently pull the pen back. After a process restart there is also no
     * usable target (the LE address has rotated), so this is a no-op until the user re-scans either way.
     */
    fun autoConnect() {
        if (userDisconnected) return
        connect(lastTarget ?: return)
    }

    /** Resolve the single-device-pairing block by forcing a bond takeover, then retry. */
    fun takeOver(macAddress: String) {
        sdk.releaseExistingBond(macAddress)
        connect(lastTarget ?: PenTarget.legacy(macAddress))
    }

    fun disconnect() {
        userDisconnected = true // suppress auto-reconnect until the user explicitly connects again
        reconnecting = false
        sessionPassword = null // the session that this password named is over
        stopBatteryPolling()
        _rssiDbm.value = null
        sdk.disconnect()
        _state.value = PenConnState.Disconnected()
    }

    private fun onPenMessage(msg: PenMessage) {
        when (msg) {
            is PenMessage.Connected -> {
                reconnecting = false
                // A fresh connection session — re-arm the low-battery notification (#10) so a pen
                // that reconnects still-low warns again instead of staying silenced from last time.
                lowBatteryGate.reset()
                // The pen accepted the password the user just typed → persist it for next time.
                pendingPassword?.let { onPasswordAccepted(it); sessionPassword = it; pendingPassword = null }
                // The ONLY place lastPenMac is written — a confirmed success, never an attempt (see
                // connect()'s doc).
                prefs.lastPenMac = msg.macAddress
                // Feature 2 (saved-pen tiles): remember every pen we successfully connect to, most
                // recent first, deduped by its stable spp identity — msg.macAddress IS the spp (see
                // PenTarget.id), never the rotating LE address. upsert-on-Connected is the ONLY source
                // of a saved-pen tile — there is deliberately no seed/migration from lastPenMac (that
                // pre-created a tile before any pairing happened this install, which surprised users).
                prefs.savedPens = upsertSavedPen(prefs.savedPens, SavedPen(msg.penName, msg.macAddress, now()))
                _savedPens.value = prefs.savedPens
                _state.value = PenConnState.Connected(msg.macAddress, msg.penName)
                startBatteryPolling()
            }
            is PenMessage.Disconnected -> {
                stopBatteryPolling()
                _rssiDbm.value = null
                // Unexpected drop while we believed we were connected -> auto-reconnect.
                if (_state.value is PenConnState.Connected) scheduleReconnect()
                else _state.value = PenConnState.Disconnected()
            }
            is PenMessage.ConnectFailed -> {
                if (msg.reason == PenMessage.FailureReason.BONDED_ELSEWHERE) {
                    // Surface an actionable state instead of a dead end (complaint #4).
                    _state.value = PenConnState.BondedElsewhere(lastTarget?.id.orEmpty())
                } else {
                    scheduleReconnect()
                }
            }
            is PenMessage.PasswordRequired -> {
                // A (re)prompt means any password we just tried was wrong — don't persist it.
                pendingPassword = null
                _state.value = PenConnState.PasswordRequired(
                    mac = lastTarget?.id.orEmpty(),
                    wrongAttempt = msg.wrongAttempt,
                    attemptsRemaining = msg.attemptsRemaining,
                )
            }
            is PenMessage.PasswordResult -> {
                passwordOpJob?.cancel() // a real result arrived — stop the hang-timeout
                if (msg.success) {
                    if (pendingDisable) onPasswordCleared()
                    // The change succeeded → the new password is now the current one for this session.
                    else pendingNewPassword?.let { onPasswordAccepted(it); sessionPassword = it }
                }
                _passwordOp.value = PasswordOpState.Done(success = msg.success, disabled = pendingDisable)
                pendingNewPassword = null
                pendingDisable = false
            }
            is PenMessage.FirmwareVersion -> _firmwareVersion.value = msg.version
            is PenMessage.FirmwareProgress -> _firmwareUpdate.value = FirmwareUpdateState.Working(msg.percent)
            is PenMessage.FirmwareResult -> _firmwareUpdate.value = FirmwareUpdateState.Done(msg.success)
            is PenMessage.Battery -> onBattery(msg.percent)
            is PenMessage.OfflineDataAvailable, PenMessage.BatteryLow -> Unit
        }
    }

    // --- battery + charge-rate estimate ---

    private fun onBattery(percent: Int) {
        val t = now()
        batterySamples.addLast(t to percent)
        // Keep ~30 min of history (but always at least two points to estimate a rate from).
        while (batterySamples.size > 2 && t - batterySamples.first().first > 30 * 60_000L) {
            batterySamples.removeFirst()
        }
        val eta = chargeEtaMinutes(percent, t)
        _battery.value = BatteryStatus(percent.coerceIn(0, 100), eta)
        // #10: fire the low-battery notification once per session on the crossing; see LowBatteryGate.
        if (lowBatteryGate.onBattery(percent, isCharging = eta != null)) {
            _lowBatteryAlert.tryEmit(percent)
        }
    }

    /** Minutes to 100% if the level is rising (charging), computed from the observed rate; else null. */
    private fun chargeEtaMinutes(percent: Int, t: Long): Int? {
        if (percent >= 100) return null
        val oldest = batterySamples.firstOrNull() ?: return null
        val dPct = percent - oldest.second
        val dMs = t - oldest.first
        if (dPct <= 0 || dMs < 90_000L) return null // not rising, or too little data to trust
        val msPerPct = dMs.toDouble() / dPct
        return ((100 - percent) * msPerPct / 60_000.0).toInt().takeIf { it in 1..6000 }
    }

    private fun startBatteryPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                sdk.requestStatus() // reply arrives as PenMessage.Battery
                delay(batteryPollMs)
            }
        }
    }

    private fun stopBatteryPolling() {
        pollJob?.cancel()
        pollJob = null
        batterySamples.clear()
        _battery.value = null
    }

    /** Answer a [PenConnState.PasswordRequired] with the user-entered password. */
    fun submitPassword(password: String) {
        pendingPassword = password // persisted via onPasswordAccepted once the pen authorizes
        _state.value = PenConnState.Connecting(lastTarget?.id.orEmpty())
        sdk.inputPassword(password)
    }

    /** Set or change the pen's unlock password. Reuses the password that unlocked this session as the
     *  "old" field (Neo Studio 2 does the same — it never re-asks the user), or the pen's blank-default
     *  "0000" on a passwordless pen. Times out after 8s so the UI never hangs when the pen — e.g. after
     *  a mismatched old — sends no reply. Watch [passwordOp] for the result. */
    fun changePassword(newPassword: String) {
        pendingNewPassword = newPassword
        pendingDisable = false
        _passwordOp.value = PasswordOpState.Working
        sdk.changePassword(sessionPassword ?: storedPassword() ?: "0000", newPassword)
        passwordOpJob?.cancel()
        passwordOpJob = scope.launch {
            delay(8_000)
            if (_passwordOp.value is PasswordOpState.Working) {
                // Do NOT null pendingNewPassword here: a genuine RES_PASSWORD_SET success can still
                // arrive after this 8s timeout, and the PasswordResult handler needs pendingNewPassword
                // intact to persist it (onPasswordAccepted + sessionPassword). It clears it itself.
                _passwordOp.value = PasswordOpState.Done(success = false, disabled = false)
            }
        }
    }

    /** Turn the pen's unlock password off. Watch [passwordOp] for the result. */
    fun disablePassword(currentPassword: String) {
        pendingNewPassword = null
        pendingDisable = true
        _passwordOp.value = PasswordOpState.Working
        sdk.disablePassword(currentPassword)
    }

    /** Dismiss a finished [passwordOp] result (the Settings UI calls this after showing it). */
    fun acknowledgePasswordOp() { _passwordOp.value = PasswordOpState.Idle }

    /** Flash a firmware image to the pen. Watch [firmwareUpdate] for progress/result. */
    fun updateFirmware(file: java.io.File) {
        _firmwareUpdate.value = FirmwareUpdateState.Working(0)
        sdk.updateFirmware(file)
    }

    /** Dismiss a finished [firmwareUpdate] result. */
    fun acknowledgeFirmwareUpdate() { _firmwareUpdate.value = FirmwareUpdateState.Idle }

    private fun scheduleReconnect() {
        if (userDisconnected) {
            // The user asked to disconnect; a straggling Disconnected/ConnectFailed message racing in
            // behind that request must not resurrect the reconnect loop.
            _state.value = PenConnState.Disconnected()
            return
        }
        val target = lastTarget ?: run {
            _state.value = PenConnState.Disconnected()
            return
        }
        if (reconnecting) return
        reconnecting = true
        scope.launch {
            var attempt = 0
            while (reconnecting && attempt < maxReconnectAttempts) {
                val window = backoffSchedule[minOf(attempt, backoffSchedule.lastIndex)]
                _state.value = PenConnState.Reconnecting(target.id, attempt + 1, window)
                // The pen's LE address rotates when it power-cycles, so the cached address goes dead and
                // blindly redialing it never reconnects (the reported bug). Spend the backoff window
                // SCANNING for the pen's stable identity to reacquire its CURRENT address; connect the
                // moment it reappears. No scanner (tests/fake) ⇒ legacy redial of the cached target.
                val fresh = if (rescan != null) rescan.invoke(target.sppAddress, window)
                            else { delay(window); target }
                attempt++
                if (!reconnecting) break
                if (fresh == null) continue // pen not seen this window — wait out the next backoff
                lastTarget = fresh
                _state.value = PenConnState.Connecting(target.id)
                sdk.connect(fresh) // success cancels the loop via onPenMessage(Connected)
                // Wait one cycle for the result before trying again.
                delay(backoffSchedule.first())
                if (_state.value is PenConnState.Connected) break
            }
            // Exhausted the retries without connecting → stop and return to a clean Disconnected
            // state (no endless loop); the user can re-scan and pick again.
            if (reconnecting && _state.value !is PenConnState.Connected) {
                reconnecting = false
                _state.value = PenConnState.Disconnected()
            }
        }
    }
}

/** Pen battery for the UI: level 0–100, plus minutes-to-full when charging (null otherwise). */
data class BatteryStatus(val percent: Int, val chargeEtaMinutes: Int?)

/** Lifecycle of a change/disable-password request, surfaced to the Settings UI. */
sealed interface PasswordOpState {
    data object Idle : PasswordOpState
    data object Working : PasswordOpState
    /** Finished: [success] true/false; [disabled] true if it was a disable (vs a change). */
    data class Done(val success: Boolean, val disabled: Boolean) : PasswordOpState
}

/** Lifecycle of a firmware update, surfaced to the Settings UI. */
sealed interface FirmwareUpdateState {
    data object Idle : FirmwareUpdateState
    data class Working(val percent: Int) : FirmwareUpdateState
    data class Done(val success: Boolean) : FirmwareUpdateState
}

/** Observable connection state for the UI. */
sealed interface PenConnState {
    data class Disconnected(val nothing: Unit = Unit) : PenConnState
    data class Connecting(val mac: String) : PenConnState
    data class Connected(val mac: String, val penName: String) : PenConnState
    data class Reconnecting(val mac: String, val attempt: Int, val nextDelayMs: Long) : PenConnState
    data class BondedElsewhere(val mac: String) : PenConnState
    data class PasswordRequired(
        val mac: String,
        val wrongAttempt: Boolean = false,
        val attemptsRemaining: Int? = null,
    ) : PenConnState
}
