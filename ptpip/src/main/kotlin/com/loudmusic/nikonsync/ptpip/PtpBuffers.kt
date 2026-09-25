package com.loudmusic.nikonsync.ptpip

/**
 * Little-endian writer for PTP datasets and PTP/IP packet payloads.
 * Unsigned 32-bit values are carried as their raw bit pattern in an [Int].
 */
public class PtpWriter(initialCapacity: Int = 64) {
    private var buf = ByteArray(initialCapacity)

    public var size: Int = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + extra))
    }

    public fun u8(v: Int): PtpWriter = apply {
        ensure(1)
        buf[size++] = v.toByte()
    }

    public fun u16(v: Int): PtpWriter = apply {
        ensure(2)
        buf[size++] = v.toByte()
        buf[size++] = (v ushr 8).toByte()
    }

    public fun u32(v: Int): PtpWriter = apply {
        ensure(4)
        buf[size++] = v.toByte()
        buf[size++] = (v ushr 8).toByte()
        buf[size++] = (v ushr 16).toByte()
        buf[size++] = (v ushr 24).toByte()
    }

    public fun u64(v: Long): PtpWriter = apply {
        u32(v.toInt())
        u32((v ushr 32).toInt())
    }

    public fun bytes(b: ByteArray): PtpWriter = apply {
        ensure(b.size)
        b.copyInto(buf, size)
        size += b.size
    }

    /** PTP string: u8 character count (including the terminator), then UTF-16LE chars. */
    public fun ptpString(s: String): PtpWriter = apply {
        if (s.isEmpty()) {
            u8(0)
            return this
        }
        val chars = s.take(254)
        u8(chars.length + 1)
        for (c in chars) u16(c.code)
        u16(0)
    }

    /** Null-terminated UTF-16LE string without a length prefix (used in PTP/IP init packets). */
    public fun utf16z(s: String): PtpWriter = apply {
        for (c in s) u16(c.code)
        u16(0)
    }

    public fun u16Array(values: List<Int>): PtpWriter = apply {
        u32(values.size)
        values.forEach { u16(it) }
    }

    public fun u32Array(values: List<Int>): PtpWriter = apply {
        u32(values.size)
        values.forEach { u32(it) }
    }

    public fun toByteArray(): ByteArray = buf.copyOf(size)
}

/** Little-endian reader over a byte array. Throws [PtpProtocolException] on truncated input. */
public class PtpReader(private val data: ByteArray, start: Int = 0, private val end: Int = data.size) {
    public var position: Int = start
        private set

    public val remaining: Int get() = end - position

    private fun need(n: Int) {
        if (remaining < n) throw PtpProtocolException("Truncated data: need $n bytes, have $remaining")
    }

    public fun u8(): Int {
        need(1)
        return data[position++].toInt() and 0xFF
    }

    public fun u16(): Int {
        need(2)
        val v = (data[position].toInt() and 0xFF) or ((data[position + 1].toInt() and 0xFF) shl 8)
        position += 2
        return v
    }

    public fun u32(): Int {
        need(4)
        val v = (data[position].toInt() and 0xFF) or
            ((data[position + 1].toInt() and 0xFF) shl 8) or
            ((data[position + 2].toInt() and 0xFF) shl 16) or
            ((data[position + 3].toInt() and 0xFF) shl 24)
        position += 4
        return v
    }

    /** Reads an unsigned 32-bit value as a non-negative [Long]. */
    public fun u32AsLong(): Long = u32().toLong() and 0xFFFFFFFFL

    public fun u64(): Long {
        val lo = u32AsLong()
        val hi = u32AsLong()
        return lo or (hi shl 32)
    }

    public fun bytes(n: Int): ByteArray {
        need(n)
        val out = data.copyOfRange(position, position + n)
        position += n
        return out
    }

    public fun ptpString(): String {
        val count = u8()
        if (count == 0) return ""
        val sb = StringBuilder(count)
        repeat(count) {
            val c = u16()
            if (c != 0) sb.append(c.toChar())
        }
        return sb.toString()
    }

    /** Lenient variant for trailing optional strings: returns "" at end of data. */
    public fun ptpStringOrEmpty(): String = if (remaining <= 0) "" else ptpString()

    /** Null-terminated UTF-16LE string. Stops at the terminator or at [limit] bytes before the end. */
    public fun utf16z(limit: Int = 0): String {
        val sb = StringBuilder()
        while (remaining - limit >= 2) {
            val c = u16()
            if (c == 0) break
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    public fun u16Array(): List<Int> {
        val n = u32AsLong()
        if (n * 2 > remaining) throw PtpProtocolException("Array of $n u16 exceeds remaining $remaining bytes")
        return List(n.toInt()) { u16() }
    }

    public fun u32Array(): List<Int> {
        val n = u32AsLong()
        if (n * 4 > remaining) throw PtpProtocolException("Array of $n u32 exceeds remaining $remaining bytes")
        return List(n.toInt()) { u32() }
    }
}
