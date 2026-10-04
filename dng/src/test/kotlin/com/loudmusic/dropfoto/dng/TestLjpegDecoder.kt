package com.loudmusic.dropfoto.dng

/** Independent lossless JPEG decoder (test only), to check what LosslessJpeg writes. */
object TestLjpegDecoder {
    class Image(val width: Int, val height: Int, val samples: IntArray)

    fun decode(b: ByteArray): Image {
        var p = 2
        require(b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte())
        var precision = 0
        var lines = 0
        var perLine = 0
        var comps = 0
        val tables = HashMap<Int, Map<Long, Int>>() // key: (len shl 32) or code
        fun u16(at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
        while (true) {
            require(b[p] == 0xFF.toByte())
            val marker = b[p + 1].toInt() and 0xFF
            val len = u16(p + 2)
            when (marker) {
                0xC3 -> {
                    precision = b[p + 4].toInt() and 0xFF
                    lines = u16(p + 5)
                    perLine = u16(p + 7)
                    comps = b[p + 9].toInt() and 0xFF
                }
                0xC4 -> {
                    var q = p + 4
                    while (q < p + 2 + len) {
                        val id = b[q].toInt() and 0x0F
                        val counts = IntArray(16) { b[q + 1 + it].toInt() and 0xFF }
                        var v = q + 17
                        val map = HashMap<Long, Int>()
                        var code = 0L
                        for (l in 1..16) {
                            repeat(counts[l - 1]) { map[(l.toLong() shl 32) or code] = b[v++].toInt() and 0xFF; code++ }
                            code = code shl 1
                        }
                        tables[id] = map
                        q = v
                    }
                }
                0xDA -> {
                    p += 2 + len
                    break
                }
            }
            p += 2 + len
        }
        val table = tables.getValue(0)
        var buf = 0L
        var count = 0
        fun bit(): Int {
            if (count == 0) {
                var byte = b[p++].toInt() and 0xFF
                if (byte == 0xFF) {
                    val next = b[p++].toInt() and 0xFF
                    require(next == 0) { "marker inside entropy data" }
                    byte = 0xFF
                }
                buf = byte.toLong()
                count = 8
            }
            count--
            return ((buf ushr count) and 1).toInt()
        }
        fun bits(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or bit() }; return v }
        fun symbol(): Int {
            var code = 0L
            for (l in 1..16) {
                code = (code shl 1) or bit().toLong()
                table[(l.toLong() shl 32) or code]?.let { return it }
            }
            error("bad Huffman code")
        }
        val width = perLine * comps
        val out = IntArray(width * lines)
        for (y in 0 until lines) for (x in 0 until perLine) for (c in 0 until comps) {
            val ssss = symbol()
            val diff = when (ssss) {
                0 -> 0
                16 -> -32768
                else -> bits(ssss).let { v -> if (v < (1 shl (ssss - 1))) v - (1 shl ssss) + 1 else v }
            }
            val i = y * width + x * comps + c
            val pred = when {
                x == 0 && y == 0 -> 1 shl (precision - 1)
                y == 0 -> out[i - comps]
                x == 0 -> out[i - width]
                else -> out[i - comps]
            }
            out[i] = (pred + diff) and 0xFFFF
        }
        return Image(width, lines, out)
    }
}
