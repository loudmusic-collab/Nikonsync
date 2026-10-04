package com.loudmusic.dropfoto.dng

/** Minimal TIFF reader: IFD entries with typed accessors. Enough for NEF files and Nikon maker notes. */
class TiffReader(val data: ByteArray, val base: Int = 0, val littleEndian: Boolean) {
    fun u8(at: Int): Int = data[at].toInt() and 0xFF

    fun u16(at: Int): Int = if (littleEndian) u8(at) or (u8(at + 1) shl 8) else (u8(at) shl 8) or u8(at + 1)

    fun u32(at: Int): Long = if (littleEndian) {
        u16(at).toLong() or (u16(at + 2).toLong() shl 16)
    } else {
        (u16(at).toLong() shl 16) or u16(at + 2).toLong()
    }

    fun s32(at: Int): Int = u32(at).toInt()

    /** Reads the IFD at [offset] (relative to [base]). */
    fun ifd(offset: Long): Ifd {
        val start = base + offset.toInt()
        val count = u16(start)
        val entries = (0 until count).map { i ->
            val e = start + 2 + i * 12
            Entry(this, tag = u16(e), type = u16(e + 2), count = u32(e + 4).toInt(), entryPos = e)
        }
        return Ifd(entries.associateBy { it.tag }, next = u32(start + 2 + count * 12))
    }

    companion object {
        /** Parses a TIFF header at [base]: "II*\0" or "MM\0*", returning the reader and IFD0 offset. */
        fun open(data: ByteArray, base: Int = 0): Pair<TiffReader, Long> {
            val little = when {
                data[base] == 'I'.code.toByte() && data[base + 1] == 'I'.code.toByte() -> true
                data[base] == 'M'.code.toByte() && data[base + 1] == 'M'.code.toByte() -> false
                else -> throw IllegalArgumentException("Not a TIFF file")
            }
            val r = TiffReader(data, base, little)
            require(r.u16(base + 2) == 42) { "Bad TIFF magic" }
            return r to r.u32(base + 4)
        }

        val TYPE_SIZES = intArrayOf(0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8, 4)
    }
}

class Ifd(val entries: Map<Int, Entry>, val next: Long) {
    operator fun get(tag: Int): Entry? = entries[tag]
}

class Entry(private val r: TiffReader, val tag: Int, val type: Int, val count: Int, private val entryPos: Int) {
    val byteSize: Int get() = TiffReader.TYPE_SIZES.getOrElse(type) { 1 } * count

    /** Absolute position of the value bytes in the file. */
    val valuePos: Int get() = if (byteSize <= 4) entryPos + 8 else r.base + r.u32(entryPos + 8).toInt()

    fun bytes(): ByteArray = r.data.copyOfRange(valuePos, valuePos + byteSize)

    fun int(i: Int = 0): Long = when (type) {
        1, 2, 6, 7 -> r.u8(valuePos + i).toLong()
        3, 8 -> r.u16(valuePos + i * 2).toLong()
        4, 9, 13 -> r.u32(valuePos + i * 4)
        else -> throw IllegalStateException("Tag $tag type $type isn't an integer")
    }

    fun ints(): LongArray = LongArray(count) { int(it) }

    /** Rational as numerator/denominator (signed for SRATIONAL). */
    fun rational(i: Int = 0): Pair<Long, Long> {
        val at = valuePos + i * 8
        return if (type == 10) r.s32(at).toLong() to r.s32(at + 4).toLong() else r.u32(at) to r.u32(at + 4)
    }

    fun double(i: Int = 0): Double = rational(i).let { (n, d) -> if (d == 0L) 0.0 else n.toDouble() / d }

    fun string(): String = String(bytes(), Charsets.ISO_8859_1).trimEnd('\u0000', ' ')
}
