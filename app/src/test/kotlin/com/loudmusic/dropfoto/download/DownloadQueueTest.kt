package com.loudmusic.dropfoto.download

import com.loudmusic.dropfoto.connection.CameraController
import com.loudmusic.dropfoto.connection.CameraNetwork
import com.loudmusic.dropfoto.connection.ConnectionState
import com.loudmusic.dropfoto.gallery.CardFile
import com.loudmusic.dropfoto.gallery.CardIndex
import com.loudmusic.dropfoto.gallery.DownloadChoice
import com.loudmusic.dropfoto.gallery.IndexStatus
import com.loudmusic.dropfoto.gallery.ObjectCache
import com.loudmusic.dropfoto.gallery.groupShots
import com.loudmusic.dropfoto.ptpip.PtpIpConfig
import com.loudmusic.dropfoto.ptpip.fake.FakeCamera
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import javax.net.SocketFactory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadQueueTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fakes = mutableListOf<FakeCamera>()

    @After
    fun tearDown() {
        scope.cancel()
        fakes.forEach { it.close() }
    }

    /** Saves into a folder and records the order files were published in. */
    private class FolderStore(private val dir: File, private val partialDir: File) : PhotoStore {
        val order: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val _saved = MutableStateFlow<Set<String>>(emptySet())
        override val saved: StateFlow<Set<String>> = _saved
        override fun partialFile(file: CardFile) = File(partialDir, "${file.filename}_${file.size}.part")
        override fun publish(file: CardFile, completed: File) {
            dir.mkdirs()
            check(completed.renameTo(File(dir, file.filename)))
            order += file.filename
            _saved.value = _saved.value + file.key
        }
        fun markSaved(key: String) {
            _saved.value = _saved.value + key
        }
    }

    private class Rig(
        val fake: FakeCamera,
        val controller: CameraController,
        val index: CardIndex,
        val store: FolderStore,
        val queue: DownloadQueue,
    )

    /** Real controller + index + queue against the simulated camera, like the app wires them. */
    private suspend fun rig(pairs: Int = 3): Rig {
        val fake = FakeCamera(FakeCamera.sampleCard(pairs = pairs, jpegSize = 300_000, nefSize = 900_000)).start().also { fakes += it }
        val controller = CameraController(
            scope,
            CameraController.Timing(keepAliveMillis = 200, probeTimeoutMillis = 200, backoffMillis = listOf(50, 100), maxAttempts = 20),
        )
        val index = CardIndex(ObjectCache(tmp.newFolder()))
        scope.launch { controller.camera.filterNotNull().collectLatest { cam -> runCatching { index.sync(cam) } } }
        val store = FolderStore(tmp.newFolder("saved"), tmp.newFolder("partial"))
        val queue = DownloadQueue(scope, store, controller.camera, index::find, chunkSize = 128 * 1024)
        controller.connect({
            object : CameraNetwork {
                override val socketFactory: SocketFactory = SocketFactory.getDefault()
                override suspend fun awaitLost() = CompletableDeferred<Unit>().await()
                override fun release() {}
            }
        }) { PtpIpConfig(host = fake.host, port = fake.port, guid = ByteArray(16) { 9 }, socketFactory = it, readTimeoutMillis = 2_000) }
        controller.state.first { it is ConnectionState.Connected }
        index.status.first { it is IndexStatus.Ready }
        return Rig(fake, controller, index, store, queue)
    }

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    private suspend fun DownloadQueue.awaitIdle() = progress.first { !it.running && it.total > 0 }

    @Test
    fun `JPEG choice downloads only the JPEGs, byte for byte`() = test {
        val rig = rig()
        val shots = groupShots(rig.index.files.value)
        assertEquals(3, rig.queue.enqueue(shots.filter { it.label == "RAW+JPG" }, DownloadChoice.JPEG))
        val done = rig.queue.awaitIdle()
        assertEquals(3, done.done)
        assertEquals(listOf("DSC_0003.JPG", "DSC_0002.JPG", "DSC_0001.JPG"), rig.store.order.toList())
        assertContentEquals(FakeCamera.bytes(300_000, 1), File(tmp.root, "saved/DSC_0001.JPG").readBytes())
    }

    @Test
    fun `RAW plus JPEG downloads every JPEG before any RAW`() = test {
        val rig = rig()
        val pairs = groupShots(rig.index.files.value).filter { it.label == "RAW+JPG" }
        rig.queue.enqueue(pairs, DownloadChoice.BOTH)
        rig.queue.awaitIdle()
        val order = rig.store.order.toList()
        assertEquals(6, order.size)
        assertTrue(order.take(3).all { it.endsWith(".JPG") } && order.drop(3).all { it.endsWith(".NEF") }, order.toString())
        assertContentEquals(FakeCamera.bytes(900_000, 1001), File(tmp.root, "saved/DSC_0001.NEF").readBytes())
    }

    @Test
    fun `resumes after the connection drops mid-file`() = test {
        val rig = rig()
        val nef = groupShots(rig.index.files.value).first { it.label == "RAW+JPG" }
        rig.fake.dropConnectionAfterObjectBytes(400_000)
        rig.queue.enqueue(listOf(nef), DownloadChoice.RAW)
        val done = rig.queue.awaitIdle()
        assertEquals(1, done.done)
        assertEquals(0, done.failed)
        assertContentEquals(FakeCamera.bytes(900_000, 1003), File(tmp.root, "saved/DSC_0003.NEF").readBytes())
        // The link really dropped and came back...
        assertEquals(2, rig.fake.requests.count { it.code == com.loudmusic.dropfoto.ptpip.OperationCode.OPEN_SESSION })
        // ...and the download resumed rather than restarted: about one file's worth of bytes requested.
        val requested = rig.fake.requests.filter { it.code == com.loudmusic.dropfoto.ptpip.OperationCode.GET_PARTIAL_OBJECT }.sumOf { it.params[2].toLong() }
        assertTrue(requested < 1_400_000, "requested $requested bytes")
    }

    @Test
    fun `skips files already on the phone`() = test {
        val rig = rig()
        val shots = groupShots(rig.index.files.value).filter { it.label == "RAW+JPG" }
        rig.store.markSaved(shots.first().jpeg!!.key)
        assertEquals(2, rig.queue.enqueue(shots, DownloadChoice.JPEG))
        assertEquals(0, rig.queue.enqueue(shots, DownloadChoice.JPEG)) // already queued
        rig.queue.awaitIdle()
        assertEquals(2, rig.store.order.size)
    }
}

class DescribeTimingTest {
    @Test
    fun `separates camera speed from overhead and queueing`() {
        val text = describeTiming(
            bytes = 6_000_000,
            totalMillis = 20_000,
            chunks = listOf(
                com.loudmusic.dropfoto.ptpip.ChunkStats(3_000_000, queuedMillis = 5_000, firstByteMillis = 2_000, transferMillis = 1_500),
                com.loudmusic.dropfoto.ptpip.ChunkStats(3_000_000, queuedMillis = 7_000, firstByteMillis = 3_000, transferMillis = 1_500),
            ),
        )
        assertEquals(
            "6.0 MB in 20.0 s (0.30 MB/s) · data flowing 2.00 MB/s · camera start-up 5.0 s · queued 12.0 s · 2 requests",
            text,
        )
    }
}
