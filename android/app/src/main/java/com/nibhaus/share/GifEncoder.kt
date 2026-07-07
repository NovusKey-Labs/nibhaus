package com.nibhaus.share

import java.io.ByteArrayOutputStream

/**
 * Self-contained GIF89a animated encoder — no dependency (hand-rolled per the GIF89a spec). Frames
 * are opaque ARGB pixel arrays (row-major, e.g. from `Bitmap.getPixels`), all sharing one [width]x
 * [height]. Ink pages are white background + a handful of ink colors, so a single Global Color Table
 * built from every frame's pixels (nearest-color mapped when there are more than 256 distinct colors)
 * covers them cheaply. Each frame is LZW-compressed per spec: a Clear code at the start, variable-
 * width codes that grow as the dictionary fills (the GIF "early change" convention — code width grows
 * the moment the next code needs it, not after), an EOI code, and 255-byte sub-block chunking. A
 * NETSCAPE2.0 application extension makes the animation loop forever.
 */
object GifEncoder {

    private const val MAX_PALETTE = 256

    /** Encodes [frames] (each `width*height` ARGB ints) into a GIF89a byte stream, [delayCs] between
     *  frames (1/100s units, GIF's native delay resolution). */
    fun encode(width: Int, height: Int, frames: List<IntArray>, delayCs: Int): ByteArray {
        require(width > 0 && height > 0) { "invalid dimensions: ${width}x$height" }
        require(frames.isNotEmpty()) { "need at least one frame" }
        val expected = width * height
        frames.forEach { require(it.size == expected) { "frame size ${it.size} != $expected" } }

        val palette = buildPalette(frames)
        val colorSize = bitsFor(palette.size)
        val gctSize = 1 shl colorSize

        val out = ByteArrayOutputStream()
        writeAscii(out, "GIF89a")
        writeLogicalScreenDescriptor(out, width, height, colorSize)
        writeColorTable(out, palette, gctSize)
        writeNetscapeLoop(out)

        val indexOf = HashMap<Int, Int>(palette.size * 2).apply {
            palette.forEachIndexed { i, c -> put(c, i) }
        }
        val nearestCache = HashMap<Int, Int>()
        val clampedDelay = delayCs.coerceIn(1, 0xFFFF)
        for (frame in frames) {
            writeGraphicControlExtension(out, clampedDelay)
            writeImageDescriptor(out, width, height)
            val indices = IntArray(frame.size) { paletteIndexOf(frame[it], palette, indexOf, nearestCache) }
            writeImageData(out, indices, colorSize)
        }
        out.write(0x3B) // trailer
        return out.toByteArray()
    }

    // ── palette ──────────────────────────────────────────────────────────────────────────────

    /** Distinct opaque RGB values across every frame, sorted ascending; when there are more than
     *  [MAX_PALETTE] distinct colors, keeps the [MAX_PALETTE] most frequent (ties broken by value) so
     *  the common ink/paper colors are exact and rarer anti-aliased edge colors get nearest-mapped. */
    private fun buildPalette(frames: List<IntArray>): IntArray {
        val freq = HashMap<Int, Int>()
        for (frame in frames) for (px in frame) {
            val rgb = px and 0xFFFFFF
            freq[rgb] = (freq[rgb] ?: 0) + 1
        }
        val chosen = if (freq.size <= MAX_PALETTE) {
            freq.keys
        } else {
            freq.entries
                .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
                .take(MAX_PALETTE)
                .map { it.key }
        }
        return chosen.sorted().toIntArray()
    }

    /** Bits needed to index [paletteSize] colors; GIF requires a minimum LZW code size of 2. */
    private fun bitsFor(paletteSize: Int): Int {
        var b = 2
        while ((1 shl b) < paletteSize) b++
        return b.coerceIn(2, 8)
    }

    private fun paletteIndexOf(
        argb: Int,
        palette: IntArray,
        indexOf: Map<Int, Int>,
        nearestCache: MutableMap<Int, Int>,
    ): Int {
        val rgb = argb and 0xFFFFFF
        indexOf[rgb]?.let { return it }
        return nearestCache.getOrPut(rgb) {
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            var best = 0
            var bestDist = Int.MAX_VALUE
            for (i in palette.indices) {
                val pc = palette[i]
                val dr = r - ((pc shr 16) and 0xFF)
                val dg = g - ((pc shr 8) and 0xFF)
                val db = b - (pc and 0xFF)
                val dist = dr * dr + dg * dg + db * db
                if (dist < bestDist) { bestDist = dist; best = i }
            }
            best
        }
    }

    // ── LZW ──────────────────────────────────────────────────────────────────────────────────

    /** LSB-first bit packer, as GIF's LZW data requires (low-order bits of each code written first). */
    private class BitBuffer {
        private val bytes = ByteArrayOutputStream()
        private var buf = 0
        private var bitCount = 0

        fun writeCode(code: Int, size: Int) {
            buf = buf or (code shl bitCount)
            bitCount += size
            while (bitCount >= 8) {
                bytes.write(buf and 0xFF)
                buf = buf ushr 8
                bitCount -= 8
            }
        }

        fun finish(): ByteArray {
            if (bitCount > 0) { bytes.write(buf and 0xFF); buf = 0; bitCount = 0 }
            return bytes.toByteArray()
        }
    }

    /** LZW-compresses [indices] (palette indices, 0 until 2^minCodeSize) per the GIF spec: Clear code
     *  first, codes grow from `minCodeSize+1` bits up to 12 as the dictionary fills (early-change:
     *  bumped the instant the next code needs the extra bit), a fresh Clear+dictionary reset if the
     *  4096-entry table fills, and a final EOI code. */
    private fun lzwEncode(indices: IntArray, minCodeSize: Int): ByteArray {
        val clearCode = 1 shl minCodeSize
        val eoiCode = clearCode + 1
        var codeSize = minCodeSize + 1
        var nextCode = clearCode + 2
        var dict = HashMap<Long, Int>()
        val writer = BitBuffer()
        writer.writeCode(clearCode, codeSize)

        if (indices.isEmpty()) {
            writer.writeCode(eoiCode, codeSize)
            return writer.finish()
        }

        var prefix = indices[0]
        for (i in 1 until indices.size) {
            val k = indices[i]
            val key = (prefix.toLong() shl 32) or (k.toLong() and 0xFFFFFFFFL)
            val existing = dict[key]
            if (existing != null) {
                prefix = existing
                continue
            }
            writer.writeCode(prefix, codeSize)
            if (nextCode < 4096) {
                dict[key] = nextCode
                // Bump the code width once the code value just registered needs it — checked
                // BEFORE incrementing nextCode. A decoder can only reconstruct a new dictionary
                // entry's second symbol once it reads the code AFTER this one (that's where the
                // symbol comes from), so its dictionary is always exactly one entry behind ours;
                // bumping here (not one insertion earlier, which is the tempting-but-wrong
                // `nextCode == 2^codeSize` check on the POST-increment value) is what keeps our
                // bit width and the decoder's reading width in sync at every code boundary.
                if (nextCode == (1 shl codeSize) && codeSize < 12) codeSize++
                nextCode++
            } else {
                writer.writeCode(clearCode, codeSize)
                dict = HashMap()
                codeSize = minCodeSize + 1
                nextCode = clearCode + 2
            }
            prefix = k
        }
        writer.writeCode(prefix, codeSize)
        writer.writeCode(eoiCode, codeSize)
        return writer.finish()
    }

    // ── GIF structure ────────────────────────────────────────────────────────────────────────

    private fun writeAscii(out: ByteArrayOutputStream, s: String) { for (c in s) out.write(c.code) }

    private fun writeLE16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    private fun writeLogicalScreenDescriptor(out: ByteArrayOutputStream, width: Int, height: Int, colorSize: Int) {
        writeLE16(out, width)
        writeLE16(out, height)
        // bit7 global color table=1, bits6-4 color resolution, bit3 sort=0, bits2-0 GCT size (both colorSize-1)
        out.write(0x80 or ((colorSize - 1) shl 4) or (colorSize - 1))
        out.write(0x00) // background color index
        out.write(0x00) // pixel aspect ratio
    }

    private fun writeColorTable(out: ByteArrayOutputStream, palette: IntArray, gctSize: Int) {
        for (i in 0 until gctSize) {
            val c = if (i < palette.size) palette[i] else 0
            out.write((c shr 16) and 0xFF)
            out.write((c shr 8) and 0xFF)
            out.write(c and 0xFF)
        }
    }

    /** Application extension that makes animated GIF viewers (incl. PIL) loop the clip forever. */
    private fun writeNetscapeLoop(out: ByteArrayOutputStream) {
        out.write(0x21) // extension introducer
        out.write(0xFF) // application extension label
        out.write(0x0B) // block size
        writeAscii(out, "NETSCAPE2.0")
        out.write(0x03) // sub-block size
        out.write(0x01) // sub-block id
        writeLE16(out, 0) // loop count, 0 = infinite
        out.write(0x00) // block terminator
    }

    private fun writeGraphicControlExtension(out: ByteArrayOutputStream, delayCs: Int) {
        out.write(0x21) // extension introducer
        out.write(0xF9) // graphic control label
        out.write(0x04) // block size
        out.write(0x04) // disposal method = 1 (do not dispose), no transparency
        writeLE16(out, delayCs)
        out.write(0x00) // transparent color index (unused)
        out.write(0x00) // block terminator
    }

    private fun writeImageDescriptor(out: ByteArrayOutputStream, width: Int, height: Int) {
        out.write(0x2C) // image separator
        writeLE16(out, 0) // left
        writeLE16(out, 0) // top
        writeLE16(out, width)
        writeLE16(out, height)
        out.write(0x00) // no local color table, no interlace
    }

    /** LZW-compresses [indices] and chunks the result into <=255-byte sub-blocks, as GIF image data
     *  requires: a leading minimum-code-size byte, then length-prefixed sub-blocks, then a 0x00
     *  terminator block. */
    private fun writeImageData(out: ByteArrayOutputStream, indices: IntArray, colorSize: Int) {
        out.write(colorSize) // LZW minimum code size
        val compressed = lzwEncode(indices, colorSize)
        var offset = 0
        while (offset < compressed.size) {
            val len = minOf(255, compressed.size - offset)
            out.write(len)
            out.write(compressed, offset, len)
            offset += len
        }
        out.write(0x00) // block terminator
    }
}
