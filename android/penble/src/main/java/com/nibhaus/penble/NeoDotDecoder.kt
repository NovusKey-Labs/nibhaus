package com.nibhaus.penble

import com.nibhaus.pen.PenDot

/**
 * Clean-room decoder for the pen's live ink-event stream — turns NeoLAB protocol-2.x dot events into
 * [PenDot]s. The byte layouts below were read off captured on-device event frames (no GPL source copied).
 *
 * A stroke is split across three event types (all carry a 3-byte `[cmd,lenLo,lenHi]` header, NO error
 * byte; the payload handed to [decode] is the bytes after that header):
 *  - **PenUpDown (0x63)** — pen state. payload[0]: `0`=pen down (stroke start), `1`=pen up (stroke end).
 *    Carries no usable coordinate, so pen-up re-emits the last dot position as the closing `UP`.
 *  - **DotId (0x64)** — the current Ncode page `(owner, section, book, page)`, which then persists.
 *  - **DotData (0x65)** — the coordinates; the first dot after a pen-down is `DOWN`, the rest `MOVE`.
 *
 * The SDK's paper-noise filter is intentionally NOT reimplemented for v1: it does no smoothing (only
 * spike rejection + DOWN/UP resynthesis), and StrokeIngestor already buffers per-page and tolerates
 * imperfect framing, so raw dots render usable ink.
 */
class NeoDotDecoder {
    private var section = -1
    private var owner = -1
    private var book = -1
    private var page = -1
    private var prevDotTimeMs = 0L
    private var penIsDown = false
    private var lastX = 0f
    private var lastY = 0f
    private var haveDot = false
    private var maxPress = MAX_PRESS_DEFAULT

    /** Reset stream state for a fresh connection. */
    fun reset() {
        section = -1; owner = -1; book = -1; page = -1
        prevDotTimeMs = 0L; penIsDown = false; lastX = 0f; lastY = 0f; haveDot = false
        maxPress = MAX_PRESS_DEFAULT
    }

    /** The pen's reported max pressure (from RES_PenStatus), used to normalize force → 0..1. */
    fun setMaxPress(value: Int) { if (value > 0) maxPress = value }

    /** Decode one event: [cmd] + its [payload] (the bytes after the `[cmd,lenLo,lenHi]` header).
     *  Returns a [PenDot], or null for events that carry no dot (id-change, pen-down). */
    fun decode(cmd: Int, payload: ByteArray): PenDot? = when (cmd) {
        EVT_PEN_UPDOWN -> onPenUpDown(payload)
        EVT_DOT_ID -> setPage(payload)
        EVT_DOT_DATA -> {
            val phase = if (penIsDown) { penIsDown = false; PenDot.Phase.DOWN } else PenDot.Phase.MOVE
            buildDot(payload, phase)
        }
        else -> null
    }

    /** PenUpDown payload[0]: 0 = pen down (arm the next dot as the stroke start), 1 = pen up
     *  (close the stroke at the last coordinate — this event carries no usable coordinate of its own).
     *
     *  A genuinely instantaneous tap — pen down then up again before a single DOT_DATA frame arrives —
     *  otherwise vanishes entirely: no DOWN (nothing armed the buffer), no UP (`haveDot` never got set).
     *  That silent drop is the likely root cause of "quick tap sometimes doesn't navigate": StrokeIngestor
     *  never sees the tap at all, so zone-matching never runs. [penIsDown] still being true at the pen-up
     *  means exactly that happened, so synthesize a single dot at the pen's last known position — the
     *  same fallback already used to close an ordinary stroke's UP below — so a physical tap always
     *  produces at least one [PenDot] downstream. */
    private fun onPenUpDown(p: ByteArray): PenDot? {
        if (p.isEmpty()) return null
        return if (p[0].toInt() == 0) {
            penIsDown = true
            null
        } else if (haveDot) {
            haveDot = false
            penIsDown = false
            PenDot(section, owner, book, page, lastX, lastY, 0f, PenDot.Phase.UP, prevDotTimeMs, INK_COLOR)
        } else if (penIsDown) {
            penIsDown = false
            PenDot(section, owner, book, page, lastX, lastY, 0f, PenDot.Phase.UP, prevDotTimeMs, INK_COLOR)
        } else null
    }

    /** DotId payload: [0..2] owner (LE) · [3] section · [4..7] book (LE) · [8..11] page (LE).
     *
     *  If the page changes while a stroke is still open (`haveDot` — a pen-up hasn't closed it yet),
     *  emit that stroke's closing UP **on the OLD page** before switching. Otherwise the pending
     *  pen-up gets re-tagged to the NEW page and re-emits the old coordinates there — a stray line
     *  near the top of every page-2/3, and the old page silently loses its last stroke's close. */
    private fun setPage(p: ByteArray): PenDot? {
        if (p.size < 12) return null
        val nOwner = u8(p, 0) or (u8(p, 1) shl 8) or (u8(p, 2) shl 16)
        val nSection = u8(p, 3)
        val nBook = u32le(p, 4).toInt()
        val nPage = u32le(p, 8).toInt()
        val closing = if (haveDot && (nBook != book || nPage != page || nSection != section || nOwner != owner)) {
            haveDot = false
            PenDot(section, owner, book, page, lastX, lastY, 0f, PenDot.Phase.UP, prevDotTimeMs, INK_COLOR)
        } else null
        owner = nOwner; section = nSection; book = nBook; page = nPage
        return closing
    }

    /** DotData payload: [0] time-delta · [1..2] force (LE) · [3..4] xInt · [5..6] yInt · [7] xFrac · [8] yFrac. */
    private fun buildDot(p: ByteArray, phase: PenDot.Phase): PenDot? {
        if (p.size < 9) return null
        prevDotTimeMs += u8(p, 0).toLong()
        val force = u16le(p, 1)
        val x = (u16le(p, 3) + u8(p, 7) * 0.01).toFloat() // integer + fraction/100, Ncode units
        val y = (u16le(p, 5) + u8(p, 8) * 0.01).toFloat()
        lastX = x; lastY = y; haveDot = true
        val pressure = (force.toFloat() / maxPress).coerceIn(0f, 1f)
        return PenDot(section, owner, book, page, x, y, pressure, phase, prevDotTimeMs, INK_COLOR)
    }

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u16le(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)
    private fun u32le(b: ByteArray, o: Int) = u8(b, o).toLong() or (u8(b, o + 1).toLong() shl 8) or
        (u8(b, o + 2).toLong() shl 16) or (u8(b, o + 3).toLong() shl 24)

    companion object {
        const val MAX_PRESS_DEFAULT = 852
        private const val INK_COLOR = 0xFF000000.toInt() // black; the wire carries no per-dot colour here

        // Event opcodes (these frames carry NO error byte in their header).
        const val EVT_PEN_UPDOWN = 0x63 // payload[0]: 0=down, 1=up
        const val EVT_DOT_ID = 0x64     // owner/section/book/page
        const val EVT_DOT_DATA = 0x65   // coordinates + force

        /** Event frames (0x63..0x6F) use a 3-byte `[cmd,lenLo,lenHi]` header with no error byte,
         *  unlike command responses. */
        fun isEvent(cmd: Int) = cmd in 0x63..0x6F
    }
}
