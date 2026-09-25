package com.loudmusic.nikonsync.ptpip

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Observes every packet sent or received. Used for protocol traces from real cameras. */
public fun interface PacketLogger {
    /**
     * @param channel "cmd" or "evt"
     * @param length full packet length including the header
     * @param preview the first bytes of the packet body (after the 8-byte header)
     */
    public fun onPacket(channel: String, outgoing: Boolean, type: Int, length: Long, preview: ByteArray)
}

/** Writes one human-readable line per packet, with a hex preview of the body. */
public class TextPacketLogger(private val out: Appendable) : PacketLogger {
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    override fun onPacket(channel: String, outgoing: Boolean, type: Int, length: Long, preview: ByteArray) {
        val arrow = if (outgoing) "->" else "<-"
        val hex = preview.joinToString(" ") { "%02X".format(it) }
        val line = "${LocalTime.now().format(time)} $channel $arrow ${PacketType.name(type)} len=$length  $hex"
        synchronized(this) {
            out.append(line).append('\n')
            (out as? java.io.Flushable)?.flush()
        }
    }
}
