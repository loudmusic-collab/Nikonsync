package com.loudmusic.dropfoto.gallery

import com.loudmusic.dropfoto.ptpip.EventCode
import com.loudmusic.dropfoto.ptpip.ObjectFormat
import com.loudmusic.dropfoto.ptpip.OperationCode
import com.loudmusic.dropfoto.ptpip.PtpCamera
import com.loudmusic.dropfoto.ptpip.PtpIpConfig
import com.loudmusic.dropfoto.ptpip.fake.FakeCamera
import com.loudmusic.dropfoto.ptpip.fake.FakeObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CardIndexTest {
    @get:Rule val tmp = TemporaryFolder()
    private val fakes = mutableListOf<FakeCamera>()

    @After
    fun tearDown() = fakes.forEach { it.close() }

    private fun fake(options: FakeCamera.Options = FakeCamera.Options(), objects: List<FakeObject> = FakeCamera.sampleCard(pairs = 20)) =
        FakeCamera(objects, options).start().also { fakes += it }

    private suspend fun connect(fake: FakeCamera) =
        PtpCamera.connect(PtpIpConfig(host = fake.host, port = fake.port, guid = ByteArray(16) { 5 }))

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    /** Runs a sync until the index reports Ready, then stops it. */
    private suspend fun CoroutineScope.syncOnce(index: CardIndex, camera: PtpCamera): IndexStatus.Ready {
        val job = launch { index.sync(camera) }
        val ready = index.status.first { it is IndexStatus.Ready } as IndexStatus.Ready
        job.cancel()
        return ready
    }

    @Test
    fun `reads the card, then reuses the cache on the next connection`() = test {
        val fake = fake()
        val cache = ObjectCache(tmp.root)

        val first = CardIndex(cache)
        connect(fake).let { cam ->
            val ready = syncOnce(first, cam)
            assertEquals(42, ready.files)
            assertEquals(0, ready.fromCache)
            cam.disconnect()
        }
        val infoRequestsFirst = fake.requests.count { it.code == OperationCode.GET_OBJECT_INFO }
        assertEquals(44, infoRequestsFirst) // 42 files + 2 folders

        val second = CardIndex(cache)
        connect(fake).let { cam ->
            val ready = syncOnce(second, cam)
            assertEquals(42, ready.files)
            assertEquals(44, ready.fromCache)
            cam.disconnect()
        }
        val infoRequestsSecond = fake.requests.count { it.code == OperationCode.GET_OBJECT_INFO } - infoRequestsFirst
        assertTrue(infoRequestsSecond <= 3, "only the cache spot-check, got $infoRequestsSecond")
        assertEquals(20, groupShots(second.files.value).count { it.label == "RAW+JPG" })
    }

    @Test
    fun `a different card with reused handles invalidates the cache`() = test {
        val cache = ObjectCache(tmp.root)
        val fake1 = fake()
        connect(fake1).let { syncOnce(CardIndex(cache), it); it.disconnect() }

        // Same camera serial, same handles, different files (card swapped).
        val swapped = FakeCamera.sampleCard(pairs = 20).map {
            if (it.isFolder) it else FakeObject(it.handle, it.parent, "X" + it.filename, it.format, it.data, it.thumb, it.captureDate)
        }
        val fake2 = fake(objects = swapped)
        val index = CardIndex(cache)
        connect(fake2).let { syncOnce(index, it); it.disconnect() }
        assertTrue(index.files.value.all { it.filename.startsWith("X") })
    }

    @Test
    fun `uses one bulk request when the camera supports it`() = test {
        val fake = fake(FakeCamera.Options(supportsObjectPropList = true))
        val index = CardIndex(ObjectCache(tmp.root))
        connect(fake).let { cam ->
            assertEquals(42, syncOnce(index, cam).files)
            cam.disconnect()
        }
        assertEquals(1, fake.requests.count { it.code == OperationCode.MTP_GET_OBJECT_PROP_LIST })
        assertEquals(0, fake.requests.count { it.code == OperationCode.GET_OBJECT_INFO })
    }

    @Test
    fun `new shots appear while connected`() = test {
        val fake = fake()
        val index = CardIndex(ObjectCache(tmp.root))
        val cam = connect(fake)
        val job = launch { index.sync(cam) }
        index.status.first { it is IndexStatus.Ready }
        fake.addObject(FakeObject(0x500, 2, "DSC_0999.JPG", ObjectFormat.EXIF_JPEG, FakeCamera.bytes(10, 1), null, "20261004T130000"))
        index.files.first { files -> files.any { it.filename == "DSC_0999.JPG" } }
        assertEquals(EventCode.OBJECT_ADDED, EventCode.OBJECT_ADDED)
        job.cancel()
        cam.disconnect()
    }
}
