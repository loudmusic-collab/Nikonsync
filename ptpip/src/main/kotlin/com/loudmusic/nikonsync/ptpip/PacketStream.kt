package com.loudmusic.nikonsync.ptpip

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** Receives the bytes of a data phase without buffering the whole object in memory. */
public interface DataSink {
    /** Called once when the camera announces the data phase. [totalLength] may be [PtpIpPacket.UNKNOWN_DATA_LENGTH]. */
    public fun onStart(totalLength: Long) {}

    public fun write(buffer: ByteArray, offset: Int, length: Int)
}

/** Collects a data phase into memory. Use only for small payloads such as datasets and thumbnails. */
public class ByteArraySink : DataSink {
    private val out = java.io.ByteArrayOutputStream()
    override fun write(buffer: ByteArray, offset: Int, length: Int): Unit = out.write(buffer, offset, length)
    public fun toByteArray(): ByteArray = out.toByteArray()
}

/** Streams a data phase into an [OutputStream]. */
public class OutputStreamSink(private val out: OutputStream) : DataSink {
    public var bytesWritten: Long = 0
        private set

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        out.write(buffer, offset, length)
        bytesWritten += length
    }
}

/** Header of a packet that has been read from the stream but whose body hasn't been consumed yet. */
public data class PacketHeader(val length: Long, val type: Int) {
    val bodyLength: Long get() = length - PtpIpPacket.HEADER_SIZE
}

/**
 * Reads and writes PTP/IP packets on one TCP stream. Not thread-safe for reads; writes are synchronized.
 * Data and End Data packets can be streamed straight into a [DataSink] so multi-megabyte
 * files never need a matching byte array.
 */
public class PacketStream(
    public val channelName: String,
    private val input: InputStream,
    private val output: OutputStream,
    private val logger: PacketLogger? = null,
) {
    private val headerBuf = ByteArray(PtpIpPacket.HEADER_SIZE)
    private val copyBuf = ByteArray(COPY_BUFFER_SIZE)
    private val writeLock = Any()

    public fun write(packet: PtpIpPacket) {
        val bytes = packet.encode()
        synchronized(writeLock) {
            output.write(bytes)
            output.flush()
        }
        logger?.onPacket(
            channelName, outgoing = true, packet.type, bytes.size.toLong(),
            bytes.copyOfRange(PtpIpPacket.HEADER_SIZE, minOf(bytes.size, PtpIpPacket.HEADER_SIZE + PREVIEW_BYTES)),
        )
    }

    public fun readHeader(): PacketHeader {
        readFully(headerBuf, 0, PtpIpPacket.HEADER_SIZE)
        val r = PtpReader(headerBuf)
        val length = r.u32AsLong()
        val type = r.u32()
        if (length < PtpIpPacket.HEADER_SIZE) {
            throw PtpProtocolException("$channelName: invalid packet length $length for ${PacketType.name(type)}")
        }
        return PacketHeader(length, type)
    }

    /** Reads the body of a non-streamed packet and decodes it. */
    public fun readBody(header: PacketHeader, maxBodyLength: Int = MAX_CONTROL_PACKET_BODY): PtpIpPacket {
        if (header.bodyLength > maxBodyLength) {
            throw PtpProtocolException(
                "$channelName: ${PacketType.name(header.type)} body of ${header.bodyLength} bytes exceeds limit $maxBodyLength",
            )
        }
        val body = ByteArray(header.bodyLength.toInt())
        readFully(body, 0, body.size)
        logger?.onPacket(channelName, outgoing = false, header.type, header.length, body.copyOf(minOf(body.size, PREVIEW_BYTES)))
        return PtpIpPacket.decode(header.type, body)
    }

    public fun readPacket(maxBodyLength: Int = MAX_CONTROL_PACKET_BODY): PtpIpPacket = readBody(readHeader(), maxBodyLength)

    /**
     * Consumes the body of a Data or End Data packet, forwarding its payload to [sink].
     * Returns the transaction ID it carried.
     */
    public fun streamDataBody(header: PacketHeader, sink: DataSink): Int {
        require(header.type == PacketType.DATA || header.type == PacketType.END_DATA)
        if (header.bodyLength < 4) throw PtpProtocolException("$channelName: data packet too short")
        readFully(headerBuf, 0, 4)
        val tid = PtpReader(headerBuf, 0, 4).u32()
        var left = header.bodyLength - 4
        var preview: ByteArray? = null
        while (left > 0) {
            val n = input.read(copyBuf, 0, minOf(left, copyBuf.size.toLong()).toInt())
            if (n < 0) throw EOFException("$channelName: stream ended inside a data packet")
            if (preview == null) preview = copyBuf.copyOf(minOf(n, PREVIEW_BYTES))
            sink.write(copyBuf, 0, n)
            left -= n
        }
        logger?.onPacket(channelName, outgoing = false, header.type, header.length, preview ?: ByteArray(0))
        return tid
    }

    private fun readFully(buf: ByteArray, off: Int, len: Int) {
        var read = 0
        while (read < len) {
            val n = input.read(buf, off + read, len - read)
            if (n < 0) throw EOFException("$channelName: connection closed by camera")
            read += n
        }
    }

    public companion object {
        public const val MAX_CONTROL_PACKET_BODY: Int = 1 shl 20
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private const val PREVIEW_BYTES = 64
    }
}
