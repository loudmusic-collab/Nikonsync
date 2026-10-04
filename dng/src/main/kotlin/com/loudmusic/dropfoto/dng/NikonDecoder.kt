package com.loudmusic.dropfoto.dng

/**
 * Decodes Nikon's compressed NEF raw data (lossless and lossy, 12- and 14-bit) into linear sensor
 * values, one row at a time. Follows the well-established algorithm used by dcraw and LibRaw:
 * Huffman-coded differences against same-colour neighbours, then a linearization curve from the
 * maker note (tag 0x0096). Lossy files switch Huffman tables at a "split" row.
 */
class NikonDecoder(private val nef: NefFile) {
    /** Linearization curve: decoded value -> linear sensor value. */
    val curve: IntArray

    /** Highest value the curve produces: the sensor's white level. */
    val whiteLevel: Int

    private val tree: Int
    private val split: Int
    private val vpredInit: IntArray
    private var maxValue: Int

    init {
        require(nef.isNikonCompressed) { "Not a Nikon-compressed NEF" }
        val meta = requireNotNull(nef.linearization) { "NEF has no decompression data (maker note 0x0096)" }
        val m = nef.makerNote!!
        val at = meta.valuePos
        val ver0 = m.u8(at)
        val ver1 = m.u8(at + 1)
        require(ver0 != 0x49 && ver1 != 0x58) { "This NEF variant (${"%02X %02X".format(ver0, ver1)}) isn't supported yet" }

        var t = if (ver0 == 0x46) 2 else 0
        if (nef.bitsPerSample == 14) t += 3
        tree = t

        vpredInit = IntArray(4) { m.u16(at + 2 + it * 2) }
        var max = (1 shl nef.bitsPerSample) and 0x7FFF
        val csize = m.u16(at + 10)
        val step = if (csize > 1) max / (csize - 1) else 0
        val c = IntArray(0x10000) { it }
        var splitRow = 0
        if (ver0 == 0x44 && ver1 == 0x20 && step > 0) {
            for (i in 0 until csize) c[i * step] = m.u16(at + 12 + i * 2)
            for (i in 0 until max) {
                val base = i - i % step
                c[i] = (c[base] * (step - i % step) + c[base + step] * (i % step)) / step
            }
            splitRow = m.u16(at + 562)
        } else if (ver0 != 0x46 && csize <= 0x4001) {
            for (i in 0 until csize) c[i] = m.u16(at + 12 + i * 2)
            max = csize
        }
        while (max >= 2 && c[max - 2] == c[max - 1]) max--
        maxValue = max
        curve = c
        whiteLevel = (0 until maxOf(max, 1)).maxOf { c[it] }
        split = splitRow
    }

    /** Counts samples that fell outside the expected range (a sign of a corrupt file). */
    var errors: Int = 0
        private set

    /**
     * Decodes the whole image, calling [onRow] for each row with linear values (0..[whiteLevel]).
     * The same [IntArray] is reused for every row.
     */
    fun decode(onRow: (row: Int, values: IntArray) -> Unit) {
        val width = nef.width
        val height = nef.height
        val bits = BitReader(nef.data, nef.rawOffset, nef.rawOffset + nef.rawLength)
        var huff = Huffman(TREES[tree])
        val vpred = vpredInit.copyOf() // [row&1][col] as index (row&1)*2+col
        val hpred = IntArray(2)
        val out = IntArray(width)
        var min = 0
        var max = maxValue
        for (row in 0 until height) {
            if (split != 0 && row == split) {
                huff = Huffman(TREES[tree + 1])
                min = 16
                max += min shl 1
            }
            for (col in 0 until width) {
                val i = huff.decode(bits)
                val len = i and 15
                val shl = i ushr 4
                var diff = if (len == 0) 0 else (((bits.get(len - shl) shl 1) + 1) shl shl) ushr 1
                if (len != 0 && diff and (1 shl (len - 1)) == 0) diff -= (1 shl len) - (if (shl == 0) 1 else 0)
                if (col < 2) {
                    val v = (vpred[(row and 1) * 2 + col] + diff) and 0xFFFF
                    vpred[(row and 1) * 2 + col] = v
                    hpred[col] = v
                } else {
                    hpred[col and 1] = (hpred[col and 1] + diff) and 0xFFFF
                }
                val h = hpred[col and 1]
                if (((h + min) and 0xFFFF) >= max) errors++
                val signed = h.toShort().toInt()
                out[col] = curve[signed.coerceIn(0, 0x3FFF)]
            }
            onRow(row, out)
        }
    }

    /** MSB-first bit reader without JPEG byte stuffing (Nikon doesn't use it). */
    private class BitReader(private val data: ByteArray, private var pos: Int, private val end: Int) {
        private var buffer = 0L
        private var count = 0

        private fun fill(n: Int) {
            while (count < n) {
                val b = if (pos < end) data[pos++].toInt() and 0xFF else 0
                buffer = (buffer shl 8) or b.toLong()
                count += 8
            }
        }

        fun peek(n: Int): Int {
            fill(n)
            return ((buffer ushr (count - n)) and ((1L shl n) - 1)).toInt()
        }

        fun skip(n: Int) {
            count -= n
        }

        fun get(n: Int): Int {
            if (n <= 0) return 0
            val v = peek(n)
            count -= n
            return v
        }
    }

    /** Canonical Huffman decoder from a dcraw-style table: 16 code-length counts, then symbols. */
    private class Huffman(source: IntArray) {
        private val maxLen: Int
        private val table: IntArray // (length shl 8) or symbol, indexed by the next maxLen bits

        init {
            var max = 16
            while (max > 0 && source[max - 1] == 0) max--
            maxLen = max
            table = IntArray(1 shl max)
            var h = 0
            var symbolIndex = 16
            for (len in 1..max) {
                repeat(source[len - 1]) {
                    val symbol = source[symbolIndex++]
                    repeat(1 shl (max - len)) {
                        if (h < table.size) table[h++] = (len shl 8) or symbol
                    }
                }
            }
        }

        fun decode(bits: BitReader): Int {
            val entry = table[bits.peek(maxLen)]
            bits.skip(entry ushr 8)
            return entry and 0xFF
        }
    }

    private companion object {
        /** dcraw's nikon_tree: counts per code length (16), then symbols. len = sym & 15, shl = sym >> 4. */
        val TREES: Array<IntArray> = arrayOf(
            intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 0, // 12-bit lossy
                5, 4, 3, 6, 2, 7, 1, 0, 8, 9, 11, 10, 12),
            intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 0, // 12-bit lossy after split
                0x39, 0x5a, 0x38, 0x27, 0x16, 5, 4, 3, 2, 1, 0, 11, 12, 12),
            intArrayOf(0, 1, 4, 2, 3, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, // 12-bit lossless
                5, 4, 6, 3, 7, 2, 8, 1, 9, 0, 10, 11, 12),
            intArrayOf(0, 1, 4, 3, 1, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 0, // 14-bit lossy
                5, 6, 4, 7, 8, 3, 9, 2, 1, 0, 10, 11, 12, 13, 14),
            intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, // 14-bit lossy after split
                8, 0x5c, 0x4b, 0x3a, 0x29, 7, 6, 5, 4, 3, 2, 1, 0, 13, 14),
            intArrayOf(0, 1, 4, 2, 2, 3, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, // 14-bit lossless
                7, 6, 8, 5, 9, 4, 10, 3, 11, 12, 2, 0, 1, 13, 14),
        )
    }
}
