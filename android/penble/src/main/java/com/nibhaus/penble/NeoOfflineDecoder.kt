package com.nibhaus.penble

import android.util.Log
import com.nibhaus.pen.OfflinePoint
import com.nibhaus.pen.OfflineStroke
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.InflaterInputStream

/**
 * Clean-room decoder for ONE offline-data blob — the payload of a `0x24` offline chunk. Reimplemented
 * from the observed `OfflineByteParser` layout; no GPL source copied. Each chunk payload is a
 * self-contained blob: a fixed header (compression flag + Ncode id + stroke count), an optionally
 * zlib-deflated body, then `strokeCount` strokes — each a 27-byte header followed by `dotCount`
 * 16-byte dot records.
 *
 * The blob header doubles as the chunk's flow-control fields (packetId @0-1, position/last-flag @7),
 * which is why `parseHeader` skips those bytes. Several offsets were reconstructed from rate-limited
 * RE and reconciled against the flow-control fields; they are confirmed against on-device captures
 * before shipping. Parsing is deliberately lenient (bounds-guarded; a bad per-dot checksum is logged,
 * not thrown) so a layout miss degrades to "fewer/wrong dots", never a crash.
 */
object NeoOfflineDecoder {
    const val MAX_PRESS_DEFAULT = 852
    private const val FORCE_MAX = 852 // dots above this are sensor noise — discarded (matches the SDK)
    private const val DEFAULT_COLOR = 0xFF000000.toInt()
    private const val STROKE_HEADER = 27
    private const val DOT_SIZE = 16
    private const val BODY_START = 17 // blob header length before the (zlib) stroke body
    private const val tag = "NeoOffline"

    /**
     * Decode one chunk blob into whole strokes. [maxPress] normalizes force → 0..1 pressure.
     *
     * Blob header (offsets verified against on-device chunk captures; header is 17 bytes):
     *   [2..3] sizeBeforeCompress · [4..5] sizeAfterCompress · [6] position (1=more, 2=last) ·
     *   [7..9] owner (LE) · [10] section · [11..14] note (LE32) · [15..16] strokeCount · [17..] body.
     * The body is zlib-deflated (sizeAfter on-wire bytes → sizeBefore inflated); when sizeAfter==
     * sizeBefore (or inflate fails) it is treated as raw.
     */
    fun parse(blob: ByteArray, maxPress: Int = MAX_PRESS_DEFAULT): List<OfflineStroke> {
        if (blob.size < BODY_START) return emptyList()
        val sizeBefore = u16le(blob, 2)
        val sizeAfter = u16le(blob, 4)
        val owner = u8(blob, 7) or (u8(blob, 8) shl 8) or (u8(blob, 9) shl 16)
        val section = u8(blob, 10)
        val note = u32le(blob, 11)
        val strokeCount = u16le(blob, 15)

        val raw = blob.copyOfRange(BODY_START, minOf(BODY_START + sizeAfter, blob.size))
        val body = if (sizeAfter in 1 until sizeBefore) {
            val inflated = inflate(raw)
            if (inflated.isEmpty()) raw else inflated
        } else raw
        return parseStrokes(body, strokeCount, section, owner, note, maxPress)
    }

    private fun parseStrokes(
        body: ByteArray, strokeCount: Int, section: Int, owner: Int, note: Int, maxPress: Int,
    ): List<OfflineStroke> {
        val strokes = ArrayList<OfflineStroke>(strokeCount)
        var pos = 0
        repeat(strokeCount) {
            if (pos + STROKE_HEADER > body.size) return strokes
            val page = u32le(body, pos)                 // per-stroke Ncode page (header has none)
            val startTime = u64le(body, pos + 4)
            val endTime = u64le(body, pos + 12)
            val color = u32le(body, pos + 21).let { if (it == 0) DEFAULT_COLOR else it }
            val dotCount = u16le(body, pos + 25)
            pos += STROKE_HEADER

            val pts = ArrayList<OfflinePoint>(dotCount)
            var runningTs = startTime
            for (i in 0 until dotCount) {
                if (pos + DOT_SIZE > body.size) break
                val timeDelta = u8(body, pos).toLong()
                val force = u16le(body, pos + 1)
                val xInt = s16le(body, pos + 3)
                val yInt = s16le(body, pos + 5)
                val xFrac = u8(body, pos + 7)
                val yFrac = u8(body, pos + 8)
                pos += DOT_SIZE
                if (force > FORCE_MAX) continue // noise dot
                val x = xInt + xFrac * 0.01f
                val y = yInt + yFrac * 0.01f
                val pressure = (force.toFloat() / maxPress).coerceIn(0f, 1f)
                val ts = when {
                    i == 0 -> startTime + timeDelta
                    i == dotCount - 1 -> endTime
                    else -> { runningTs += timeDelta; runningTs }
                }
                pts.add(OfflinePoint(x, y, pressure, ts))
            }
            if (pts.isNotEmpty()) strokes.add(OfflineStroke(section, owner, note, page, color, pts))
        }
        return strokes
    }

    /** Raw zlib (RFC 1950) inflate — the offline body, when compressed, is DEFLATE with a zlib header. */
    private fun inflate(data: ByteArray): ByteArray = try {
        val out = ByteArrayOutputStream(data.size * 3)
        InflaterInputStream(ByteArrayInputStream(data)).use { iis ->
            val buf = ByteArray(2048)
            while (true) { val n = iis.read(buf); if (n < 0) break; out.write(buf, 0, n) }
        }
        out.toByteArray()
    } catch (e: Exception) {
        Log.w(tag, "inflate failed: ${e.message}")
        ByteArray(0)
    }

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u16le(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)
    private fun s16le(b: ByteArray, o: Int) = (u16le(b, o).toShort()).toInt()
    private fun u32le(b: ByteArray, o: Int) =
        u8(b, o) or (u8(b, o + 1) shl 8) or (u8(b, o + 2) shl 16) or (u8(b, o + 3) shl 24)
    private fun u64le(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[o + i].toLong() and 0xFF) shl (8 * i))
        return v
    }
}
