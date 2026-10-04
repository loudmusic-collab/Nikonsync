package com.loudmusic.dropfoto.dng

import java.io.ByteArrayOutputStream

/**
 * Lossless JPEG (ITU-T T.81 process 14, predictor 1) encoder for DNG raw tiles, the compression
 * Adobe DNG Converter uses (DNG Compression = 7).
 *
 * A CFA tile is encoded as two interleaved components over half the width, so each sample is
 * predicted from the same-colour sample two columns to its left. Each tile gets its own optimal
 * Huffman table.
 */
object LosslessJpeg {
    private const val PRECISION = 16

    /**
     * Encodes [height] rows of [width] samples (even width) starting at [offset] in [samples]
     * with row stride [stride].
     */
    fun encode(samples: IntArray, offset: Int, stride: Int, width: Int, height: Int): ByteArray {
        require(width % 2 == 0) { "Tile width must be even" }
        val half = width / 2
        // Pass 1: categories, to build the Huffman table.
        val freq = LongArray(17)
        forEachDiff(samples, offset, stride, half, height) { diff -> freq[category(diff)]++ }
        val (bits, values) = optimalTable(freq)
        val codes = IntArray(17)
        val sizes = IntArray(17)
        assignCodes(bits, values, codes, sizes)

        val out = ByteArrayOutputStream(width * height)
        out.marker(0xD8) // SOI
        // SOF3: lossless, Huffman
        out.marker(0xC3)
        out.u16(8 + 3 * 2)
        out.write(PRECISION)
        out.u16(height)
        out.u16(half)
        out.write(2)
        for (id in 1..2) {
            out.write(id)
            out.write(0x11)
            out.write(0)
        }
        // DHT: one DC table shared by both components
        out.marker(0xC4)
        out.u16(2 + 1 + 16 + values.size)
        out.write(0x00)
        for (i in 1..16) out.write(bits[i])
        values.forEach { out.write(it) }
        // SOS: predictor 1, no point transform
        out.marker(0xDA)
        out.u16(6 + 2 * 2)
        out.write(2)
        for (id in 1..2) {
            out.write(id)
            out.write(0x00)
        }
        out.write(1) // Ss = predictor
        out.write(0) // Se
        out.write(0) // Ah/Al

        val w = BitWriter(out)
        forEachDiff(samples, offset, stride, half, height) { diff ->
            val ssss = category(diff)
            w.write(codes[ssss], sizes[ssss])
            if (ssss in 1..15) {
                val extra = if (diff < 0) diff - 1 else diff
                w.write(extra and ((1 shl ssss) - 1), ssss)
            }
        }
        w.flush()
        out.marker(0xD9) // EOI
        return out.toByteArray()
    }

    private inline fun forEachDiff(samples: IntArray, offset: Int, stride: Int, half: Int, height: Int, f: (Int) -> Unit) {
        for (y in 0 until height) {
            val row = offset + y * stride
            for (x in 0 until half) {
                for (c in 0..1) {
                    val sample = samples[row + 2 * x + c]
                    val prediction = when {
                        x == 0 && y == 0 -> 1 shl (PRECISION - 1)
                        y == 0 -> samples[row + 2 * (x - 1) + c]
                        x == 0 -> samples[row - stride + c]
                        else -> samples[row + 2 * (x - 1) + c]
                    }
                    var diff = (sample - prediction) and 0xFFFF
                    if (diff >= 0x8000) diff -= 0x10000
                    f(diff)
                }
            }
        }
    }

    private fun category(diff: Int): Int = if (diff == 0) 0 else 32 - Integer.numberOfLeadingZeros(kotlin.math.abs(diff))

    /** Optimal Huffman code lengths limited to 16 bits (T.81 Annex K.2, as in libjpeg). */
    internal fun optimalTable(frequencies: LongArray): Pair<IntArray, IntArray> {
        val freq = LongArray(257)
        frequencies.copyInto(freq)
        freq[256] = 1 // reserve one code point so no code is all ones
        val codeSize = IntArray(257)
        val others = IntArray(257) { -1 }
        while (true) {
            var c1 = -1
            var v = Long.MAX_VALUE
            for (i in 0..256) if (freq[i] in 1..v) { v = freq[i]; c1 = i }
            var c2 = -1
            v = Long.MAX_VALUE
            for (i in 0..256) if (freq[i] in 1..v && i != c1) { v = freq[i]; c2 = i }
            if (c2 < 0) break
            freq[c1] += freq[c2]
            freq[c2] = 0
            codeSize[c1]++
            var k = c1
            while (others[k] >= 0) { k = others[k]; codeSize[k]++ }
            others[k] = c2
            codeSize[c2]++
            k = c2
            while (others[k] >= 0) { k = others[k]; codeSize[k]++ }
        }
        val bits = IntArray(33)
        for (i in 0..256) if (codeSize[i] > 0) bits[codeSize[i]]++
        for (i in 32 downTo 17) {
            while (bits[i] > 0) {
                var j = i - 2
                while (bits[j] == 0) j--
                bits[i] -= 2
                bits[i - 1]++
                bits[j + 1] += 2
                bits[j]--
            }
        }
        var i = 16
        while (bits[i] == 0) i--
        bits[i]-- // drop the reserved code point
        val values = ArrayList<Int>()
        for (size in 1..32) for (s in 0..255) if (codeSize[s] == size) values += s
        return bits.copyOf(17) to values.toIntArray()
    }

    private fun assignCodes(bits: IntArray, values: IntArray, codes: IntArray, sizes: IntArray) {
        var code = 0
        var k = 0
        for (len in 1..16) {
            repeat(bits[len]) {
                val symbol = values[k++]
                codes[symbol] = code
                sizes[symbol] = len
                code++
            }
            code = code shl 1
        }
    }

    private fun ByteArrayOutputStream.marker(m: Int) {
        write(0xFF)
        write(m)
    }

    private fun ByteArrayOutputStream.u16(v: Int) {
        write(v ushr 8)
        write(v and 0xFF)
    }

    /** MSB-first bit writer with JPEG byte stuffing (0xFF is followed by 0x00). */
    private class BitWriter(private val out: ByteArrayOutputStream) {
        private var buffer = 0L
        private var count = 0

        fun write(value: Int, n: Int) {
            if (n == 0) return
            buffer = (buffer shl n) or (value.toLong() and ((1L shl n) - 1))
            count += n
            while (count >= 8) {
                val b = ((buffer ushr (count - 8)) and 0xFF).toInt()
                out.write(b)
                if (b == 0xFF) out.write(0)
                count -= 8
            }
        }

        fun flush() {
            if (count > 0) write((1 shl (8 - count)) - 1, 8 - count) // pad with ones
        }
    }
}
