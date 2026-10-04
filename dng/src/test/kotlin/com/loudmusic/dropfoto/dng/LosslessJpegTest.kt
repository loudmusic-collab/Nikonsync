package com.loudmusic.dropfoto.dng

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LosslessJpegTest {
    @Test
    fun `round trips CFA tiles, including extreme values`() {
        val random = Random(7)
        val w = 64
        val h = 48
        val stride = 80
        val samples = IntArray(stride * h) { i ->
            val x = i % stride
            val y = i / stride
            when {
                x == 3 && y == 5 -> 0
                x == 4 && y == 5 -> 65535 // forces the 16-bit difference category
                else -> (600 + x * 37 + y * 11 + random.nextInt(200) + if ((x + y) % 2 == 0) 3000 else 0) and 0x3FFF
            }
        }
        val encoded = LosslessJpeg.encode(samples, 8, stride, w, h)
        val decoded = TestLjpegDecoder.decode(encoded)
        assertEquals(w, decoded.width)
        assertEquals(h, decoded.height)
        val expected = IntArray(w * h) { samples[8 + (it / w) * stride + it % w] }
        assertContentEquals(expected, decoded.samples)
    }

    @Test
    fun `optimal Huffman tables stay within 16 bits and the Kraft limit`() {
        val freq = LongArray(17) { 1L shl (it * 2).coerceAtMost(40) } // very skewed
        val (bits, values) = LosslessJpeg.optimalTable(freq)
        assertEquals(17, values.size)
        var kraft = 0.0
        for (len in 1..16) kraft += bits[len] / Math.pow(2.0, len.toDouble())
        assertTrue(kraft < 1.0, "Kraft sum $kraft")
    }
}
