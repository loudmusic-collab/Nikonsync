package com.loudmusic.nikonsync.ptpip

import com.loudmusic.nikonsync.ptpip.fake.FakeCamera
import com.loudmusic.nikonsync.ptpip.fake.FakeObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PtpCameraTest {
    private val cameras = mutableListOf<FakeCamera>()

    private fun fake(
        options: FakeCamera.Options = FakeCamera.Options(),
        objects: List<FakeObject> = FakeCamera.sampleCard(),
    ) = FakeCamera(objects, options).start().also { cameras += it }

    private fun config(fake: FakeCamera, readTimeoutMillis: Int = 5_000) = PtpIpConfig(
        host = fake.host,
        port = fake.port,
        guid = ByteArray(16) { 7 },
        friendlyName = "test",
        readTimeoutMillis = readTimeoutMillis,
    )

    @AfterTest
    fun tearDown() {
        cameras.forEach { it.close() }
    }

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    @Test
    fun `connects, reads device info and opens a session`() = test {
        val fake = fake()
        val camera = PtpCamera.connect(config(fake))
        assertEquals("D5500", camera.deviceInfo.model)
        assertTrue(camera.isNikon)
        assertEquals("D5500", camera.connection.cameraName)

        // GetDeviceInfo is sessionless (tid 0), OpenSession uses tid 0, then IDs count from 1.
        camera.getStorageIds()
        camera.getStorageIds()
        val tids = fake.requests.map { OperationCode.name(it.code) to it.transactionId }
        assertEquals(
            listOf("GetDeviceInfo" to 0, "OpenSession" to 0, "GetStorageIDs" to 1, "GetStorageIDs" to 2),
            tids,
        )
        camera.disconnect()
        assertEquals(OperationCode.CLOSE_SESSION, fake.requests.last().code)
    }

    @Test
    fun `lists files newest first and skips folders`() = test {
        val fake = fake()
        val camera = PtpCamera.connect(config(fake))
        val files = camera.files().toList()
        assertEquals(
            listOf("DSC_0005.MOV", "DSC_0004.JPG", "DSC_0003.NEF", "DSC_0003.JPG", "DSC_0002.NEF", "DSC_0002.JPG", "DSC_0001.NEF", "DSC_0001.JPG"),
            files.map { it.filename },
        )
        assertEquals(1_500_000, files.first { it.filename == "DSC_0001.NEF" }.size)
        val storage = camera.getStorageInfo(camera.getAvailableStorageIds().single())
        assertEquals("NIKON D5500", storage.volumeLabel)
        camera.disconnect()
    }

    @Test
    fun `walks folders when the camera only lists top-level objects`() = test {
        val fake = fake(FakeCamera.Options(anyParentReturnsRootOnly = true))
        val camera = PtpCamera.connect(config(fake))
        val names = camera.files().toList().map { it.filename }
        assertEquals(8, names.size)
        assertEquals("DSC_0005.MOV", names.first())
        camera.disconnect()
    }

    @Test
    fun `fetches thumbnails and reports missing ones`() = test {
        val fake = fake()
        val camera = PtpCamera.connect(config(fake))
        val thumb = camera.getThumb(0x100)
        assertEquals(0xFF.toByte(), thumb[0])
        assertEquals(0xD8.toByte(), thumb[1])
        val error = assertFailsWith<PtpResponseException> { camera.getThumb(1) } // a folder
        assertEquals(ResponseCode.NO_THUMBNAIL_PRESENT, error.responseCode)
        assertNull(camera.getLargeThumb(0x100)) // not advertised by this camera
        assertTrue(camera.isOpen, "a failed operation must not close the connection")
        camera.disconnect()
    }

    @Test
    fun `invalid handle raises a response error`() = test {
        val camera = PtpCamera.connect(config(fake()))
        val error = assertFailsWith<PtpResponseException> { camera.getObjectInfo(0x9999) }
        assertEquals(ResponseCode.INVALID_OBJECT_HANDLE, error.responseCode)
        camera.disconnect()
    }

    @Test
    fun `downloads in chunks and matches the original bytes`() = test {
        val fake = fake()
        val camera = PtpCamera.connect(config(fake))
        val nef = camera.files().toList().first { it.filename == "DSC_0001.NEF" }
        val out = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        camera.download(nef.handle, nef.size, out, chunkSize = 256 * 1024) { done, _ -> progress += done }
        assertContentEquals(FakeCamera.bytes(1_500_000, 1001), out.toByteArray())
        assertEquals(nef.size, progress.last())
        assertEquals(6, fake.requests.count { it.code == OperationCode.GET_PARTIAL_OBJECT })
        camera.disconnect()
    }

    @Test
    fun `falls back to GetObject when partial reads are unsupported`() = test {
        val fake = fake(FakeCamera.Options(supportsPartialObject = false))
        val camera = PtpCamera.connect(config(fake))
        val jpg = camera.files().toList().first { it.filename == "DSC_0002.JPG" }
        val out = ByteArrayOutputStream()
        camera.download(jpg.handle, jpg.size, out, startOffset = 1000)
        assertContentEquals(FakeCamera.bytes(300_000, 2).copyOfRange(1000, 300_000), out.toByteArray())
        camera.disconnect()
    }

    @Test
    fun `resumes a download after the connection drops midway`() = test {
        val fake = fake()
        val expected = FakeCamera.bytes(1_500_000, 1001)
        val saved = ByteArrayOutputStream()
        fake.dropConnectionAfterObjectBytes(700_000)

        val first = PtpCamera.connect(config(fake))
        val nef = first.files().toList().first { it.filename == "DSC_0001.NEF" }
        assertFailsWith<PtpConnectionLostException> {
            first.download(nef.handle, nef.size, saved, chunkSize = 256 * 1024)
        }
        assertFalse(first.isOpen)
        assertTrue(first.connection.awaitClose() is PtpConnectionLostException)
        // Bytes from the interrupted chunk were streamed to the sink too, so the saved size is the resume point.
        val resumeFrom = saved.size().toLong()
        assertTrue(resumeFrom in 1 until nef.size)

        val second = connectWithRetry(fake)
        second.download(nef.handle, nef.size, saved, startOffset = resumeFrom, chunkSize = 256 * 1024)
        assertContentEquals(expected, saved.toByteArray())
        second.disconnect()
    }

    @Test
    fun `camera accepts only one client at a time`() = test {
        val fake = fake()
        val first = PtpCamera.connect(config(fake))
        val error = assertFailsWith<PtpInitFailedException> { PtpCamera.connect(config(fake)) }
        assertEquals(FakeCamera.REASON_BUSY, error.reason)
        first.disconnect()
        connectWithRetry(fake).disconnect()
    }

    @Test
    fun `detects the camera disappearing`() = test {
        val fake = fake()
        val camera = PtpCamera.connect(config(fake))
        fake.disconnectClients()
        assertTrue(camera.connection.awaitClose() is PtpConnectionLostException)
        assertFailsWith<PtpConnectionLostException> { camera.getStorageIds() }
    }

    @Test
    fun `delivers camera events and answers probes`() = test {
        val fake = fake()
        val camera = PtpCamera.connect(config(fake))
        assertTrue(camera.probe())
        val event = async { camera.events.first() }
        delay(100) // let the collector subscribe
        fake.addObject(
            FakeObject(0x200, 2, "DSC_0100.JPG", ObjectFormat.EXIF_JPEG, FakeCamera.bytes(1000, 100), null, "20260925T120000"),
        )
        assertEquals(PtpEvent(EventCode.OBJECT_ADDED, listOf(0x200)), event.await())
        assertEquals("DSC_0100.JPG", camera.getObjectInfo(0x200).filename)
        camera.disconnect()
    }

    @Test
    fun `probe reports false when the camera ignores it`() = test {
        val camera = PtpCamera.connect(config(fake(FakeCamera.Options(respondToProbes = false))))
        assertFalse(camera.probe(timeoutMillis = 300))
        assertTrue(camera.isOpen)
        camera.disconnect()
    }

    @Test
    fun `concurrent callers are serialized onto one command channel`() = test {
        val fake = fake(FakeCamera.Options(dataPacketSize = 1024))
        val camera = PtpCamera.connect(config(fake))
        val files = camera.files().toList()
        val thumbs = files.map { f -> async { f.handle to camera.getThumb(f.handle) } }.awaitAll()
        assertEquals(files.size, thumbs.size)
        thumbs.forEach { (handle, thumb) -> assertTrue(thumb.size > 4, "thumb for $handle") }
        val tids = fake.requests.drop(2).map { it.transactionId }
        assertEquals(tids.sorted(), tids)
        assertEquals(tids.toSet().size, tids.size)
        camera.disconnect()
    }

    @Test
    fun `reports a clear error when nothing is listening`() = test {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val error = assertFailsWith<PtpConnectionLostException> {
            PtpCamera.connect(PtpIpConfig(host = "127.0.0.1", port = port, guid = ByteArray(16), connectTimeoutMillis = 1000))
        }
        assertTrue(error.message!!.contains("Couldn't connect"))
    }

    private suspend fun connectWithRetry(fake: FakeCamera): PtpCamera {
        repeat(20) {
            try {
                return PtpCamera.connect(config(fake))
            } catch (_: PtpInitFailedException) {
                delay(50) // the camera hasn't noticed the old connection is gone yet
            }
        }
        return PtpCamera.connect(config(fake))
    }
}
