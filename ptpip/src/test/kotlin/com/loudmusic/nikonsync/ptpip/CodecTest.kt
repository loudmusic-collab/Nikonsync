package com.loudmusic.nikonsync.ptpip

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class CodecTest {
    private fun hex(s: String): ByteArray =
        s.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `init command request matches the PTP-IP wire layout`() {
        val guid = ByteArray(16) { it.toByte() }
        val bytes = PtpIpPacket.InitCommandRequest(guid, "AB").encode()
        val expected = hex(
            "22 00 00 00  01 00 00 00" + // length 34, type 1
                " 00 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F" + // GUID
                " 41 00 42 00 00 00" + // "AB\0" UTF-16LE
                " 00 00 01 00", // protocol version 1.0
        )
        assertContentEquals(expected, bytes)
    }

    @Test
    fun `operation request matches the PTP-IP wire layout`() {
        val bytes = PtpIpPacket.OperationRequest(DataPhase.NONE_OR_IN, OperationCode.GET_OBJECT_INFO, 7, listOf(0x1234)).encode()
        val expected = hex("16 00 00 00  06 00 00 00  01 00 00 00  08 10  07 00 00 00  34 12 00 00")
        assertContentEquals(expected, bytes)
    }

    @Test
    fun `packets round trip through decode`() {
        val packets = listOf(
            PtpIpPacket.InitEventRequest(3),
            PtpIpPacket.InitEventAck,
            PtpIpPacket.InitFail(1),
            PtpIpPacket.OperationRequest(DataPhase.NONE_OR_IN, OperationCode.GET_PARTIAL_OBJECT, 9, listOf(1, 2, 3)),
            PtpIpPacket.OperationResponse(ResponseCode.OK, 9, listOf(42)),
            PtpIpPacket.Event(EventCode.OBJECT_ADDED, 0, listOf(0x100)),
            PtpIpPacket.StartData(9, 1_000_000_000_000L),
            PtpIpPacket.Cancel(9),
            PtpIpPacket.ProbeRequest,
            PtpIpPacket.ProbeResponse,
        )
        for (p in packets) {
            val bytes = p.encode()
            val decoded = PtpIpPacket.decode(p.type, bytes.copyOfRange(PtpIpPacket.HEADER_SIZE, bytes.size))
            assertEquals(p, decoded)
        }
    }

    @Test
    fun `init command ack parses the camera name`() {
        val ack = PtpIpPacket.InitCommandAck(5, ByteArray(16) { 1 }, "D5500")
        val bytes = ack.encode()
        val decoded = PtpIpPacket.decode(ack.type, bytes.copyOfRange(8, bytes.size))
        assertIs<PtpIpPacket.InitCommandAck>(decoded)
        assertEquals(5, decoded.connectionNumber)
        assertEquals("D5500", decoded.friendlyName)
        assertEquals(PtpIpPacket.PROTOCOL_VERSION, decoded.protocolVersion)
    }

    @Test
    fun `ptp strings round trip, including empty`() {
        val bytes = PtpWriter().ptpString("DSC_0001.NEF").ptpString("").ptpString("é").toByteArray()
        assertEquals(13, bytes[0].toInt()) // 12 chars + terminator
        val r = PtpReader(bytes)
        assertEquals("DSC_0001.NEF", r.ptpString())
        assertEquals("", r.ptpString())
        assertEquals("é", r.ptpString())
        assertEquals(0, r.remaining)
    }

    @Test
    fun `truncated data throws a protocol exception`() {
        assertFailsWith<PtpProtocolException> { PtpReader(byteArrayOf(1, 2)).u32() }
        assertFailsWith<PtpProtocolException> { PtpReader(PtpWriter().u32(1000).toByteArray()).u32Array() }
    }

    @Test
    fun `datasets round trip`() {
        val info = ObjectInfo(
            storageId = 0x00010001, format = ObjectFormat.EXIF_JPEG, protectionStatus = 0,
            compressedSize = 3_000_000_000L, thumbFormat = ObjectFormat.EXIF_JPEG, thumbCompressedSize = 5000,
            thumbWidth = 160, thumbHeight = 120, imageWidth = 6000, imageHeight = 4000, imageBitDepth = 24,
            parent = 2, associationType = 0, associationDesc = 0, sequenceNumber = 0,
            filename = "DSC_0001.JPG", captureDate = "20260925T142233", modificationDate = "20260925T142233", keywords = "",
        )
        assertEquals(info, ObjectInfo.parse(info.encode()))
        assertEquals("JPG", info.extension)
        assertEquals(2026, info.captureDateTime?.year)
        assertEquals(22, info.captureDateTime?.minute)

        val storage = StorageInfo(4, 2, 0, 32L shl 30, 10L shl 30, 1234, "SD", "NIKON D5500")
        assertEquals(storage, StorageInfo.parse(storage.encode()))
    }

    @Test
    fun `object info tolerates missing trailing strings`() {
        val full = PtpWriter()
            .u32(1).u16(ObjectFormat.EXIF_JPEG).u16(0).u32(10).u16(0).u32(0)
            .u32(0).u32(0).u32(0).u32(0).u32(0).u32(0).u16(0).u32(0).u32(0)
            .ptpString("A.JPG")
            .toByteArray()
        val info = ObjectInfo.parse(full)
        assertEquals("A.JPG", info.filename)
        assertEquals("", info.captureDate)
        assertNull(info.captureDateTime)
    }

    @Test
    fun `ptp dates parse with and without suffixes`() {
        assertEquals(5, parsePtpDateTime("20260925T142205")?.second)
        assertEquals(5, parsePtpDateTime("20260925T142205.0Z")?.second)
        assertEquals(14, parsePtpDateTime("20260925T142205+0200")?.hour)
        assertNull(parsePtpDateTime("garbage"))
    }
}
