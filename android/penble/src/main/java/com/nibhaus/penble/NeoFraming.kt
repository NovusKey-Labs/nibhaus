package com.nibhaus.penble

/**
 * NeoLAB pen wire-protocol framing (protocol 2.x), reimplemented from the observed byte stream — a
 * HDLC-style frame: `START | body | END`, with any `START`/`END`/`ESC` byte inside the body
 * byte-stuffed (`ESC` then `byte XOR 0x20`). This is the clean-room replacement for the GPL SDK's
 * `ProtocolParser20` framing layer; it carries NO command or decode logic — only delimiting and
 * byte-stuffing.
 *
 * A request body is `[cmd, lenLo, lenHi, payload…]` where `len` is the payload byte count
 * (little-endian, counting the *logical* payload, not its escaped expansion). Verified byte-exact
 * against packets captured from the real pen:
 * ```
 *   reqOfflineDataListAll -> C0 21 04 00 FF FF FF FF C1   (cmd 0x21, payload 4×0xFF)
 *   penOfflineDataSetup   -> C0 05 02 00 07 01 C1         (cmd 0x05, payload 07 01)
 * ```
 * Response/event bodies (from the pen) carry an extra error byte — `[cmd, error, lenLo, lenHi,
 * payload…]` — but parsing that is the command layer's job; this layer just hands back the
 * un-stuffed body of each complete frame.
 */
object NeoFraming {
    const val START = 0xC0
    const val END = 0xC1
    const val ESC = 0x7D
    const val ESC_XOR = 0x20
    const val MAX_PAYLOAD = 32768

    /** Build a request frame: `[cmd][len(2, LE)][payload]`, byte-stuffed and wrapped in START/END. */
    fun encodeRequest(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "payload ${payload.size} > $MAX_PAYLOAD" }
        val body = ByteArray(3 + payload.size)
        body[0] = cmd.toByte()
        body[1] = (payload.size and 0xFF).toByte()
        body[2] = ((payload.size ushr 8) and 0xFF).toByte()
        payload.copyInto(body, 3)
        return frame(body)
    }

    /** `START` + byte-stuffed(body) + `END`. */
    fun frame(body: ByteArray): ByteArray {
        val out = ArrayList<Byte>(body.size + 4)
        out.add(START.toByte())
        for (b in body) {
            when (b.toInt() and 0xFF) {
                START, END, ESC -> {
                    out.add(ESC.toByte())
                    out.add(((b.toInt() and 0xFF) xor ESC_XOR).toByte())
                }
                else -> out.add(b)
            }
        }
        out.add(END.toByte())
        return out.toByteArray()
    }
}

/**
 * Reassembles framed packets from a byte stream. BLE notifications arrive in arbitrary chunks (and
 * may pack several frames or split one across notifications), so this is a small stateful machine:
 * feed raw bytes, get back the un-stuffed body of every complete `START..END` frame seen so far.
 * Not thread-safe — drive it from one place (the GATT callback thread).
 */
class NeoFrameDecoder {
    private val buf = ArrayList<Byte>()
    private var inFrame = false
    private var escaped = false

    private companion object { const val MAX_BODY = NeoFraming.MAX_PAYLOAD + 3 } // [cmd][len:2] + payload cap

    /** Bodies of any frames completed by [chunk] (usually 0 or 1; can be more). */
    fun feed(chunk: ByteArray): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        for (b in chunk) {
            val v = b.toInt() and 0xFF
            if (escaped) {
                // The byte after ESC is real data, un-XOR it. (A genuine START/END is never escaped,
                // so this can't be confused with a delimiter.)
                buf.add((v xor NeoFraming.ESC_XOR).toByte())
                escaped = false
                if (buf.size > MAX_BODY) resyncOnOverflow()
                continue
            }
            when (v) {
                NeoFraming.START -> { inFrame = true; buf.clear() } // (re)sync on any unescaped START
                NeoFraming.END -> if (inFrame) {
                    frames.add(buf.toByteArray()); inFrame = false; buf.clear()
                }
                NeoFraming.ESC -> if (inFrame) escaped = true
                else -> if (inFrame) {
                    buf.add(b)
                    if (buf.size > MAX_BODY) resyncOnOverflow()
                }
            }
        }
        return frames
    }

    // A well-formed body is [cmd][len:2] + at most MAX_PAYLOAD bytes. A stream that runs longer without
    // an END is corrupt or hostile: drop the partial frame so `buf` can't grow without bound (a
    // malfunctioning or malicious pen can't exhaust memory) and resync on the next START.
    private fun resyncOnOverflow() { inFrame = false; escaped = false; buf.clear() }

    /** Drop any partial frame (e.g. on disconnect) so a reconnect starts clean. */
    fun reset() { buf.clear(); inFrame = false; escaped = false }
}
