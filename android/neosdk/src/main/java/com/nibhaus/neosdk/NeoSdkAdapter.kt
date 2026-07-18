package com.nibhaus.neosdk

import android.content.Context
import android.util.Log
import com.nibhaus.pen.NcodeAddress
import com.nibhaus.pen.NeoPenSdk
import com.nibhaus.pen.OfflineBatch
import com.nibhaus.pen.OfflinePoint
import com.nibhaus.pen.OfflineStroke
import com.nibhaus.pen.PenDot
import com.nibhaus.pen.PenListener
import com.nibhaus.pen.PenMessage
import com.nibhaus.pen.PenProtocol
import com.nibhaus.pen.PenTarget
import kr.neolab.sdk.ink.structure.Dot
import kr.neolab.sdk.pen.bluetooth.BTLEAdt
import kr.neolab.sdk.ink.structure.Stroke
import kr.neolab.sdk.pen.PenCtrl
import kr.neolab.sdk.pen.penmsg.IOfflineDataListener
import kr.neolab.sdk.pen.penmsg.IPenDotListener
import kr.neolab.sdk.pen.penmsg.IPenMsgListener
import kr.neolab.sdk.pen.penmsg.PenMsg
import kr.neolab.sdk.pen.penmsg.PenMsgType
import org.json.JSONArray

/**
 * The one and only bridge between NeoLAB's SDK and our clean [NeoPenSdk] boundary. Everything the
 * app sees comes from [com.nibhaus.pen]; nothing else imports `kr.neolab.sdk`. This is the seam we
 * progressively gut behind (android/STRANGLER.md).
 *
 * Verified on hardware (an Android 14+ phone, Android 15) against the real PenCtrl surface. Two findings baked
 * in: the pens are password-locked (we answer PASSWORD_REQUEST via [passwordFor]), and the SDK
 * needs the Android-14 `registerReceiver` patches (apply them to the SDK module — see README).
 *
 * @param passwordFor returns the pen's password for a given MAC (from your secure store), or null.
 */
class NeoSdkAdapter(
    context: Context,
    private val passwordFor: (penAddress: String) -> String? = { null },
) : NeoPenSdk {

    private val pen: PenCtrl = PenCtrl.getInstance().apply {
        setContext(context.applicationContext)
        // Ncode pens (M1+ / LAMY) connect over BLE. Without this the SDK defaults to classic-Bluetooth
        // SPP and connect() fails instantly (PEN_CONNECTION_FAILURE / type=3). setLeMode(true) selects
        // the BLE adapter. (setAdtMode(AdtMode.BLE) is the non-deprecated equivalent if needed.)
        setLeMode(true)
    }

    private var listener: PenListener? = null
    private var dotListener: ((PenDot) -> Unit)? = null
    private var offlineListener: ((OfflineBatch) -> Unit)? = null
    private var lastPenName: String = ""
    private var dotCount = 0
    /** Guards against a wrong stored password looping: we auto-answer from storage at most once per
     *  connection, then defer to the user prompt. Reset on every (re)connect and on disconnect. */
    private var autoTriedPassword = false

    override fun connect(target: PenTarget) {
        autoTriedPassword = false
        Log.i(TAG, "connect(spp=${target.sppAddress}, le=${target.leAddress}, ${target.protocol})")
        try {
            // The full overload — NOT connect(spp, le), which hardcodes UUID_VER.VER_2 (PenCtrl.java).
            // After GATT connects, BTLEAdt.onServicesDiscovered looks up its service by uuidVer; the
            // WRONG version finds no service and tears the link down (this is why the V5 LAMY safari
            // failed against VER_2). leAddress drives connectGatt(TRANSPORT_LE); sppAddress is the
            // pen's reported identity. appType 0x1201 / reqProtocol "2.18" match NeoLAB's own sample.
            val uuidVer = when (target.protocol) {
                PenProtocol.V5 -> BTLEAdt.UUID_VER.VER_5
                PenProtocol.V2 -> BTLEAdt.UUID_VER.VER_2
            }
            pen.connect(target.sppAddress, target.leAddress, uuidVer, 0x1201.toShort(), "2.18")
        } catch (e: Exception) {
            Log.w(TAG, "pen connection failed (${e.javaClass.simpleName})")
            listener?.onMessage(PenMessage.ConnectFailed(PenMessage.FailureReason.UNKNOWN))
        }
    }

    override fun disconnect() { Log.i(TAG, "disconnect()"); pen.disconnect() }
    override fun requestStatus() { runCatching { pen.reqPenStatus() } }
    override fun releaseExistingBond(macAddress: String) { pen.unpairDevice(macAddress) }
    override fun inputPassword(password: String) {
        Log.i(TAG, "inputPassword(len=${password.length})")
        pen.inputPassword(password)
    }

    /** True while a change/disable-password request is in flight, so we know the next generic
     *  PEN_SETUP_SUCCESS/FAILURE is the password result. The app issues no other setup ops, so this
     *  gate can't be confused by an unrelated setup reply. Note: if other setup ops are added,
     *  distinguish by the PEN_SETUP_* payload instead of a single in-flight flag. */
    private var passwordOpInFlight = false

    override fun changePassword(oldPassword: String, newPassword: String) {
        Log.i(TAG, "reqSetupPassword(old=${oldPassword.length}, new=${newPassword.length})")
        passwordOpInFlight = true
        runCatching { pen.reqSetupPassword(oldPassword, newPassword) }
            .onFailure {
                Log.w(TAG, "pen password setup failed (${it.javaClass.simpleName})")
                passwordOpInFlight = false
                listener?.onMessage(PenMessage.PasswordResult(false))
            }
    }

    override fun disablePassword(currentPassword: String) {
        Log.i(TAG, "reqSetUpPasswordOff(cur=${currentPassword.length})")
        passwordOpInFlight = true
        runCatching { pen.reqSetUpPasswordOff(currentPassword) }
            .onFailure {
                Log.w(TAG, "pen password removal failed (${it.javaClass.simpleName})")
                passwordOpInFlight = false
                listener?.onMessage(PenMessage.PasswordResult(false))
            }
    }

    override fun setListener(listener: PenListener) { this.listener = listener }
    override fun setDotListener(listener: (PenDot) -> Unit) { this.dotListener = listener }
    override fun setOfflineListener(listener: (OfflineBatch) -> Unit) { this.offlineListener = listener }

    override fun setAllowOfflineData(allow: Boolean) = pen.setAllowOfflineData(allow)
    override fun requestOfflineDataList() = pen.reqOfflineDataList()
    override fun requestOfflineData(section: Int, owner: Int, note: Int, deleteOnFinished: Boolean) =
        pen.reqOfflineData(section, owner, note, deleteOnFinished)

    override fun updateFirmware(file: java.io.File) {
        Log.i(TAG, "upgradePen(${file.name}, ${file.length()} bytes)")
        runCatching { pen.upgradePen(file) }
            .onFailure {
                Log.w(TAG, "pen firmware upgrade failed (${it.javaClass.simpleName})")
                listener?.onMessage(PenMessage.FirmwareResult(false))
            }
    }

    // --- NeoLAB SDK callbacks → our boundary ---

    private val msgListener = IPenMsgListener { penAddress, msg: PenMsg ->
        // The single most useful diagnostic: every raw message the SDK delivers, with its type +
        // payload, so a connect that stalls/loops shows exactly where it stops (success? failure?
        // password? nothing?). Read with: adb logcat -s NibhausPen
        Log.i(TAG, "PenMsg type=${msg.penMsgType} from=$penAddress content=${msg.content?.take(180)}")
        when (msg.penMsgType) {
            PenMsgType.PEN_FW_VERSION -> {
                lastPenName = nameFrom(msg.content) ?: lastPenName
                fwFrom(msg.content)?.let { listener?.onMessage(PenMessage.FirmwareVersion(it)) }
            }
            PenMsgType.PEN_FW_UPGRADE_STATUS ->
                progressFrom(msg.content)?.let { listener?.onMessage(PenMessage.FirmwareProgress(it)) }
            PenMsgType.PEN_FW_UPGRADE_SUCCESS ->
                listener?.onMessage(PenMessage.FirmwareResult(true))
            PenMsgType.PEN_FW_UPGRADE_FAILURE, PenMsgType.PEN_FW_UPGRADE_SUSPEND ->
                listener?.onMessage(PenMessage.FirmwareResult(false))
            PenMsgType.PEN_CONNECTION_SUCCESS ->
                listener?.onMessage(PenMessage.Connected(penAddress ?: "", lastPenName))
            PenMsgType.PEN_AUTHORIZED -> {
                // Authorized (after the password). The pen withholds live dots until the host
                // registers which notebooks it wants; reqAddUsingNoteAll() accepts ALL of them. This
                // is what NeoLAB's own sample does on PEN_AUTHORIZED — without it, writing streams no
                // ink. Also enable offline-page retrieval here.
                runCatching { pen.reqAddUsingNoteAll() }
                    .onFailure { Log.w(TAG, "pen note registration failed (${it.javaClass.simpleName})") }
                runCatching { pen.setAllowOfflineData(true) }
                Log.i(TAG, "authorized → registered all notebooks for live ink")
                listener?.onMessage(PenMessage.Connected(penAddress ?: "", lastPenName))
                batteryFrom(msg.content)?.let { listener?.onMessage(PenMessage.Battery(it)) }
            }
            PenMsgType.PEN_STATUS ->
                batteryFrom(msg.content)?.let { listener?.onMessage(PenMessage.Battery(it)) }
            PenMsgType.OFFLINE_DATA_NOTE_LIST -> {
                // reqOfflineDataList() answers with the notes the pen has stored offline — but the
                // LIST alone downloads nothing; each note must be pulled with reqOfflineData(). The pen
                // streams ONE note at a time, so requesting them in a tight loop makes a later request
                // supersede an in-flight one (only the last note arrives). Queue them and pull the next
                // only when the pen signals the current transfer ended (SUCCESS/FAILURE/ZERO below).
                // deleteOnFinished=false PRESERVES the pen's copy; strokes stream back via offlineSink
                // (IOfflineDataListener) and OfflineSync ingests them idempotently.
                val notes: JSONArray = runCatching { msg.contentByJSONArray }.getOrNull() ?: JSONArray()
                offlineQueue.clear()
                for (i in 0 until notes.length()) {
                    val n = notes.optJSONObject(i) ?: continue
                    offlineQueue.addLast(Triple(n.optInt("section_id"), n.optInt("owner_id"), n.optInt("note_id")))
                }
                Log.i(TAG, "offline note list: ${offlineQueue.size} note(s) → downloading sequentially")
                requestNextOfflineNote()
            }
            PenMsgType.OFFLINE_DATA_SEND_STATUS -> {
                // Per-note progress {total_size, received_size}. THIS pen signals a note's completion
                // by received_size reaching total_size — it sends NO separate SUCCESS message — so the
                // queue must advance here. onOfflineNoteComplete() ignores the duplicate 100% status.
                val obj = runCatching { msg.contentByJSONObject }.getOrNull()
                val total = obj?.optInt("total_size") ?: 0
                val received = obj?.optInt("received_size") ?: 0
                if (total > 0 && received >= total) onOfflineNoteComplete()
            }
            // Some firmware signals end-of-note explicitly (or a note had nothing / failed) — also advance.
            PenMsgType.OFFLINE_DATA_SEND_SUCCESS,
            PenMsgType.OFFLINE_DATA_SEND_FAILURE,
            PenMsgType.OFFLINE_DATA_SEND_ZERO -> onOfflineNoteComplete()
            PenMsgType.PEN_DISCONNECTED -> {
                autoTriedPassword = false
                listener?.onMessage(PenMessage.Disconnected)
            }
            PenMsgType.PEN_CONNECTION_FAILURE ->
                listener?.onMessage(PenMessage.ConnectFailed(PenMessage.FailureReason.UNKNOWN))
            PenMsgType.PEN_CONNECTION_FAILURE_BTDUPLICATE ->
                listener?.onMessage(PenMessage.ConnectFailed(PenMessage.FailureReason.BONDED_ELSEWHERE))
            PenMsgType.PEN_SETUP_SUCCESS ->
                if (passwordOpInFlight) {
                    passwordOpInFlight = false
                    listener?.onMessage(PenMessage.PasswordResult(true))
                }
            PenMsgType.PEN_SETUP_FAILURE ->
                if (passwordOpInFlight) {
                    passwordOpInFlight = false
                    listener?.onMessage(PenMessage.PasswordResult(false))
                }
            PenMsgType.PASSWORD_REQUEST -> {
                // Both target pens are isLock=true. Auto-answer ONCE from the stored password
                // (Keystore, or the -PpenPassword dev fallback); if that's missing or was wrong (the
                // pen re-asks), surface it so the user can type/retry in-app.
                val stored = passwordFor(penAddress ?: "")
                val tryAuto = stored != null && !autoTriedPassword
                Log.i(TAG, "PASSWORD_REQUEST (auto-answer=$tryAuto)")
                if (tryAuto) {
                    autoTriedPassword = true
                    pen.inputPassword(stored!!)
                } else {
                    listener?.onMessage(PenMessage.PasswordRequired())
                }
            }
        }
    }

    private val dotSink = IPenDotListener { _, dot: Dot ->
        if (dotCount++ % 50 == 0) {
            Log.d(TAG, "dot #$dotCount note=${dot.noteId} page=${dot.pageId} type=${dot.dotType}")
        }
        // Hover dots (pen near the paper but not touching) aren't ink — drop them so they never
        // start or extend a stroke.
        if (dot.dotType == HOVER_DOT_TYPE) return@IPenDotListener
        dotListener?.invoke(
            PenDot(
                section = dot.sectionId, owner = dot.ownerId, book = dot.noteId, page = dot.pageId,
                x = dot.x, y = dot.y,
                pressure = dot.pressure / 255f,            // SDK pressure is 0..max int
                phase = phaseOf(dot.dotType),
                timestamp = dot.timestamp, color = dot.color,
            ),
        )
    }

    /** Notes the pen reported as stored offline, pulled ONE AT A TIME (the pen streams a single note
     *  at a time; concurrent reqOfflineData calls clobber each other). Touched only on the SDK's
     *  single message-delivery thread, so it needs no extra synchronization. */
    private val offlineQueue = ArrayDeque<Triple<Int, Int, Int>>()
    // false while a note is mid-download; guards against the pen's duplicate "100%" status advancing
    // the queue twice (which would skip the next note).
    private var currentOfflineNoteDone = true
    private fun requestNextOfflineNote() {
        val next = offlineQueue.removeFirstOrNull()
        if (next == null) { currentOfflineNoteDone = true; return }
        currentOfflineNoteDone = false
        val (section, owner, note) = next
        Log.i(TAG, "offline: requesting note s=$section o=$owner n=$note (${offlineQueue.size} queued after)")
        runCatching { pen.reqOfflineData(section, owner, note, false) }
            .onFailure { Log.w(TAG, "offline pen data request failed (${it.javaClass.simpleName})"); requestNextOfflineNote() }
    }
    private fun onOfflineNoteComplete() {
        if (currentOfflineNoteDone) return // ignore duplicate / late end-of-note signals for a finished note
        currentOfflineNoteDone = true
        requestNextOfflineNote()
    }

    private val offlineSink =
        IOfflineDataListener { _, penAddress, strokes: Array<Stroke>, _, _, _, _ ->
            Log.i(TAG, "offline batch from=$penAddress strokes=${strokes.size}")
            val offline = strokes.mapNotNull { s -> s.toOfflineStroke() }
            offlineListener?.invoke(OfflineBatch(penAddress ?: "", offline))
        }

    // Registered after the listener properties above are initialized (Kotlin runs init blocks and
    // property initializers top-to-bottom, so this must come last).
    init {
        pen.setListener(msgListener)
        pen.setDotListener(dotSink)
        pen.setOffLineDataListener(offlineSink)
        Log.i(TAG, "adapter ready (BLE/LE mode)")
    }

    // Verified against the SDK enum on hardware — kr.neolab.sdk.ink.structure.DotType:
    // DOWN=17, MOVE=18, UP=20, HOVER=25. UP is what commits a stroke, so this mapping has to be exact.
    private fun phaseOf(dotType: Int): PenDot.Phase = when (dotType) {
        17 -> PenDot.Phase.DOWN
        20 -> PenDot.Phase.UP
        else -> PenDot.Phase.MOVE // 18 = MOVE
    }

    private fun Stroke.toOfflineStroke(): OfflineStroke? {
        val dots = this.dots ?: return null
        if (dots.isEmpty()) return null
        val first = dots.first()
        return OfflineStroke(
            section = first.sectionId, owner = first.ownerId, note = first.noteId, page = first.pageId,
            color = this.color,
            points = dots.map { OfflinePoint(it.x, it.y, it.pressure / 255f, it.timestamp) },
        )
    }

    /** Pull "battery":N (0–100) out of a PEN_STATUS / PEN_AUTHORIZED JSON payload. */
    private fun batteryFrom(content: String?): Int? =
        content?.let { Regex("\"battery\"\\s*:\\s*(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

    /** Pull "pen_fw_version" out of the PEN_FW_VERSION JSON content. */
    private fun fwFrom(content: String?): String? =
        content?.let { Regex("\"pen_fw_version\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.getOrNull(1) }

    /** Compute an upgrade percent from PEN_FW_UPGRADE_STATUS's "sent_size"/"total_size". */
    private fun progressFrom(content: String?): Int? {
        content ?: return null
        val sent = Regex("\"sent_size\"\\s*:\\s*(\\d+)").find(content)?.groupValues?.getOrNull(1)?.toLongOrNull()
        val total = Regex("\"total_size\"\\s*:\\s*(\\d+)").find(content)?.groupValues?.getOrNull(1)?.toLongOrNull()
        if (sent == null || total == null || total <= 0) return null
        return (sent * 100 / total).toInt().coerceIn(0, 100)
    }

    /** Pull "sub_name"/"device_name" out of the PEN_FW_VERSION JSON content for the notification. */
    private fun nameFrom(content: String?): String? {
        content ?: return null
        return Regex("\"sub_name\"\\s*:\\s*\"([^\"]+)\"").find(content)?.groupValues?.get(1)
            ?: Regex("\"device_name\"\\s*:\\s*\"([^\"]+)\"").find(content)?.groupValues?.get(1)
    }

    private companion object {
        const val TAG = "NibhausPen"
        const val HOVER_DOT_TYPE = 25 // DotType.PEN_ACTION_HOVER
    }
}
