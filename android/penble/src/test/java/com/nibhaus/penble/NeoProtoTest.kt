package com.nibhaus.penble

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Command catalog + request builders, validated byte-exact against packets captured from the pen. */
class NeoProtoTest {

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    // Each expected string is a frame the real SDK was logged building.
    @Test fun `penInfo reproduces the captured handshake byte-exact`() {
        // From logcat: buildReqPenInfo appVer=0.1.0 -> C0 01 2A 00 [18×00] 01 12 "0.1.0" pad… "2.18" pad… C1
        assertThat(hex(NeoRequest.penInfo(appVersion = "0.1.0", protocol = "2.18"))).isEqualTo(
            "C0 01 2A 00 " +
                "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " + // 18 reserved
                "01 12 " +                                                 // appType 0x1201 (LE)
                "30 2E 31 2E 30 00 00 00 00 00 00 00 00 00 00 00 " +       // "0.1.0" padded to 16
                "32 2E 31 38 00 00 " +                                     // "2.18" padded to 6
                "C1",
        )
    }

    @Test fun `penInfo declares the pen-specific protocol version`() {
        // Neo Studio sends a different pen-info protocol per generation (captured from its handshakes):
        // V5/LAMY = 2.22, V2/M1+ = 2.18. penInfo carries whichever the driver picks; the field sits at
        // the end of the payload (protocol ASCII, zero-padded to 6). M1+ default path must stay 2.18.
        assertThat(hex(NeoRequest.penInfo(protocol = "2.22"))).endsWith("32 2E 32 32 00 00 C1") // "2.22"
        assertThat(hex(NeoRequest.penInfo())).endsWith("32 2E 31 38 00 00 C1")                  // "2.18"
    }

    @Test fun `password encodes ASCII into a fixed 16-byte field`() {
        // cmd 0x02, len 16; "1234" -> ASCII 31 32 33 34, zero-padded to 16.
        assertThat(hex(NeoRequest.password("1234")))
            .isEqualTo("C0 02 10 00 31 32 33 34 00 00 00 00 00 00 00 00 00 00 00 00 C1")
    }

    @Test fun `setPassword matches the captured change frame`() {
        // Layout confirmed byte-exact vs Neo Studio 2 HCI snoops: cmd 0x03, len 0x21=33; [0]=01 flag,
        // [1..16]=old, [17..32]=new (each ASCII, padded to 16). Example values (not a real credential).
        assertThat(hex(NeoRequest.setPassword("1234", "5678")))
            .isEqualTo(
                "C0 03 21 00 01 " +
                    "31 32 33 34 00 00 00 00 00 00 00 00 00 00 00 00 " + // old "1234"
                    "35 36 37 38 00 00 00 00 00 00 00 00 00 00 00 00 " + // new "5678"
                    "C1",
            )
    }

    @Test fun `penStatus matches the captured frame`() {
        assertThat(hex(NeoRequest.penStatus())).isEqualTo("C0 04 00 00 C1")
    }

    @Test fun `usingAllNotes matches the captured frame`() {
        assertThat(hex(NeoRequest.usingAllNotes())).isEqualTo("C0 11 02 00 FF FF C1")
    }

    @Test fun `offlineNoteList matches the captured frame`() {
        assertThat(hex(NeoRequest.offlineNoteList())).isEqualTo("C0 21 04 00 FF FF FF FF C1")
    }

    @Test fun `offlineDataSave on matches the captured frame`() {
        assertThat(hex(NeoRequest.offlineDataSave(true))).isEqualTo("C0 05 02 00 07 01 C1")
        assertThat(hex(NeoRequest.offlineDataSave(false))).isEqualTo("C0 05 02 00 07 00 C1")
    }

    // offlineData's byte-exact format is covered by NeoOfflineTest (the verified 14-byte layout).

    @Test fun `response opcode is request or 0x80`() {
        assertThat(NeoCmd.responseOf(NeoCmd.REQ_PEN_STATUS)).isEqualTo(NeoCmd.RES_PEN_STATUS)
        assertThat(NeoCmd.responseOf(NeoCmd.REQ_OFFLINE_NOTE_LIST)).isEqualTo(NeoCmd.RES_OFFLINE_NOTE_LIST)
        assertThat(NeoCmd.responseOf(NeoCmd.REQ_PEN_INFO)).isEqualTo(NeoCmd.RES_PEN_INFO)
    }

    @Test fun `parseInbound reads cmd error and length-bounded payload`() {
        // a synthetic RX body: cmd=0x84(RES_PenStatus), error=0, len=2, payload=AA BB, + trailing junk
        val body = byteArrayOf(0x84.toByte(), 0x00, 0x02, 0x00, 0xAA.toByte(), 0xBB.toByte(), 0x77)
        val msg = parseInbound(body)!!
        assertThat(msg.cmd).isEqualTo(NeoCmd.RES_PEN_STATUS)
        assertThat(msg.error).isEqualTo(0)
        assertThat(hex(msg.payload)).isEqualTo("AA BB") // trailing 0x77 excluded by length
    }

    @Test fun `parseInbound rejects a too-short body`() {
        assertThat(parseInbound(byteArrayOf(0x84.toByte(), 0x00))).isNull()
    }

    @Test fun `unlock reply decodes correct vs wrong from payload byte 0`() {
        // Captured byte-exact: a correct password replies 01 00 0a, a wrong one 00 06 0a.
        assertThat(passwordUnlockCorrect(byteArrayOf(0x01, 0x00, 0x0A))).isTrue()
        assertThat(passwordUnlockCorrect(byteArrayOf(0x00, 0x06, 0x0A))).isFalse()
        assertThat(passwordUnlockCorrect(ByteArray(0))).isFalse()
    }

    @Test fun `change reply uses the opposite success marker from unlock`() {
        // Captured: a successful old->new change replies 00 0a 00 (payload[0]==0x00 = success) — the
        // OPPOSITE of the unlock reply, where payload[0]==0x01 = correct. Regression guard: reusing
        // the unlock's 0x01 marker reported every successful change as a failure.
        assertThat(passwordChangeSucceeded(byteArrayOf(0x00, 0x0A, 0x00))).isTrue()
        assertThat(passwordChangeSucceeded(byteArrayOf(0x00, 0x0A))).isTrue() // older fw: 2-byte 00 0a
        assertThat(passwordChangeSucceeded(byteArrayOf(0x01, 0x00, 0x0A))).isFalse()
        assertThat(passwordChangeSucceeded(ByteArray(0))).isFalse()
        // The two markers must disagree on a change-success payload, or one reply type is misread.
        val changeOk = byteArrayOf(0x00, 0x0A, 0x00)
        assertThat(passwordChangeSucceeded(changeOk)).isNotEqualTo(passwordUnlockCorrect(changeOk))
    }

    @Test fun `a built request round-trips through the decoder`() {
        val frames = NeoFrameDecoder().feed(NeoRequest.usingAllNotes())
        assertThat(frames).hasSize(1)
        assertThat(hex(frames[0])).isEqualTo("11 02 00 FF FF") // [cmd, lenLo, lenHi, payload]
    }
}
