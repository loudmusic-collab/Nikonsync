package com.loudmusic.dropfoto.gallery

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExifOrientationTest {
    /** Minimal TIFF header + IFD0 with one Orientation entry. */
    private fun tiff(orientation: Int, little: Boolean): ByteArray {
        val b = java.io.ByteArrayOutputStream()
        fun u16(v: Int) = if (little) { b.write(v and 0xFF); b.write(v shr 8) } else { b.write(v shr 8); b.write(v and 0xFF) }
        fun u32(v: Int) = if (little) { u16(v and 0xFFFF); u16(v ushr 16) } else { u16(v ushr 16); u16(v and 0xFFFF) }
        b.write(if (little) 'I'.code else 'M'.code); b.write(if (little) 'I'.code else 'M'.code)
        u16(42); u32(8)
        u16(2) // two entries
        u16(0x010F); u16(2); u32(4); u32(0) // Make (ignored)
        u16(0x0112); u16(3); u32(1); u16(orientation); u16(0)
        u32(0)
        return b.toByteArray()
    }

    private fun jpeg(tiff: ByteArray): ByteArray {
        val app1 = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val len = app1.size + 2
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) +
            app1 + byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2)
    }

    @Test
    fun `reads orientation from JPEG and NEF headers in both byte orders`() {
        assertEquals(90, ExifOrientation.rotationDegrees(jpeg(tiff(6, little = false))))
        assertEquals(270, ExifOrientation.rotationDegrees(jpeg(tiff(8, little = true))))
        assertEquals(180, ExifOrientation.rotationDegrees(tiff(3, little = true))) // NEF is TIFF-based
        assertEquals(0, ExifOrientation.rotationDegrees(tiff(1, little = false)))
    }

    @Test
    fun `returns null for data without EXIF`() {
        assertNull(ExifOrientation.rotationDegrees(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDA.toByte(), 0, 2, 0, 0)))
        assertNull(ExifOrientation.rotationDegrees(ByteArray(100)))
    }
}
