package com.nibhaus.penble

/**
 * NeoLAB protocol-2.x command catalog, reimplemented from the observed protocol. Opcode values are
 * facts cross-checked against byte-exact request captures + the SDK's `CMD20` constants; no GPL
 * source is copied. Request opcodes are sent TO the pen; the matching response opcode is
 * `REQ | 0x80`. Dot/event opcodes arrive unsolicited FROM the pen.
 */
object NeoCmd {
    // --- Requests (TX) ---
    const val REQ_PEN_INFO = 0x01
    const val REQ_PASSWORD = 0x02
    const val REQ_PASSWORD_SET = 0x03
    const val REQ_PEN_STATUS = 0x04
    const val REQ_PEN_STATUS_CHANGE = 0x05
    const val REQ_USING_NOTE_NOTIFY = 0x11
    const val REQ_OFFLINE_NOTE_LIST = 0x21
    const val REQ_OFFLINE_DATA = 0x23
    const val ACK_OFFLINE_CHUNK = 0xA4 // host → pen, per-chunk acknowledgement

    // --- Responses (RX) = request | 0x80 ---
    const val RES_PEN_INFO = 0x81
    const val RES_PASSWORD = 0x82
    const val RES_PASSWORD_SET = 0x83 // reply to REQ_PASSWORD_SET (0x03); err byte 0 = the change was accepted
    const val RES_PEN_STATUS = 0x84
    const val RES_PEN_STATUS_CHANGE = 0x85
    const val RES_USING_NOTE_NOTIFY = 0x91
    const val RES_OFFLINE_NOTE_LIST = 0xA1
    const val RES_OFFLINE_DATA_REQ = 0xA3 // pen's reply to a download request (size/compress/count)
    const val RES_OFFLINE_CHUNK = 0x24 // note: NOT the |0x80 form — a distinct chunk-stream opcode

    // --- Unsolicited pen events (RX) ---
    const val EVT_PEN_UP_DOWN = 0x63
    const val EVT_DOT_DATA = 0x65
    const val EVT_PEN_DOWN = 0x69
    const val EVT_PEN_UP = 0x6A

    // --- PenStatusChange (0x05) sub-types — first payload byte ---
    const val SUB_CURRENT_TIME = 0x01
    const val SUB_OFFLINE_DATA_SAVE = 0x07

    /** The response opcode the pen replies with for a given request opcode. */
    fun responseOf(reqCmd: Int): Int = reqCmd or 0x80
}

/**
 * Builders for the request frames the pen expects. Each is verified byte-exact against a packet the
 * real SDK was observed to send (the hex in each comment is the captured frame). Framing is
 * delegated to [NeoFraming.encodeRequest] (`[cmd][len(2,LE)][payload]`, byte-stuffed).
 *
 * PenInfo (the connect handshake) and Password input are intentionally absent here — their payload
 * formats are captured live against hardware in the BLE-transport step rather than guessed.
 */
object NeoRequest {
    private val ALL2 = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
    private val ALL4 = ByteArray(4) { 0xFF.toByte() }

    /**
     * The connect handshake. 42-byte payload, decoded from a captured packet:
     *   [0..17] 18 reserved zero bytes · [18..19] appType (LE) · [20..35] appVersion ASCII, zero-padded
     *   to 16 · [36..41] protocol ASCII, zero-padded to 6.
     * `penInfo()` reproduces the captured `C0 01 2A 00 …(appType 0x1201, "0.1.0", "2.18")… C1` exactly.
     */
    fun penInfo(appVersion: String = "0.1.0", protocol: String = "2.18", appType: Int = 0x1201): ByteArray {
        val p = ByteArray(42)
        p[18] = (appType and 0xFF).toByte()
        p[19] = ((appType ushr 8) and 0xFF).toByte()
        val v = appVersion.encodeToByteArray()
        v.copyInto(p, 20, 0, minOf(v.size, 16))
        val pr = protocol.encodeToByteArray()
        pr.copyInto(p, 36, 0, minOf(pr.size, 6))
        return NeoFraming.encodeRequest(NeoCmd.REQ_PEN_INFO, p)
    }

    /** Answer the lock challenge. Password is ASCII (the SDK uses String.getBytes()) in a fixed
     *  16-byte field. e.g. "1234" -> C0 02 10 00 31 32 33 34 00×12 C1 */
    fun password(password: String): ByteArray {
        val field = ByteArray(16)
        val bytes = password.encodeToByteArray()
        bytes.copyInto(field, 0, 0, minOf(bytes.size, 16))
        return NeoFraming.encodeRequest(NeoCmd.REQ_PASSWORD, field)
    }

    /** Change the pen's unlock password (old → new). 33-byte payload, captured byte-exact from Neo
     *  Studio 2's own traffic (HCI snoop, LAMY pen, 2026-07-03): [0] = 0x01 change-flag, [1..16] =
     *  old password, [17..32] = new password — each ASCII, zero-padded to 16 (same field as
     *  [password]). e.g. old "1234" / new "5678" ->
     *  C0 03 21 00 01 31 32 33 34 00×12 35 36 37 38 00×12 C1 */
    fun setPassword(oldPassword: String, newPassword: String): ByteArray {
        val p = ByteArray(33)
        p[0] = 0x01
        val old = oldPassword.encodeToByteArray(); old.copyInto(p, 1, 0, minOf(old.size, 16))
        val neu = newPassword.encodeToByteArray(); neu.copyInto(p, 17, 0, minOf(neu.size, 16))
        return NeoFraming.encodeRequest(NeoCmd.REQ_PASSWORD_SET, p)
    }

    /** Ask for current pen status (battery, lock, offline-save). -> C0 04 00 00 C1 */
    fun penStatus(): ByteArray = NeoFraming.encodeRequest(NeoCmd.REQ_PEN_STATUS)

    /** Set the pen's clock. Neo firmware requires this after auth before it will accept operational
     *  commands — without it the pen rejects using-note / offline / status-change with err=2 while
     *  still answering read-only status queries. Payload: sub-type 0x01, then epoch time as 8-byte LE
     *  milliseconds. -> C0 05 09 00 01 <ms:8LE> C1 */
    fun setCurrentTime(nowMillis: Long): ByteArray {
        val p = ByteArray(9)
        p[0] = NeoCmd.SUB_CURRENT_TIME.toByte()
        for (i in 0 until 8) p[1 + i] = ((nowMillis ushr (8 * i)) and 0xFF).toByte()
        return NeoFraming.encodeRequest(NeoCmd.REQ_PEN_STATUS_CHANGE, p)
    }

    /** Register ALL notebooks for live ink (must be sent on authorize, or the pen streams no dots).
     *  -> C0 11 02 00 FF FF C1 */
    fun usingAllNotes(): ByteArray = NeoFraming.encodeRequest(NeoCmd.REQ_USING_NOTE_NOTIFY, ALL2)

    /** List ALL notes stored offline. -> C0 21 04 00 FF FF FF FF C1 */
    fun offlineNoteList(): ByteArray = NeoFraming.encodeRequest(NeoCmd.REQ_OFFLINE_NOTE_LIST, ALL4)

    /** Toggle the pen's offline-data saving. on=true -> C0 05 02 00 07 01 C1 */
    fun offlineDataSave(on: Boolean): ByteArray = NeoFraming.encodeRequest(
        NeoCmd.REQ_PEN_STATUS_CHANGE,
        byteArrayOf(NeoCmd.SUB_OFFLINE_DATA_SAVE.toByte(), (if (on) 1 else 0).toByte()),
    )

    /**
     * Download one stored note's strokes. 14-byte payload (verified byte-exact vs the SDK builder):
     *   [0] deleteFlag (1 = delete after, 2 = keep) · [1] 0x01 · [2..4] owner (3-byte LE) · [5] section
     *   · [6..9] note (4-byte LE) · [10..13] page-count = 0 (= the whole note).
     * `delete=false` preserves the pen's copy.
     */
    fun offlineData(section: Int, owner: Int, note: Int, delete: Boolean): ByteArray {
        val p = ByteArray(14)
        p[0] = (if (delete) 1 else 2).toByte()
        p[1] = 0x01
        p[2] = (owner and 0xFF).toByte()
        p[3] = ((owner ushr 8) and 0xFF).toByte()
        p[4] = ((owner ushr 16) and 0xFF).toByte()
        p[5] = section.toByte()
        p[6] = (note and 0xFF).toByte()
        p[7] = ((note ushr 8) and 0xFF).toByte()
        p[8] = ((note ushr 16) and 0xFF).toByte()
        p[9] = ((note ushr 24) and 0xFF).toByte()
        // [10..13] page-count 0 ⇒ whole note
        return NeoFraming.encodeRequest(NeoCmd.REQ_OFFLINE_DATA, p)
    }

    /**
     * Acknowledge one received offline chunk so the pen sends the next. Uses the SDK's 2-arg packet
     * shape (an extra header byte after cmd): `C0 A4 00 03 00 <chunkPacketId LE16> <status> C1`, where
     * status = 0 on the last chunk (position==2), 1 otherwise. Built byte-exact from the RE'd builder.
     */
    fun offlineChunkAck(chunkPacketId: Int, lastChunk: Boolean): ByteArray = NeoFraming.frame(
        byteArrayOf(
            NeoCmd.ACK_OFFLINE_CHUNK.toByte(),
            0x00,       // 2-arg builder's extra header byte (arg0 = 0)
            0x03, 0x00, // payload length = 3 (LE)
            (chunkPacketId and 0xFF).toByte(),
            ((chunkPacketId ushr 8) and 0xFF).toByte(),
            (if (lastChunk) 0 else 1).toByte(),
        ),
    )
}

/** A note the pen has stored offline, from the note-list response. */
data class NeoOfflineNote(val section: Int, val owner: Int, val note: Int)

/**
 * Parse a RES_OfflineNoteList (0xA1) payload (the bytes after the `[cmd,err,lenLo,lenHi]` header):
 * `[0..1]` count (LE16), then `count` 8-byte records: `[0..2]` owner (LE), `[3]` section, `[4..7]`
 * note (LE32). Bounds-guarded; returns what it can. (Layout reconstructed from RE — confirmed on-device.)
 */
fun parseOfflineNoteList(payload: ByteArray): List<NeoOfflineNote> {
    if (payload.size < 2) return emptyList()
    val count = (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
    val out = ArrayList<NeoOfflineNote>(count)
    var base = 2
    repeat(count) {
        if (base + 8 > payload.size) return out
        fun u8(o: Int) = payload[o].toInt() and 0xFF
        val owner = u8(base) or (u8(base + 1) shl 8) or (u8(base + 2) shl 16)
        val section = u8(base + 3)
        val note = u8(base + 4) or (u8(base + 5) shl 8) or (u8(base + 6) shl 16) or (u8(base + 7) shl 24)
        out.add(NeoOfflineNote(section, owner, note))
        base += 8
    }
    return out
}

/** A parsed inbound frame from the pen. */
class NeoInbound(val cmd: Int, val error: Int, val payload: ByteArray)

/**
 * Parse an un-stuffed inbound body (from [NeoFrameDecoder]) into cmd / error / payload. Inbound
 * (response + event) bodies carry an error byte the request frames don't: `[cmd, error, lenLo,
 * lenHi, payload…]`. Returns null if the body is too short to hold a header.
 *
 * NOTE: the error-byte position + 2-byte length are taken from the SDK's documented RX header; the
 * exact dot-event payload layout (the only place this matters beyond cmd/error) is validated against
 * captured hardware frames in the dot-decode step.
 */
fun parseInbound(body: ByteArray): NeoInbound? {
    if (body.size < 4) return null
    val cmd = body[0].toInt() and 0xFF
    val error = body[1].toInt() and 0xFF
    val len = (body[2].toInt() and 0xFF) or ((body[3].toInt() and 0xFF) shl 8)
    val end = minOf(4 + len, body.size)
    return NeoInbound(cmd, error, body.copyOfRange(4, end))
}

/**
 * Whether a RES_PASSWORD (0x82) unlock reply says the entered password was correct. The frame's err
 * byte only means "frame received"; the real result is payload[0]: 0x01 = correct, 0x00 = wrong (a
 * correct password replies `01 00 0a`, a wrong one `00 06 0a`; payload[1]/[2] = failed attempts used
 * / max = 0x0a = 10). Confirmed byte-exact from Neo Studio HCI captures. Reading the err byte instead
 * accepted ANY password and left the pen unauthorized, so no ink ever streamed.
 */
fun passwordUnlockCorrect(payload: ByteArray): Boolean =
    payload.isNotEmpty() && payload[0].toInt() == 0x01

/**
 * Whether a RES_PASSWORD_SET (0x83) set/change reply says the change took. The set reply uses the
 * OPPOSITE marker from the unlock reply: success is payload[0] == 0x00 (captured byte-exact — two
 * independent old->new changes both reply `00 0a 00`, and immediately re-unlocking with the new
 * password returns 01 = correct, proving it applied). Reusing the unlock's 0x01 marker here reported
 * every successful change as a failure.
 */
fun passwordChangeSucceeded(payload: ByteArray): Boolean =
    payload.isNotEmpty() && payload[0].toInt() == 0x00
