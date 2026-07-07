package com.nibhaus.penble

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Offline request builders, note-list parse, and blob decode — byte layouts from the RE'd SDK. */
class NeoOfflineTest {

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())
    private fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(), ((v ushr 24) and 0xFF).toByte(),
    )
    private fun le64(v: Long) = ByteArray(8) { ((v ushr (8 * it)) and 0xFF).toByte() }

    // --- request builders ---

    @Test fun `offlineData builds the 14-byte download request`() {
        // section=3, owner=27 (1B), note=438 (01B6) → keep (deleteFlag 2), page-count 0
        assertThat(hex(NeoRequest.offlineData(section = 3, owner = 27, note = 438, delete = false)))
            .isEqualTo("C0 23 0E 00 02 01 1B 00 00 03 B6 01 00 00 00 00 00 00 C1")
    }

    @Test fun `offlineData delete flag is 1`() {
        assertThat(hex(NeoRequest.offlineData(3, 27, 438, delete = true)))
            .isEqualTo("C0 23 0E 00 01 01 1B 00 00 03 B6 01 00 00 00 00 00 00 C1")
    }

    @Test fun `chunk ACK status is 0 on the last chunk, 1 otherwise`() {
        assertThat(hex(NeoRequest.offlineChunkAck(chunkPacketId = 5, lastChunk = true)))
            .isEqualTo("C0 A4 00 03 00 05 00 00 C1")
        assertThat(hex(NeoRequest.offlineChunkAck(chunkPacketId = 5, lastChunk = false)))
            .isEqualTo("C0 A4 00 03 00 05 00 01 C1")
    }

    // --- note-list parse ---

    @Test fun `parseOfflineNoteList reads count then 8-byte records`() {
        val payload = le16(1) + byteArrayOf(0x1B, 0, 0, 0x03) + le32(438) // owner=27, section=3, note=438
        assertThat(parseOfflineNoteList(payload)).containsExactly(NeoOfflineNote(3, 27, 438))
    }

    // --- blob decode ---

    private fun dot(timeDelta: Int, force: Int, xInt: Int, yInt: Int, xFrac: Int, yFrac: Int): ByteArray {
        val d = ByteArray(16)
        d[0] = timeDelta.toByte()
        le16(force).copyInto(d, 1)
        le16(xInt).copyInto(d, 3)
        le16(yInt).copyInto(d, 5)
        d[7] = xFrac.toByte()
        d[8] = yFrac.toByte()
        return d
    }

    /** Build a chunk blob with a RAW (uncompressed) body: sizeAfter == sizeBefore ⇒ no inflate. */
    private fun rawBlob(owner: Int, section: Int, note: Int, strokeCount: Int, body: ByteArray): ByteArray {
        val blob = ByteArray(17 + body.size)
        blob[0] = 0; blob[1] = 1                  // [0..1] marker
        le16(body.size).copyInto(blob, 2)         // [2..3] sizeBeforeCompress
        le16(body.size).copyInto(blob, 4)         // [4..5] sizeAfterCompress (== before ⇒ raw)
        blob[6] = 2                               // [6] position = last
        blob[7] = (owner and 0xFF).toByte(); blob[8] = ((owner ushr 8) and 0xFF).toByte()
        blob[9] = ((owner ushr 16) and 0xFF).toByte(); blob[10] = section.toByte()
        le32(note).copyInto(blob, 11)             // [11..14] note
        le16(strokeCount).copyInto(blob, 15)      // [15..16] strokeCount
        body.copyInto(blob, 17)
        return blob
    }

    @Test fun `decodes an uncompressed blob into a stroke with correct coords, pressure, time`() {
        val strokeHdr = ByteArray(27).also {
            le32(9).copyInto(it, 0)      // page
            le64(1000).copyInto(it, 4)   // startTime
            le64(1050).copyInto(it, 12)  // endTime
            le32(0xFF112233.toInt()).copyInto(it, 21) // color
            le16(2).copyInto(it, 25)     // dotCount
        }
        val body = strokeHdr +
            dot(timeDelta = 10, force = 426, xInt = 100, yInt = 200, xFrac = 50, yFrac = 25) +
            dot(timeDelta = 5, force = 400, xInt = 150, yInt = 200, xFrac = 0, yFrac = 0)
        val blob = rawBlob(owner = 27, section = 3, note = 438, strokeCount = 1, body = body)

        val strokes = NeoOfflineDecoder.parse(blob, maxPress = 852)
        assertThat(strokes).hasSize(1)
        val s = strokes[0]
        assertThat(s.section).isEqualTo(3)
        assertThat(s.owner).isEqualTo(27)
        assertThat(s.note).isEqualTo(438)
        assertThat(s.page).isEqualTo(9)
        assertThat(s.color).isEqualTo(0xFF112233.toInt())
        assertThat(s.points).hasSize(2)
        assertThat(s.points[0].x).isWithin(1e-4f).of(100.5f)   // 100 + 50/100
        assertThat(s.points[0].y).isWithin(1e-4f).of(200.25f)
        assertThat(s.points[0].pressure).isWithin(1e-3f).of(426f / 852f)
        assertThat(s.points[0].t).isEqualTo(1010L)             // startTime + delta
        assertThat(s.points[1].x).isWithin(1e-4f).of(150f)
        assertThat(s.points[1].t).isEqualTo(1050L)             // last dot → endTime
    }

    @Test fun `discards noise dots above the force ceiling`() {
        val strokeHdr = ByteArray(27).also { le16(1).copyInto(it, 25) } // 1 dot
        val body = strokeHdr + dot(timeDelta = 0, force = 900, xInt = 1, yInt = 1, xFrac = 0, yFrac = 0)
        val blob = rawBlob(owner = 1, section = 1, note = 1, strokeCount = 1, body = body)
        // force 900 > 852 → dropped → no points → stroke omitted
        assertThat(NeoOfflineDecoder.parse(blob, maxPress = 852)).isEmpty()
    }
}
