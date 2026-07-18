package com.nibhaus.penble

import com.google.common.truth.Truth.assertThat
import com.nibhaus.pen.PenDot
import org.junit.Test

/** Dot decoder, validated against event frames captured from the pen (notebook 438, page 9). */
class NeoDotDecodeTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun `decodes a real stroke from pen-down, id, dots and pen-up`() {
        val d = NeoDotDecoder()

        // 0x63 pen-DOWN (payload[0]==0): arms the next dot as the stroke start, no dot emitted.
        assertThat(d.decode(NeoDotDecoder.EVT_PEN_UPDOWN,
            bytes(0x00, 0xD4, 0xC2, 0x27, 0x00, 0x9F, 0x01, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0x00))).isNull()

        // 0x64 DotId — owner=27, section=3, book=438, page=9 (captured frame).
        assertThat(d.decode(NeoDotDecoder.EVT_DOT_ID,
            bytes(0x1B, 0x00, 0x00, 0x03, 0xB6, 0x01, 0x00, 0x00, 0x09, 0x00, 0x00, 0x00))).isNull()

        // 0x65 first dot → DOWN. force=70, xInt=11, yInt=67, xFrac=39, yFrac=68 (captured frame).
        val down = d.decode(NeoDotDecoder.EVT_DOT_DATA,
            bytes(0x17, 0x46, 0x00, 0x0B, 0x00, 0x43, 0x00, 0x27, 0x44, 0x59, 0x78, 0x95, 0x00))!!
        assertThat(down.section).isEqualTo(3)
        assertThat(down.owner).isEqualTo(27)
        assertThat(down.book).isEqualTo(438)
        assertThat(down.page).isEqualTo(9)
        assertThat(down.x).isWithin(1e-4f).of(11.39f) // 11 + 39/100
        assertThat(down.y).isWithin(1e-4f).of(67.68f) // 67 + 68/100
        assertThat(down.pressure).isWithin(1e-3f).of(70f / 852f)
        assertThat(down.phase).isEqualTo(PenDot.Phase.DOWN)
        assertThat(down.timestamp).isEqualTo(0x17L)

        // Next dot → MOVE, page address persists, timestamp accumulates the delta.
        val move = d.decode(NeoDotDecoder.EVT_DOT_DATA,
            bytes(0x10, 0x30, 0x02, 0x22, 0x00, 0x43, 0x00, 0x07, 0x4E, 0x58, 0x77, 0x9D, 0x00))!!
        assertThat(move.phase).isEqualTo(PenDot.Phase.MOVE)
        assertThat(move.book).isEqualTo(438)
        assertThat(move.x).isWithin(1e-4f).of(34.07f)
        assertThat(move.timestamp).isEqualTo(0x17L + 0x10L)

        // 0x63 pen-UP (payload[0]==1): closes the stroke at the last coordinate.
        val up = d.decode(NeoDotDecoder.EVT_PEN_UPDOWN,
            bytes(0x01, 0x16, 0xC8, 0x27, 0x00, 0x9F, 0x01, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0x00))!!
        assertThat(up.phase).isEqualTo(PenDot.Phase.UP)
        assertThat(up.x).isWithin(1e-4f).of(34.07f) // last dot's position
        assertThat(up.book).isEqualTo(438)
    }

    @Test fun `page change mid-stroke closes the old page's stroke on the old page`() {
        val d = NeoDotDecoder()
        // pen-down + first dot on book 438 / page 9
        d.decode(NeoDotDecoder.EVT_PEN_UPDOWN, bytes(0x00))
        d.decode(NeoDotDecoder.EVT_DOT_ID,
            bytes(0x1B, 0x00, 0x00, 0x03, 0xB6, 0x01, 0x00, 0x00, 0x09, 0x00, 0x00, 0x00))
        val down = d.decode(NeoDotDecoder.EVT_DOT_DATA, bytes(0x17, 0x46, 0x00, 0x0B, 0x00, 0x43, 0x00, 0x27, 0x44))!!
        assertThat(down.page).isEqualTo(9)

        // page changes to page 10 while the stroke is still open (the pen-up hasn't arrived yet).
        // This must close the OLD page's stroke ON THE OLD PAGE at the old coords — not leak onto page 10.
        val closing = d.decode(NeoDotDecoder.EVT_DOT_ID,
            bytes(0x1B, 0x00, 0x00, 0x03, 0xB6, 0x01, 0x00, 0x00, 0x0A, 0x00, 0x00, 0x00))!!
        assertThat(closing.phase).isEqualTo(PenDot.Phase.UP)
        assertThat(closing.page).isEqualTo(9)            // OLD page, not 10
        assertThat(closing.x).isWithin(1e-4f).of(11.39f) // OLD coords
        assertThat(closing.y).isWithin(1e-4f).of(67.68f)

        // the real pen-up that follows is now a no-op — NO stale dot lands on page 10.
        assertThat(d.decode(NeoDotDecoder.EVT_PEN_UPDOWN, bytes(0x01))).isNull()
    }

    @Test fun `duplicate page id mid-stroke does not break the stroke`() {
        val d = NeoDotDecoder()
        d.decode(NeoDotDecoder.EVT_PEN_UPDOWN, bytes(0x00))
        d.decode(NeoDotDecoder.EVT_DOT_ID, bytes(0x1B, 0x00, 0x00, 0x03, 0xB6, 0x01, 0x00, 0x00, 0x09, 0x00, 0x00, 0x00))
        d.decode(NeoDotDecoder.EVT_DOT_DATA, bytes(0x17, 0x46, 0x00, 0x0B, 0x00, 0x43, 0x00, 0x27, 0x44))
        // same page id re-sent mid-stroke must NOT emit a spurious UP (no real page change).
        assertThat(d.decode(NeoDotDecoder.EVT_DOT_ID,
            bytes(0x1B, 0x00, 0x00, 0x03, 0xB6, 0x01, 0x00, 0x00, 0x09, 0x00, 0x00, 0x00))).isNull()
    }

    @Test fun `id-change and short payloads produce no dot`() {
        val d = NeoDotDecoder()
        assertThat(d.decode(NeoDotDecoder.EVT_DOT_ID, ByteArray(12))).isNull()
        assertThat(d.decode(NeoDotDecoder.EVT_DOT_DATA, ByteArray(4))).isNull() // too short
        assertThat(NeoDotDecoder.isEvent(0x65)).isTrue()
        assertThat(NeoDotDecoder.isEvent(0x81)).isFalse() // 0x81 is a response, not an event
    }

    @Test fun `a genuinely instantaneous tap with zero DOT_DATA samples still emits a dot`() {
        val d = NeoDotDecoder()
        // Establish a real page and a last-known coordinate via one ordinary stroke, then close it.
        d.decode(NeoDotDecoder.EVT_PEN_UPDOWN, bytes(0x00))
        d.decode(NeoDotDecoder.EVT_DOT_ID,
            bytes(0x1B, 0x00, 0x00, 0x03, 0xB6, 0x01, 0x00, 0x00, 0x09, 0x00, 0x00, 0x00))
        d.decode(NeoDotDecoder.EVT_DOT_DATA, bytes(0x17, 0x46, 0x00, 0x0B, 0x00, 0x43, 0x00, 0x27, 0x44))
        d.decode(NeoDotDecoder.EVT_PEN_UPDOWN, bytes(0x01)) // closes it; haveDot -> false

        // Now a fast/light tap: pen-down immediately followed by pen-up, with NO DOT_DATA frame ever
        // arriving between them (the pen never crossed its sampling threshold). The down itself still
        // carries no dot, but the up must not vanish silently — it's the only signal this tap happened.
        assertThat(d.decode(NeoDotDecoder.EVT_PEN_UPDOWN, bytes(0x00))).isNull()
        val tap = d.decode(NeoDotDecoder.EVT_PEN_UPDOWN, bytes(0x01))
        assertThat(tap).isNotNull()
        assertThat(tap!!.phase).isEqualTo(PenDot.Phase.UP)
        assertThat(tap.book).isEqualTo(438)
        assertThat(tap.page).isEqualTo(9)
    }

    @Test fun `an unarmed pen-up (no preceding pen-down at all) still emits nothing`() {
        val d = NeoDotDecoder()
        // A bare pen-up with no pen-down having been seen yet in this session is not a tap — there was
        // no contact to synthesize from. Must stay null, not misfire on decoder-startup noise.
        assertThat(d.decode(NeoDotDecoder.EVT_PEN_UPDOWN, bytes(0x01))).isNull()
    }

    @Test fun `pressure clamps to 1 when force exceeds maxPress`() {
        val d = NeoDotDecoder().apply { setMaxPress(852) }
        val dot = bytes(0x00, 0xFF, 0x03, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0) // force=0x03FF=1023 > 852
        assertThat(d.decode(NeoDotDecoder.EVT_DOT_DATA, dot)!!.pressure).isEqualTo(1f)
    }

    @Test fun `every truncated event payload is ignored without corrupting the next frame`() {
        val d = NeoDotDecoder()
        for (size in 0 until 12) {
            assertThat(d.decode(NeoDotDecoder.EVT_DOT_ID, ByteArray(size))).isNull()
        }
        for (size in 0 until 9) {
            assertThat(d.decode(NeoDotDecoder.EVT_DOT_DATA, ByteArray(size))).isNull()
        }
        assertThat(d.decode(NeoDotDecoder.EVT_PEN_UPDOWN, byteArrayOf())).isNull()

        d.decode(NeoDotDecoder.EVT_PEN_UPDOWN, bytes(0))
        val dot = d.decode(NeoDotDecoder.EVT_DOT_DATA, bytes(1, 1, 0, 2, 0, 3, 0, 0, 0))!!
        assertThat(dot.phase).isEqualTo(PenDot.Phase.DOWN)
        assertThat(dot.x).isEqualTo(2f)
    }

    @Test fun `coordinate fields decode their full unsigned wire range`() {
        val d = NeoDotDecoder()
        val dot = d.decode(
            NeoDotDecoder.EVT_DOT_DATA,
            bytes(0, 0, 0, 0xFF, 0xFF, 0xFF, 0xFF, 99, 99),
        )!!
        assertThat(dot.x).isWithin(0.01f).of(65_535.99f)
        assertThat(dot.y).isWithin(0.01f).of(65_535.99f)
    }

    @Test fun `a repeated down arms the next sample as a new DOWN phase`() {
        val d = NeoDotDecoder()
        d.decode(NeoDotDecoder.EVT_PEN_UPDOWN, bytes(0))
        assertThat(d.decode(NeoDotDecoder.EVT_DOT_DATA, bytes(0, 1, 0, 1, 0, 1, 0, 0, 0))!!.phase)
            .isEqualTo(PenDot.Phase.DOWN)
        d.decode(NeoDotDecoder.EVT_PEN_UPDOWN, bytes(0))
        assertThat(d.decode(NeoDotDecoder.EVT_DOT_DATA, bytes(0, 1, 0, 2, 0, 2, 0, 0, 0))!!.phase)
            .isEqualTo(PenDot.Phase.DOWN)
    }

    @Test fun `section owner and book switch closes old address before accepting new address`() {
        val d = NeoDotDecoder()
        d.decode(NeoDotDecoder.EVT_DOT_ID, bytes(1, 0, 0, 2, 3, 0, 0, 0, 4, 0, 0, 0))
        d.decode(NeoDotDecoder.EVT_PEN_UPDOWN, bytes(0))
        d.decode(NeoDotDecoder.EVT_DOT_DATA, bytes(0, 1, 0, 5, 0, 6, 0, 0, 0))

        val closing = d.decode(
            NeoDotDecoder.EVT_DOT_ID,
            bytes(9, 0, 0, 8, 7, 0, 0, 0, 6, 0, 0, 0),
        )!!
        assertThat(listOf(closing.owner, closing.section, closing.book, closing.page))
            .containsExactly(1, 2, 3, 4).inOrder()
        assertThat(closing.phase).isEqualTo(PenDot.Phase.UP)

        d.decode(NeoDotDecoder.EVT_PEN_UPDOWN, bytes(0))
        val next = d.decode(NeoDotDecoder.EVT_DOT_DATA, bytes(0, 1, 0, 1, 0, 1, 0, 0, 0))!!
        assertThat(listOf(next.owner, next.section, next.book, next.page))
            .containsExactly(9, 8, 7, 6).inOrder()
    }
}
