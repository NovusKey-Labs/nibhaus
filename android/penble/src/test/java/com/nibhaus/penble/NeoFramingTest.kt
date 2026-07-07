package com.nibhaus.penble

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Framing codec, validated byte-exact against packets captured from the real pen. */
class NeoFramingTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    @Test fun `encodes the captured reqOfflineDataListAll packet byte-exact`() {
        // From logcat: buildReqOfflineDataListAllPacket -> C0 21 04 00 FF FF FF FF C1
        val pkt = NeoFraming.encodeRequest(0x21, bytes(0xFF, 0xFF, 0xFF, 0xFF))
        assertThat(hex(pkt)).isEqualTo("C0 21 04 00 FF FF FF FF C1")
    }

    @Test fun `encodes the captured penOfflineDataSetup packet byte-exact`() {
        // From logcat: buildPenOfflineDataSetup.Packet -> C0 05 02 00 07 01 C1
        val pkt = NeoFraming.encodeRequest(0x05, bytes(0x07, 0x01))
        assertThat(hex(pkt)).isEqualTo("C0 05 02 00 07 01 C1")
    }

    @Test fun `empty payload still carries a zero length`() {
        assertThat(hex(NeoFraming.encodeRequest(0x05))).isEqualTo("C0 05 00 00 C1")
    }

    @Test fun `byte-stuffs START END and ESC inside the body`() {
        // cmd 0xC0 + payload [0xC1, 0x7D] must all be escaped (XOR 0x20): C0->E0, C1->E1, 7D->5D.
        val pkt = NeoFraming.encodeRequest(0xC0, bytes(0xC1, 0x7D))
        // body = [C0, 02, 00, C1, 7D] -> stuffed -> 7DE0 02 00 7DE1 7D5D, wrapped in C0..C1
        assertThat(hex(pkt)).isEqualTo("C0 7D E0 02 00 7D E1 7D 5D C1")
    }

    @Test fun `decoder round-trips a request to its un-stuffed body`() {
        val body = bytes(0x21, 0x04, 0x00, 0xFF, 0xFF, 0xFF, 0xFF)
        val frames = NeoFrameDecoder().feed(NeoFraming.encodeRequest(0x21, bytes(0xFF, 0xFF, 0xFF, 0xFF)))
        assertThat(frames).hasSize(1)
        assertThat(hex(frames[0])).isEqualTo(hex(body))
    }

    @Test fun `decoder un-escapes stuffed bytes back to the original body`() {
        val original = bytes(0xC0, 0x02, 0x00, 0xC1, 0x7D)
        val frames = NeoFrameDecoder().feed(NeoFraming.frame(original))
        assertThat(frames).hasSize(1)
        assertThat(hex(frames[0])).isEqualTo(hex(original))
    }

    @Test fun `decoder reassembles a frame split across BLE notifications`() {
        val pkt = NeoFraming.encodeRequest(0x21, bytes(0xFF, 0xFF, 0xFF, 0xFF))
        val dec = NeoFrameDecoder()
        val first = dec.feed(pkt.copyOfRange(0, 3))   // partial — no complete frame yet
        val second = dec.feed(pkt.copyOfRange(3, pkt.size))
        assertThat(first).isEmpty()
        assertThat(second).hasSize(1)
        assertThat(hex(second[0])).isEqualTo("21 04 00 FF FF FF FF")
    }

    @Test fun `decoder emits multiple frames packed into one chunk`() {
        val a = NeoFraming.encodeRequest(0x05, bytes(0x07, 0x01))
        val b = NeoFraming.encodeRequest(0x21, bytes(0xFF, 0xFF, 0xFF, 0xFF))
        val frames = NeoFrameDecoder().feed(a + b)
        assertThat(frames).hasSize(2)
        assertThat(hex(frames[0])).isEqualTo("05 02 00 07 01")
        assertThat(hex(frames[1])).isEqualTo("21 04 00 FF FF FF FF")
    }

    @Test fun `decoder ignores junk before the first START`() {
        val pkt = NeoFraming.encodeRequest(0x05, bytes(0x07, 0x01))
        val frames = NeoFrameDecoder().feed(bytes(0x00, 0x99, 0x42) + pkt)
        assertThat(frames).hasSize(1)
        assertThat(hex(frames[0])).isEqualTo("05 02 00 07 01")
    }

    @Test fun `decoder resyncs on a fresh START, dropping a truncated frame`() {
        val good = NeoFraming.encodeRequest(0x05, bytes(0x07, 0x01))
        // a START + a few body bytes with NO end, then a complete frame
        val frames = NeoFrameDecoder().feed(bytes(NeoFraming.START, 0x99, 0x88) + good)
        assertThat(frames).hasSize(1)
        assertThat(hex(frames[0])).isEqualTo("05 02 00 07 01")
    }
}
