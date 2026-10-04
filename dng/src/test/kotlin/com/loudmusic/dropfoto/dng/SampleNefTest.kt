package com.loudmusic.dropfoto.dng

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * End-to-end check on a real D5500 NEF (DSC_8878.NEF on the `samples` branch), when available via
 * DROPFOTO_SAMPLE_NEF. Reference values come from LibRaw 0.22 (rawpy) on the same file.
 */
class SampleNefTest {
    private val sample = System.getProperty("sampleNef")?.let(::File)?.takeIf { it.exists() }

    @Test
    fun `decodes exactly like LibRaw and converts to an equivalent DNG`() {
        val file = sample ?: return println("No sample NEF given; skipping")
        val nef = NefFile(file.readBytes())
        assertEquals("NIKON D5500", nef.model)
        assertEquals(6016, nef.width)
        assertEquals(4016, nef.height)
        assertContentEquals(intArrayOf(600, 600, 600, 600), nef.blackLevels)

        val decoder = NikonDecoder(nef)
        val raw = IntArray(nef.width * nef.height)
        val sha = MessageDigest.getInstance("SHA-256")
        decoder.decode { row, values ->
            System.arraycopy(values, 0, raw, row * nef.width, nef.width)
            for (v in values) sha.update(byteArrayOf(v.toByte(), (v ushr 8).toByte()))
        }
        assertEquals(0, decoder.errors)
        assertEquals(16380, decoder.whiteLevel)
        assertEquals("bd8628d206023deef6bbd54b80c3bed8e6ca7f845c95846ebf78ed8f01029fa2", sha.digest().joinToString("") { "%02x".format(it) })
        assertContentEquals(intArrayOf(9060, 16350, 9444, 16350, 9476, 16350), raw.copyOfRange(0, 6))

        val dngFile = File.createTempFile("sample", ".dng")
        try {
            NefToDng.convert(file.readBytes(), dngFile)
            val dng = dngFile.readBytes()
            val (r, ifd0Offset) = TiffReader.open(dng)
            val ifd0 = r.ifd(ifd0Offset)
            assertContentEquals(byteArrayOf(1, 4, 0, 0), ifd0[50706]!!.bytes())
            assertEquals(16380L, ifd0[50717]!!.int())
            assertEquals(600L, ifd0[50714]!!.int(0))
            assertEquals(1 / 2.10546875, ifd0[50728]!!.double(0), 1e-5)
            assertEquals(1 / 1.375, ifd0[50728]!!.double(2), 1e-5)
            assertEquals(8821.0 / 10000, ifd0[50721]!!.double(0), 1e-9)
            assertEquals(6000L, ifd0[50720]!!.int(0))

            // Every tile decodes back to exactly the NEF's raw values.
            val offsets = ifd0[324]!!.ints()
            val counts = ifd0[325]!!.ints()
            val across = (nef.width + 255) / 256
            for (t in offsets.indices) {
                val tile = TestLjpegDecoder.decode(dng.copyOfRange(offsets[t].toInt(), (offsets[t] + counts[t]).toInt()))
                val tx = t % across
                val ty = t / across
                for (y in 0 until 256) {
                    val row = ty * 256 + y
                    if (row >= nef.height) break
                    for (x in 0 until 256) {
                        val col = tx * 256 + x
                        if (col >= nef.width) break
                        if (tile.samples[y * 256 + x] != raw[row * nef.width + col]) {
                            error("tile $t differs at ($col,$row)")
                        }
                    }
                }
            }
        } finally {
            dngFile.delete()
        }
    }
}
