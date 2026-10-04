package com.loudmusic.dropfoto.download

import com.loudmusic.dropfoto.gallery.CardFile
import com.loudmusic.dropfoto.gallery.DownloadChoice
import com.loudmusic.dropfoto.gallery.FileKind
import com.loudmusic.dropfoto.gallery.Shot
import com.loudmusic.dropfoto.ptpip.ChunkStats
import com.loudmusic.dropfoto.ptpip.PtpCamera
import com.loudmusic.dropfoto.ptpip.PtpConnectionLostException
import com.loudmusic.dropfoto.ptpip.PtpException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Where downloaded files end up. On Android: MediaStore (Pictures/DropFoto). */
interface PhotoStore {
    /** [CardFile.key]s of files already saved on the phone. */
    val saved: StateFlow<Set<String>>

    /** App-private file holding a partly downloaded copy; survives reconnects so downloads resume. */
    fun partialFile(file: CardFile): File

    /** Moves a finished download into the phone's photo library. */
    fun publish(file: CardFile, completed: File)
}

data class DownloadProgress(
    val running: Boolean = false,
    /** Files in this batch: done + failed + still to go. */
    val total: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val current: CardFile? = null,
    val currentBytes: Long = 0,
    val batchBytesDone: Long = 0,
    val batchBytesTotal: Long = 0,
    val bytesPerSecond: Double = 0.0,
    val waitingForCamera: Boolean = false,
    val lastError: String? = null,
    /** Where the time went for the last finished file, e.g. for diagnosing slow transfers. */
    val lastTiming: String? = null,
) {
    val remaining: Int get() = total - done - failed
}

/**
 * Downloads selected files one at a time (PTP allows one transfer at a time), in 1 MB chunks so
 * thumbnails can load in between. JPEGs go first, then movies, then RAW files, so quick-to-share
 * photos arrive while the big files keep going.
 *
 * If the connection drops, the partial file is kept, and the download resumes from the same byte
 * once [camera] reports a new connection. Handles are re-resolved through [resolve] because they can
 * change between sessions.
 */
class DownloadQueue(
    private val scope: CoroutineScope,
    private val store: PhotoStore,
    private val camera: StateFlow<PtpCamera?>,
    private val resolve: (key: String) -> CardFile?,
    private val log: (String) -> Unit = {},
    private val chunkSize: Int = PtpCamera.DEFAULT_CHUNK_SIZE,
) {
    private val lock = Any()
    private val pending = ArrayList<CardFile>()
    private var worker: Job? = null

    /** Bytes of each file already counted in this batch's progress (so resumes aren't double-counted). */
    private val credited = HashMap<String, Long>()

    private val _progress = MutableStateFlow(DownloadProgress())
    val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()

    /** Keys of files waiting or in progress, for gallery badges. */
    private val _queuedKeys = MutableStateFlow<Set<String>>(emptySet())
    val queuedKeys: StateFlow<Set<String>> = _queuedKeys.asStateFlow()

    /** Adds the chosen files of [shots]. Returns how many files were actually added. */
    fun enqueue(shots: List<Shot>, choice: DownloadChoice): Int = synchronized(lock) {
        val saved = store.saved.value
        val already = pending.mapTo(HashSet()) { it.key }
        val files = shots.flatMap { it.filesFor(choice) }
            .filter { it.key !in saved && already.add(it.key) }
        if (files.isEmpty()) return 0

        val batchContinues = worker?.isActive == true
        if (!batchContinues) credited.clear()
        pending += files
        // Stable sort keeps newest-first order within each kind.
        pending.sortWith(compareBy { rank(it.kind) })
        _queuedKeys.value = pending.mapTo(HashSet()) { it.key }
        _progress.update { p ->
            val base = if (batchContinues) p else DownloadProgress()
            base.copy(
                running = true,
                total = base.total + files.size,
                batchBytesTotal = base.batchBytesTotal + files.sumOf { it.size },
                lastError = if (batchContinues) p.lastError else null,
            )
        }
        log("Queued ${files.size} files (${choice.label})")
        if (!batchContinues) worker = scope.launch { run() }
        files.size
    }

    /** Stops after the current chunk and clears the queue. Partial files stay, so a later download resumes. */
    fun cancel() {
        synchronized(lock) {
            pending.clear()
            _queuedKeys.value = emptySet()
            worker?.cancel()
            worker = null
        }
        _progress.update { it.copy(running = false, current = null, waitingForCamera = false) }
        log("Downloads cancelled")
    }

    private fun rank(kind: FileKind) = when (kind) {
        FileKind.JPEG -> 0
        FileKind.VIDEO, FileKind.OTHER -> 1
        FileKind.RAW -> 2
    }

    private suspend fun run() {
        var failedCamera: PtpCamera? = null
        while (true) {
            val next = synchronized(lock) { pending.firstOrNull() } ?: break
            try {
                downloadOne(next, avoid = failedCamera)
                failedCamera = null
                finish(next, error = null)
            } catch (e: PtpConnectionLostException) {
                // Keep the file at the head of the queue; wait for the controller to reconnect.
                failedCamera = camera.value
                log("Download paused: ${e.message}")
            } catch (e: NotOnCardException) {
                finish(next, error = e.message)
            } catch (e: PtpException) {
                finish(next, error = "${next.filename}: ${e.message}")
            } catch (e: IOException) {
                finish(next, error = "${next.filename}: couldn't save (${e.message})")
            }
        }
        _progress.update { it.copy(running = false, current = null, waitingForCamera = false) }
        val p = _progress.value
        log("Downloads finished: ${p.done} saved" + if (p.failed > 0) ", ${p.failed} failed" else "")
    }

    private fun finish(file: CardFile, error: String?) {
        synchronized(lock) {
            pending.remove(file)
            _queuedKeys.value = pending.mapTo(HashSet()) { it.key }
        }
        if (error != null) log("Download failed: $error")
        _progress.update {
            if (error == null) {
                it.copy(done = it.done + 1, current = null, currentBytes = 0)
            } else {
                it.copy(failed = it.failed + 1, current = null, currentBytes = 0, lastError = error,
                    batchBytesTotal = it.batchBytesTotal - file.size)
            }
        }
    }

    private class NotOnCardException(message: String) : Exception(message)

    private suspend fun downloadOne(queued: CardFile, avoid: PtpCamera?) {
        if (queued.key in store.saved.value) return
        _progress.update { it.copy(current = queued, currentBytes = 0, waitingForCamera = camera.value == null || camera.value === avoid) }
        val cam = camera.first { it != null && it !== avoid && it.isOpen }!!
        val file = resolve(queued.key) ?: throw NotOnCardException("${queued.filename} is no longer on the card")
        _progress.update { it.copy(current = file, waitingForCamera = false) }

        withContext(Dispatchers.IO) {
            val part = store.partialFile(file)
            part.parentFile?.mkdirs()
            if (part.length() > file.size) part.delete()
            val resumeFrom = part.length()
            if (resumeFrom > 0) log("Resuming ${file.filename} at ${resumeFrom / 1024} KB")
            // Bytes saved by an earlier attempt count towards the batch once.
            val earlier = synchronized(lock) { credited[file.key] ?: 0L }
            val credit = (resumeFrom - earlier).coerceAtLeast(0)
            synchronized(lock) { credited[file.key] = resumeFrom }
            _progress.update { it.copy(currentBytes = resumeFrom, batchBytesDone = it.batchBytesDone + credit) }
            var lastBytes = resumeFrom
            var lastTime = System.nanoTime()
            val chunks = mutableListOf<ChunkStats>()
            val started = System.nanoTime()
            FileOutputStream(part, true).use { out ->
                cam.download(
                    file.handle, file.size, out,
                    startOffset = resumeFrom,
                    chunkSize = chunkSize,
                    onChunk = { chunks += it },
                ) { bytes, _ ->
                    val now = System.nanoTime()
                    val delta = bytes - lastBytes
                    val seconds = (now - lastTime) / 1e9
                    _progress.update { p ->
                        val rate = if (seconds > 0 && delta > 0) delta / seconds else p.bytesPerSecond
                        p.copy(
                            currentBytes = bytes,
                            batchBytesDone = p.batchBytesDone + delta,
                            bytesPerSecond = if (p.bytesPerSecond == 0.0) rate else p.bytesPerSecond * 0.7 + rate * 0.3,
                        )
                    }
                    lastBytes = bytes
                    lastTime = now
                    synchronized(lock) { credited[file.key] = bytes }
                }
            }
            val timing = describeTiming(file.size - resumeFrom, (System.nanoTime() - started) / 1_000_000, chunks)
            store.publish(file, part)
            log("Saved ${file.filename}: $timing")
            _progress.update { it.copy(lastTiming = "${file.filename}: $timing") }
        }
    }
}

/**
 * "5.9 MB in 19.6 s (0.30 MB/s) · data flowing 1.9 MB/s · camera start-up 4.1 s · queued 12.3 s · 6 requests"
 * Separates the camera's raw speed from per-request overhead and from waiting behind other requests.
 */
internal fun describeTiming(bytes: Long, totalMillis: Long, chunks: List<ChunkStats>): String {
    fun mb(b: Long) = "%.1f MB".format(b / 1e6)
    fun s(ms: Long) = "%.1f s".format(ms / 1000.0)
    fun rate(b: Long, ms: Long) = if (ms > 0) "%.2f MB/s".format(b / 1e6 / (ms / 1000.0)) else "?"
    val transfer = chunks.sumOf { it.transferMillis }
    val startup = chunks.sumOf { it.firstByteMillis }
    val queued = chunks.sumOf { it.queuedMillis }
    return "${mb(bytes)} in ${s(totalMillis)} (${rate(bytes, totalMillis)}) · data flowing ${rate(bytes, transfer)}" +
        " · camera start-up ${s(startup)} · queued ${s(queued)} · ${chunks.size} requests"
}
