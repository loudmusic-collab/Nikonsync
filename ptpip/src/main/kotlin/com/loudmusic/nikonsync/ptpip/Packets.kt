package com.loudmusic.nikonsync.ptpip

/**
 * PTP/IP packets. On the wire every packet is `u32 length (including this 8-byte header)`,
 * `u32 type`, then a type-specific little-endian payload.
 */
public sealed class PtpIpPacket {
    public abstract val type: Int

    internal abstract fun writePayload(w: PtpWriter)

    public fun encode(): ByteArray {
        val payload = PtpWriter().also { writePayload(it) }.toByteArray()
        return PtpWriter(HEADER_SIZE + payload.size)
            .u32(HEADER_SIZE + payload.size)
            .u32(type)
            .bytes(payload)
            .toByteArray()
    }

    public class InitCommandRequest(
        public val guid: ByteArray,
        public val friendlyName: String,
        public val protocolVersion: Int = PROTOCOL_VERSION,
    ) : PtpIpPacket() {
        init {
            require(guid.size == GUID_SIZE) { "GUID must be $GUID_SIZE bytes" }
        }

        override val type: Int get() = PacketType.INIT_COMMAND_REQUEST
        override fun writePayload(w: PtpWriter) {
            w.bytes(guid).utf16z(friendlyName).u32(protocolVersion)
        }
    }

    public class InitCommandAck(
        public val connectionNumber: Int,
        public val guid: ByteArray,
        public val friendlyName: String,
        public val protocolVersion: Int = PROTOCOL_VERSION,
    ) : PtpIpPacket() {
        override val type: Int get() = PacketType.INIT_COMMAND_ACK
        override fun writePayload(w: PtpWriter) {
            w.u32(connectionNumber).bytes(guid).utf16z(friendlyName).u32(protocolVersion)
        }
    }

    public data class InitEventRequest(public val connectionNumber: Int) : PtpIpPacket() {
        override val type: Int get() = PacketType.INIT_EVENT_REQUEST
        override fun writePayload(w: PtpWriter) {
            w.u32(connectionNumber)
        }
    }

    public data object InitEventAck : PtpIpPacket() {
        override val type: Int get() = PacketType.INIT_EVENT_ACK
        override fun writePayload(w: PtpWriter) {}
    }

    public data class InitFail(public val reason: Int) : PtpIpPacket() {
        override val type: Int get() = PacketType.INIT_FAIL
        override fun writePayload(w: PtpWriter) {
            w.u32(reason)
        }
    }

    public data class OperationRequest(
        public val dataPhase: Int,
        public val code: Int,
        public val transactionId: Int,
        public val params: List<Int> = emptyList(),
    ) : PtpIpPacket() {
        init {
            require(params.size <= 5) { "PTP allows at most 5 parameters" }
        }

        override val type: Int get() = PacketType.OPERATION_REQUEST
        override fun writePayload(w: PtpWriter) {
            w.u32(dataPhase).u16(code).u32(transactionId)
            params.forEach { w.u32(it) }
        }
    }

    public data class OperationResponse(
        public val code: Int,
        public val transactionId: Int,
        public val params: List<Int> = emptyList(),
    ) : PtpIpPacket() {
        override val type: Int get() = PacketType.OPERATION_RESPONSE
        override fun writePayload(w: PtpWriter) {
            w.u16(code).u32(transactionId)
            params.forEach { w.u32(it) }
        }
    }

    public data class Event(
        public val code: Int,
        public val transactionId: Int,
        public val params: List<Int> = emptyList(),
    ) : PtpIpPacket() {
        override val type: Int get() = PacketType.EVENT
        override fun writePayload(w: PtpWriter) {
            w.u16(code).u32(transactionId)
            params.forEach { w.u32(it) }
        }
    }

    public data class StartData(public val transactionId: Int, public val totalLength: Long) : PtpIpPacket() {
        override val type: Int get() = PacketType.START_DATA
        override fun writePayload(w: PtpWriter) {
            w.u32(transactionId).u64(totalLength)
        }
    }

    public class Data(public val transactionId: Int, public val payload: ByteArray) : PtpIpPacket() {
        override val type: Int get() = PacketType.DATA
        override fun writePayload(w: PtpWriter) {
            w.u32(transactionId).bytes(payload)
        }
    }

    public class EndData(public val transactionId: Int, public val payload: ByteArray) : PtpIpPacket() {
        override val type: Int get() = PacketType.END_DATA
        override fun writePayload(w: PtpWriter) {
            w.u32(transactionId).bytes(payload)
        }
    }

    public data class Cancel(public val transactionId: Int) : PtpIpPacket() {
        override val type: Int get() = PacketType.CANCEL
        override fun writePayload(w: PtpWriter) {
            w.u32(transactionId)
        }
    }

    public data object ProbeRequest : PtpIpPacket() {
        override val type: Int get() = PacketType.PROBE_REQUEST
        override fun writePayload(w: PtpWriter) {}
    }

    public data object ProbeResponse : PtpIpPacket() {
        override val type: Int get() = PacketType.PROBE_RESPONSE
        override fun writePayload(w: PtpWriter) {}
    }

    public companion object {
        public const val HEADER_SIZE: Int = 8
        public const val GUID_SIZE: Int = 16
        public const val PROTOCOL_VERSION: Int = 0x00010000
        public const val UNKNOWN_DATA_LENGTH: Long = -1L // 0xFFFFFFFFFFFFFFFF

        /** Decodes a packet body (everything after the 8-byte header). */
        public fun decode(type: Int, body: ByteArray): PtpIpPacket {
            val r = PtpReader(body)
            return when (type) {
                PacketType.INIT_COMMAND_REQUEST -> {
                    val guid = r.bytes(GUID_SIZE)
                    val name = r.utf16z(limit = 4)
                    InitCommandRequest(guid, name, if (r.remaining >= 4) r.u32() else PROTOCOL_VERSION)
                }
                PacketType.INIT_COMMAND_ACK -> {
                    val conn = r.u32()
                    val guid = r.bytes(GUID_SIZE)
                    val name = r.utf16z(limit = 4)
                    InitCommandAck(conn, guid, name, if (r.remaining >= 4) r.u32() else PROTOCOL_VERSION)
                }
                PacketType.INIT_EVENT_REQUEST -> InitEventRequest(r.u32())
                PacketType.INIT_EVENT_ACK -> InitEventAck
                PacketType.INIT_FAIL -> InitFail(if (r.remaining >= 4) r.u32() else 0)
                PacketType.OPERATION_REQUEST -> {
                    val phase = r.u32()
                    val code = r.u16()
                    val tid = r.u32()
                    OperationRequest(phase, code, tid, readParams(r))
                }
                PacketType.OPERATION_RESPONSE -> {
                    val code = r.u16()
                    val tid = r.u32()
                    OperationResponse(code, tid, readParams(r))
                }
                PacketType.EVENT -> {
                    val code = r.u16()
                    val tid = r.u32()
                    Event(code, tid, readParams(r))
                }
                PacketType.START_DATA -> {
                    val tid = r.u32()
                    StartData(tid, if (r.remaining >= 8) r.u64() else UNKNOWN_DATA_LENGTH)
                }
                PacketType.DATA -> Data(r.u32(), r.bytes(r.remaining))
                PacketType.END_DATA -> EndData(r.u32(), r.bytes(r.remaining))
                PacketType.CANCEL -> Cancel(r.u32())
                PacketType.PROBE_REQUEST -> ProbeRequest
                PacketType.PROBE_RESPONSE -> ProbeResponse
                else -> throw PtpProtocolException("Unknown PTP/IP packet type ${hex32(type)}")
            }
        }

        private fun readParams(r: PtpReader): List<Int> = List(minOf(r.remaining / 4, 5)) { r.u32() }
    }
}
