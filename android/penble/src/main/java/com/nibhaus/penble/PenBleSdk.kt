package com.nibhaus.penble

import android.content.Context
import android.util.Log
import com.nibhaus.pen.NeoPenSdk
import com.nibhaus.pen.OfflineBatch
import com.nibhaus.pen.OfflineStroke
import com.nibhaus.pen.PenDot
import com.nibhaus.pen.PenListener
import com.nibhaus.pen.PenMessage
import com.nibhaus.pen.PenProtocol
import com.nibhaus.pen.PenTarget
import java.io.File

/**
 * Clean-room, GPL-FREE [NeoPenSdk] driver. Drives [NeoGattTransport] through the NeoLAB protocol-2.x
 * handshake and raises the [PenMessage]s the rest of the app already understands. It is constructed
 * reflectively by ServiceLocator with the SAME `(Context, passwordProvider)` signature as the GPL
 * adapter, so it drops into `loadRealPenSdk()` behind a build flag for side-by-side A/B testing.
 *
 * STATUS (android/STRANGLER.md): connect + PenInfo handshake + password auth + live-dot decoding
 * (via [NeoDotDecoder]) + offline-note download (note list → per-note chunk stream → zlib-inflate →
 * [NeoOfflineDecoder] → [OfflineBatch]) are all wired and validated on hardware (full 377+298-stroke
 * recovery, byte-identical to the GPL adapter). This driver now covers the entire NeoPenSdk contract
 * with zero GPL code, so the quarantined :neosdk module can be retired.
 */
class PenBleSdk(
    private val context: Context,
    @Suppress("unused") private val passwordProvider: (String) -> String?,
) : NeoPenSdk {
    private val tag = "PenBle"

    private var listener: PenListener? = null
    private var dotListener: ((PenDot) -> Unit)? = null
    private var offlineListener: ((OfflineBatch) -> Unit)? = null
    private var rssiListener: ((Int) -> Unit)? = null
    /** Whether a consumer has asked for RSSI (Find My Pen). Remembered across reconnects so polling
     *  resumes automatically once the new transport is ready — the consumer only calls start/stop
     *  once per screen visit, not per reconnect. */
    @Volatile private var rssiPollRequested = false
    /** True between a changePassword() send and its RES_PASSWORD reply, so that reply is routed to a
     *  PasswordResult (change outcome) instead of the unlock-and-authorize path. @Volatile: written on
     *  the caller thread, read on the BLE-callback thread. Reset on (dis)connect so an interrupted
     *  change from a prior session can't misroute the NEXT session's unlock reply. */
    @Volatile private var passwordOpInFlight = false

    @Volatile private var transport: NeoGattTransport? = null
    @Volatile private var target: PenTarget? = null
    @Volatile private var penName = "Neo smartpen"
    private val dotDecoder = NeoDotDecoder()

    // Offline download state: notes are pulled one at a time; each note's chunks accumulate then flush.
    private val offlineNotes = ArrayDeque<NeoOfflineNote>()
    private val offlineAccum = mutableListOf<OfflineStroke>()
    private val maxPress = NeoOfflineDecoder.MAX_PRESS_DEFAULT

    override fun setListener(listener: PenListener) { this.listener = listener }
    override fun setDotListener(listener: (PenDot) -> Unit) { this.dotListener = listener }
    override fun setOfflineListener(listener: (OfflineBatch) -> Unit) { this.offlineListener = listener }

    override fun connect(target: PenTarget) {
        this.target = target
        dotDecoder.reset()
        offlineNotes.clear()
        offlineAccum.clear()
        passwordOpInFlight = false // an interrupted change from a prior session must not misroute this unlock
        timeSetInFlight = false
        awaitingLockPrompt = false
        // Close any prior GATT client first — the reconnect loop calls connect() repeatedly without an
        // intervening disconnect(), and Android caps concurrent GATT clients process-wide (~4-7); leaking
        // one per reconnect makes the pen unconnectable (status 133) until the app is force-killed.
        transport?.close()
        Log.i(tag, "connect spp=${target.sppAddress} le=${target.leAddress} ${target.protocol}")
        NeoGattTransport(context, target.protocol, transportListener).also {
            transport = it
            it.connect(target.leAddress)
        }
    }

    override fun disconnect() {
        passwordOpInFlight = false
        transport?.close()
        transport = null
        listener?.onMessage(PenMessage.Disconnected)
    }

    override fun requestStatus() = send(NeoRequest.penStatus())
    override fun setAllowOfflineData(allow: Boolean) = send(NeoRequest.offlineDataSave(allow))
    override fun requestOfflineDataList() = send(NeoRequest.offlineNoteList())
    override fun requestOfflineData(section: Int, owner: Int, note: Int, deleteOnFinished: Boolean) =
        send(NeoRequest.offlineData(section, owner, note, deleteOnFinished))

    private fun send(frame: ByteArray) { transport?.send(frame) }

    override fun inputPassword(password: String) = send(NeoRequest.password(password))

    // --- RSSI (Find My Pen, #20b) ---
    override fun startRssiPolling() {
        rssiPollRequested = true
        transport?.startRssiPolling()
    }
    override fun stopRssiPolling() {
        rssiPollRequested = false
        transport?.stopRssiPolling()
    }
    override fun setRssiListener(listener: (Int) -> Unit) { rssiListener = listener }

    override fun changePassword(oldPassword: String, newPassword: String) {
        passwordOpInFlight = true
        send(NeoRequest.setPassword(oldPassword, newPassword))
    }
    // The pen has no dedicated disable command (Neo Studio 2 offers none; the only way to clear a
    // password is a destructive factory reset), so there is nothing to send — report unsupported.
    override fun disablePassword(currentPassword: String) =
        listener?.onMessage(PenMessage.PasswordResult(success = false)) ?: Unit

    // --- not yet implemented (firmware milestone) ---
    override fun updateFirmware(file: File) { Log.w(tag, "updateFirmware: not yet implemented") }
    override fun releaseExistingBond(macAddress: String) { /* LE link has no classic bond to release */ }

    /** RES_PenInfo (decoded from a captured frame): [0..15] model · [16..31] fw · [32..39] protocol ·
     *  [40..55] name · [56] lock flag (1 = locked) · [58..63] MAC · [64] sensor. */
    private fun onPenInfo(payload: ByteArray) {
        penName = asciiField(payload, 40, 16).ifBlank { "Neo smartpen" }
        asciiField(payload, 16, 16).takeIf { it.isNotBlank() }
            ?.let { listener?.onMessage(PenMessage.FirmwareVersion(it)) }
        val locked = payload.size > 56 && payload[56].toInt() != 0
        if (locked) {
            // Defer Connected until AFTER auth: it flips PenConnState to Connected, which fires the
            // one-shot offline-sync — issuing it while locked gets rejected and never retries.
            // Query status first so the very first prompt already shows attempts-remaining, like Neo
            // Studio (PenStatus 0x84 payload[2] = attempts used, [1] = max 10). See onPenStatus.
            Log.i(tag, "pen locked → querying attempt count, then requesting password")
            awaitingLockPrompt = true
            send(NeoRequest.penStatus())
        } else {
            sendSetTimeThenRegister()
        }
    }

    @Volatile private var timeSetInFlight = false
    /** True between a locked-connect and the first PenStatus reply, so that status raises the initial
     *  password prompt (with the attempts-remaining count) rather than just updating the battery. */
    @Volatile private var awaitingLockPrompt = false

    /** After correct auth, set the pen clock — the pen refuses every operational command (err=2) until
     *  its clock is set, while still answering read-only status. Confirmed byte-exact against a Neo
     *  Studio HCI capture (`C0 05 09 00 01 <ms:8LE> C1`). We wait for its ack ([onStatusChangeResult])
     *  before announcing Connected and registering for ink. */
    private fun sendSetTimeThenRegister() {
        timeSetInFlight = true
        Log.i(tag, "sending SetCurrentTime")
        send(NeoRequest.setCurrentTime(System.currentTimeMillis()))
    }

    /** RES_PenStatusChange (0x85): ack for a status-change request. The first after auth is our
     *  SetCurrentTime; once it lands the pen is initialized, so announce Connected and register for ink
     *  with the confirmed `usingAllNotes()` filter (Neo Studio sends the same frame). */
    private fun onStatusChangeResult(error: Int) {
        if (!timeSetInFlight) return // an ordinary offline-save ack — nothing to do here
        timeSetInFlight = false
        Log.i(tag, "SetCurrentTime result err=$error — announcing connected + registering for ink")
        target?.let { listener?.onMessage(PenMessage.Connected(it.sppAddress, penName)) }
        send(NeoRequest.usingAllNotes())
    }

    /** RES_UsingNoteNotify (0x91): err 0 = the pen accepted the filter and will stream live ink. The
     *  earlier err=2 was never a format problem — it was the pen refusing everything because auth had
     *  silently failed (wrong password accepted); see [onPasswordResult]. */
    private fun onUsingNoteResult(error: Int) {
        if (error == 0) Log.i(tag, "using-note accepted — live ink streaming enabled")
        else Log.w(tag, "using-note rejected (err=$error) — no ink; check that auth actually succeeded")
    }

    /** RES_PenStatus (0x84): battery %, used-memory %, and RTC/timer fields. Byte offsets confirmed on
     *  a real M1+ against Neo Studio via an HCI-snoop capture of the official app's own traffic: the
     *  RES_PenStatus payload read payload[20]=0x42=66 while Neo Studio displayed 66% battery, and
     *  payload[21]=0x01=1% used (99% free) matched its 99% storage bar. We surface only battery; the
     *  rest (RTC at [3..10], timers, flags) isn't shown. Battery is a plain 0–100 byte here (this pen
     *  wasn't charging — a charging encoding, if any, is unconfirmed, so we just clamp to 0–100). */
    private fun onPenStatus(payload: ByteArray) {
        if (payload.size <= 20) return
        listener?.onMessage(PenMessage.Battery((payload[20].toInt() and 0xFF).coerceIn(0, 100)))
        // Connect-time attempt counter (decoded from a Neo Studio capture): payload[2] = attempts used,
        // max = 10. On the first status after a locked connect, raise the prompt already showing the
        // remaining count, so the user sees it before their first try (like Neo Studio does).
        if (awaitingLockPrompt) {
            awaitingLockPrompt = false
            val remaining = payload.getOrNull(2)?.toInt()?.and(0xFF)?.let { 10 - it }
            Log.i(tag, "pen locked, $remaining attempts remaining → requesting password")
            listener?.onMessage(PenMessage.PasswordRequired(wrongAttempt = false, attemptsRemaining = remaining))
        }
    }

    /** RES_Password (0x82) / RES_PasswordSet (0x83). The err byte only means "frame received"; the real
     *  result is in payload[0], and the two replies use OPPOSITE markers — see [passwordUnlockCorrect]
     *  and [passwordChangeSucceeded] for the byte-exact decode. passwordOpInFlight is set only while a
     *  change is outstanding, so it tells a 0x83 change reply apart from a 0x82 unlock reply. */
    private fun onPasswordResult(payload: ByteArray) {
        if (passwordOpInFlight) {
            passwordOpInFlight = false
            val changed = passwordChangeSucceeded(payload)
            Log.i(tag, "password change result (success=$changed, payload=${hex(payload, 8)})")
            listener?.onMessage(PenMessage.PasswordResult(success = changed))
            return
        }
        val ok = passwordUnlockCorrect(payload)
        if (ok) {
            Log.i(tag, "password ACCEPTED (correct) → authorized; setting clock + registering for ink")
            sendSetTimeThenRegister()
        } else {
            val usedRaw = payload.getOrNull(1)?.toInt()?.and(0xFF)
            val used = usedRaw ?: -1
            val max = payload.getOrNull(2)?.toInt()?.and(0xFF) ?: -1
            Log.w(tag, "password REJECTED (wrong) — $used of $max attempts used before a destructive reset")
            listener?.onMessage(
                PenMessage.PasswordRequired(wrongAttempt = true, attemptsRemaining = usedRaw?.let { 10 - it }),
            )
        }
    }

    // --- Offline download (note list → per-note chunk stream → OfflineBatch) ---

    /** RES_OfflineNoteList: enqueue every stored note, then pull them one at a time. */
    private fun onOfflineNoteList(payload: ByteArray) {
        val notes = parseOfflineNoteList(payload)
        Log.i(tag, "offline note list: ${notes.size} note(s) raw=${hex(payload, 24)}")
        offlineNotes.clear()
        offlineNotes.addAll(notes)
        offlineAccum.clear()
        requestNextOfflineNote()
    }

    /** Request the next queued note's offline data (delete=false preserves the pen's copy). */
    private fun requestNextOfflineNote() {
        val n = offlineNotes.firstOrNull() ?: run { Log.i(tag, "offline download complete"); return }
        Log.i(tag, "offline request note s=${n.section} o=${n.owner} note=${n.note}")
        offlineAccum.clear()
        send(NeoRequest.offlineData(n.section, n.owner, n.note, delete = false))
    }

    /** RES_OfflineChunk: decode this chunk's strokes, ACK it, and flush the note on the last chunk. */
    private fun onOfflineChunk(packetId: Int, payload: ByteArray) {
        if (payload.size < 17) { Log.w(tag, "offline chunk too short: ${payload.size}"); return }
        val position = payload[6].toInt() and 0xFF // 0 = first, 1 = more, 2 = last
        Log.i(tag, "offline chunk pkt=$packetId pos=$position len=${payload.size}")
        offlineAccum.addAll(NeoOfflineDecoder.parse(payload, maxPress))
        send(NeoRequest.offlineChunkAck(packetId, lastChunk = position == 2))
        if (position == 2) {
            Log.i(tag, "offline note done: ${offlineAccum.size} stroke(s)")
            offlineListener?.invoke(OfflineBatch(target?.sppAddress ?: "", offlineAccum.toList()))
            offlineAccum.clear()
            offlineNotes.removeFirstOrNull()
            requestNextOfflineNote()
        }
    }

    /** Hex-dump the first [n] bytes of a payload for on-device protocol validation. */
    private fun hex(b: ByteArray, n: Int) =
        b.take(n).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    /** Read a zero-terminated, fixed-width ASCII field out of a frame payload. */
    private fun asciiField(b: ByteArray, off: Int, len: Int): String {
        if (off >= b.size) return ""
        val end = minOf(off + len, b.size)
        var n = off
        while (n < end && b[n].toInt() != 0) n++
        return String(b, off, n - off, Charsets.US_ASCII)
    }

    private val transportListener = object : NeoTransportListener {
        override fun onReady() {
            // Neo Studio declares a different pen-info protocol per pen generation, captured from its
            // own handshakes: V2/M1+ = 2.18, V5/LAMY = 2.22. Match it per-pen so each pen answers in
            // the dialect it expects. The V2/M1+ request is unchanged (its 2.18 path is verified); only
            // V5/LAMY now sends 2.22, mirroring Neo Studio. target.protocol comes from the advertised
            // service UUID, so it's known before the handshake goes out.
            val protocol = if (target?.protocol == PenProtocol.V5) "2.22" else "2.18"
            Log.i(tag, "transport ready → sending PenInfo handshake (protocol=$protocol)")
            send(NeoRequest.penInfo(protocol = protocol))
            // Resume RSSI polling across a reconnect if a consumer asked for it before the drop.
            if (rssiPollRequested) transport?.startRssiPolling()
        }

        override fun onFrame(body: ByteArray) {
            if (body.isEmpty()) return
            val cmd = body[0].toInt() and 0xFF
            if (NeoDotDecoder.isEvent(cmd)) {
                // Event frame: [cmd, lenLo, lenHi, payload] — NO error byte. Decode live ink dots.
                if (body.size < 3) return
                val len = (body[1].toInt() and 0xFF) or ((body[2].toInt() and 0xFF) shl 8)
                val payload = body.copyOfRange(3, minOf(3 + len, body.size))
                dotDecoder.decode(cmd, payload)?.let { dotListener?.invoke(it) }
                return
            }
            if (cmd == NeoCmd.RES_OFFLINE_CHUNK) {
                // 0x24's [2..3] is NOT a payload length (it under-reports), so parseInbound would
                // truncate the chunk. The HDLC frame already delimits it — use the whole body after
                // the [cmd, packetId, _, _] header. body[1] is the per-chunk id echoed in the ACK.
                if (body.size > 4) onOfflineChunk(body[1].toInt() and 0xFF, body.copyOfRange(4, body.size))
                return
            }
            val msg = parseInbound(body) ?: return
            Log.i(tag, "RX cmd=0x%02X err=%d len=%d".format(msg.cmd, msg.error, msg.payload.size))
            when (msg.cmd) {
                NeoCmd.RES_PEN_INFO -> onPenInfo(msg.payload)
                // 0x82 = reply to an unlock (0x02); 0x83 = reply to a set/change (0x03). Both carry the
                // result in the error byte (0 = accepted) and route to the same handler —
                // passwordOpInFlight tells them apart (set/change in flight → report; else → authorize).
                NeoCmd.RES_PASSWORD, NeoCmd.RES_PASSWORD_SET -> onPasswordResult(msg.payload)
                NeoCmd.RES_USING_NOTE_NOTIFY -> onUsingNoteResult(msg.error)
                NeoCmd.RES_PEN_STATUS_CHANGE -> onStatusChangeResult(msg.error)
                NeoCmd.RES_PEN_STATUS -> onPenStatus(msg.payload)
                NeoCmd.RES_OFFLINE_NOTE_LIST -> onOfflineNoteList(msg.payload)
                NeoCmd.RES_OFFLINE_DATA_REQ -> Log.i(tag, "offline note start: hdr=${hex(msg.payload, 12)}")
            }
        }

        override fun onRssi(rssi: Int) { rssiListener?.invoke(rssi) }

        override fun onDisconnected() { listener?.onMessage(PenMessage.Disconnected) }

        override fun onError(stage: String, status: Int) {
            Log.w(tag, "transport error @$stage status=$status")
            listener?.onMessage(PenMessage.ConnectFailed(PenMessage.FailureReason.UNKNOWN))
        }
    }
}
