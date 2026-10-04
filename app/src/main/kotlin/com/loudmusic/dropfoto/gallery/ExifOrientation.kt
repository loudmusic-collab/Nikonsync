package com.loudmusic.dropfoto.gallery

/**
 * Reads the EXIF orientation from the first few KB of a JPEG or a TIFF-based RAW (NEF).
 * The camera's thumbnails aren't rotated, so portrait shots need this to display upright.
 */
object ExifOrientation {
    /** Clockwise rotation in degrees (0, 90, 180, 270) needed to display the image upright, or null if unknown. */
    fun rotationDegrees(head: ByteArray): Int? = when (orientation(head)) {
        null -> null
        3 -> 180
        6 -> 90
        8 -> 270
        else -> 0
    }

    fun orientation(b: ByteArray): Int? {
        if (b.size < 8) return null
        if (b.u8(0) == 0xFF && b.u8(1) == 0xD8) {
            var i = 2
            while (i + 4 <= b.size) {
                if (b.u8(i) != 0xFF) return null
                val marker = b.u8(i + 1)
                if (marker == 0xD9 || marker == 0xDA) return null // end of image / start of scan
                val length = (b.u8(i + 2) shl 8) or b.u8(i + 3)
                if (marker == 0xE1 && i + 10 <= b.size && isExifHeader(b, i + 4)) {
                    return tiffOrientation(b, i + 10, minOf(b.size, i + 2 + length))
                }
                i += 2 + length
            }
            return null
        }
        return tiffOrientation(b, 0, b.size)
    }

    private fun isExifHeader(b: ByteArray, at: Int) =
        b.u8(at) == 'E'.code && b.u8(at + 1) == 'x'.code && b.u8(at + 2) == 'i'.code &&
            b.u8(at + 3) == 'f'.code && b.u8(at + 4) == 0 && b.u8(at + 5) == 0

    private fun tiffOrientation(b: ByteArray, start: Int, end: Int): Int? {
        if (start + 8 > end) return null
        val little = when {
            b.u8(start) == 'I'.code && b.u8(start + 1) == 'I'.code -> true
            b.u8(start) == 'M'.code && b.u8(start + 1) == 'M'.code -> false
            else -> return null
        }
        fun u16(at: Int): Int = if (little) b.u8(at) or (b.u8(at + 1) shl 8) else (b.u8(at) shl 8) or b.u8(at + 1)
        fun u32(at: Int): Long = if (little) {
            (u16(at).toLong()) or (u16(at + 2).toLong() shl 16)
        } else {
            (u16(at).toLong() shl 16) or u16(at + 2).toLong()
        }
        if (u16(start + 2) != 42) return null
        val ifd = start + u32(start + 4)
        if (ifd + 2 > end) return null
        val count = u16(ifd.toInt())
        for (n in 0 until count) {
            val entry = ifd.toInt() + 2 + n * 12
            if (entry + 12 > end) return null
            if (u16(entry) == ORIENTATION_TAG) return u16(entry + 8).takeIf { it in 1..8 }
        }
        return null
    }

    private fun ByteArray.u8(at: Int) = this[at].toInt() and 0xFF

    private const val ORIENTATION_TAG = 0x0112

    /** How many bytes from the start of a file are enough to find the orientation. */
    const val HEAD_BYTES = 8 * 1024
}
