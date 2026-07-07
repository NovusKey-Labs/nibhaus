package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.share.GifEncoder
import org.junit.Test

/**
 * [GifEncoder] structural + round-trip tests. No Android/Bitmap involved — frames are raw ARGB
 * IntArrays, so this runs plain-JVM. The round-trip tests decode the GIF's own container format back
 * (LZW + block structure) via a small hand-written reader below, rather than trusting a third-party
 * library, to pin down exactly the parts of the GIF89a spec [GifEncoder] must get right for real
 * readers (PIL, browsers) to accept it: header, NETSCAPE2.0 loop extension, per-frame Image
 * Descriptors, and LZW pixels that decode back to the original colors.
 */
class GifEncoderTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test fun encode_startsWithGif89aHeader() {
        val bytes = GifEncoder.encode(2, 2, listOf(intArrayOf(white, white, white, white)), delayCs = 10)
        assertThat(String(bytes, 0, 6, Charsets.US_ASCII)).isEqualTo("GIF89a")
    }

    @Test fun encode_endsWithTrailer() {
        val bytes = GifEncoder.encode(2, 2, listOf(intArrayOf(white, white, white, white)), delayCs = 10)
        assertThat(bytes.last()).isEqualTo(0x3B.toByte())
    }

    @Test fun encode_containsNetscapeLoopExtensionForInfiniteLooping() {
        val bytes = GifEncoder.encode(2, 2, listOf(intArrayOf(white, white, white, white)), delayCs = 10)
        assertThat(String(bytes, Charsets.ISO_8859_1)).contains("NETSCAPE2.0")
    }

    @Test fun encode_twoFrames_hasExactlyTwoImageDescriptorsAndRoundTripsPixels() {
        val frame0 = intArrayOf(white, white, white, white)
        val frame1 = intArrayOf(black, white, white, white)
        val bytes = GifEncoder.encode(2, 2, listOf(frame0, frame1), delayCs = 7)

        val gif = parseGif(bytes)
        assertThat(gif.imageFrameCount).isEqualTo(2)
        assertThat(gif.decodedFrameRgb[0]).isEqualTo(frame0.map { it and 0xFFFFFF })
        assertThat(gif.decodedFrameRgb[1]).isEqualTo(frame1.map { it and 0xFFFFFF })
    }

    @Test fun encode_manyDistinctColorsRoundTrips() {
        // 4x4, all-distinct pixels — exercises palette building beyond 2 colors and LZW dictionary
        // growth (repeated literal-code emission before any multi-symbol codes appear).
        val w = 4; val h = 4
        val frame = IntArray(w * h) { i -> (0xFF shl 24) or ((i * 16) shl 16) or ((i * 8) shl 8) or i }
        val bytes = GifEncoder.encode(w, h, listOf(frame), delayCs = 10)

        val gif = parseGif(bytes)
        assertThat(gif.imageFrameCount).isEqualTo(1)
        assertThat(gif.decodedFrameRgb[0]).isEqualTo(frame.map { it and 0xFFFFFF })
    }

    @Test fun encode_throwsOnEmptyFrameList() {
        try {
            GifEncoder.encode(2, 2, emptyList(), delayCs = 10)
            error("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test fun encode_throwsOnFrameSizeMismatch() {
        try {
            GifEncoder.encode(2, 2, listOf(intArrayOf(white, white, white)), delayCs = 10)
            error("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    // ── minimal structural GIF89a reader, just enough to validate GifEncoder's own output ──────

    private class ParsedGif(val imageFrameCount: Int, val decodedFrameRgb: List<List<Int>>)

    private fun parseGif(bytes: ByteArray): ParsedGif {
        var pos = 6 // past "GIF89a"
        fun u8(): Int = bytes[pos++].toInt() and 0xFF
        fun u16(): Int { val lo = u8(); val hi = u8(); return lo or (hi shl 8) }

        u16(); u16() // logical screen width/height (unused by this reader)
        val packed = u8()
        u8(); u8() // background color index, pixel aspect ratio
        val gctFlag = (packed and 0x80) != 0
        val gctSize = 1 shl ((packed and 0x07) + 1)
        val globalPalette = if (gctFlag) {
            IntArray(gctSize) { val r = u8(); val g = u8(); val b = u8(); (r shl 16) or (g shl 8) or b }
        } else IntArray(0)

        fun skipSubBlocks() {
            while (true) {
                val len = u8()
                if (len == 0) break
                pos += len
            }
        }
        fun readSubBlocks(): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                val len = u8()
                if (len == 0) break
                out.write(bytes, pos, len)
                pos += len
            }
            return out.toByteArray()
        }

        var frameCount = 0
        val decoded = mutableListOf<List<Int>>()
        loop@ while (pos < bytes.size) {
            when (val marker = u8()) {
                0x21 -> { // extension: application (NETSCAPE2.0), graphic control, or other
                    u8() // label
                    val n = u8()
                    pos += n
                    skipSubBlocks()
                }
                0x2C -> { // image descriptor
                    frameCount++
                    u16(); u16() // left, top
                    val iw = u16(); val ih = u16()
                    val ipacked = u8()
                    val localFlag = (ipacked and 0x80) != 0
                    val palette = if (localFlag) {
                        val lctSize = 1 shl ((ipacked and 0x07) + 1)
                        IntArray(lctSize) { val r = u8(); val g = u8(); val b = u8(); (r shl 16) or (g shl 8) or b }
                    } else globalPalette
                    val minCodeSize = u8()
                    val data = readSubBlocks()
                    val indices = lzwDecode(data, minCodeSize, iw * ih)
                    decoded.add(indices.map { palette[it] })
                }
                0x3B -> break@loop // trailer
                else -> error("unexpected GIF marker 0x${marker.toString(16)} at byte $pos")
            }
        }
        return ParsedGif(frameCount, decoded)
    }

    /** Textbook GIF LZW decompression (a dictionary of index-lists) — the mirror of [GifEncoder]'s
     *  encoder, used only to verify the encoder's own output round-trips correctly. */
    private fun lzwDecode(data: ByteArray, minCodeSize: Int, expectedPixels: Int): IntArray {
        val clearCode = 1 shl minCodeSize
        val eoiCode = clearCode + 1
        var codeSize = minCodeSize + 1
        var dict = ArrayList<IntArray>()
        fun resetDict() {
            dict = ArrayList(4096)
            for (i in 0 until clearCode) dict.add(intArrayOf(i))
            dict.add(IntArray(0)) // clear code slot, never read as an entry
            dict.add(IntArray(0)) // eoi code slot, never read as an entry
        }
        resetDict()

        var bitBuf = 0; var bitCount = 0; var bytePos = 0
        fun readCode(): Int {
            while (bitCount < codeSize) {
                bitBuf = bitBuf or ((data[bytePos].toInt() and 0xFF) shl bitCount)
                bytePos++
                bitCount += 8
            }
            val code = bitBuf and ((1 shl codeSize) - 1)
            bitBuf = bitBuf ushr codeSize
            bitCount -= codeSize
            return code
        }

        val out = ArrayList<Int>(expectedPixels)
        var prev: IntArray? = null
        while (out.size < expectedPixels) {
            val code = readCode()
            if (code == clearCode) { resetDict(); codeSize = minCodeSize + 1; prev = null; continue }
            if (code == eoiCode) break
            val entry: IntArray = when {
                code < dict.size -> dict[code]
                code == dict.size && prev != null -> prev + prev[0]
                else -> error("bad LZW code $code at dict size ${dict.size}")
            }
            out.addAll(entry.toList())
            if (prev != null && dict.size < 4096) {
                dict.add(prev + entry[0])
                if (dict.size == (1 shl codeSize) && codeSize < 12) codeSize++
            }
            prev = entry
        }
        return out.toIntArray()
    }
}
