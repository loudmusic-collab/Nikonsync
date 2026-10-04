package com.loudmusic.dropfoto.gallery

import com.loudmusic.dropfoto.ptpip.EventCode
import com.loudmusic.dropfoto.ptpip.ObjectInfo
import com.loudmusic.dropfoto.ptpip.PtpCamera
import com.loudmusic.dropfoto.ptpip.PtpConnectionLostException
import com.loudmusic.dropfoto.ptpip.PtpException
import com.loudmusic.dropfoto.ptpip.PtpResponseException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface IndexStatus {
    data object Idle : IndexStatus
    data class Reading(val done: Int, val total: Int) : IndexStatus
    data class Ready(val files: Int, val seconds: Double, val fromCache: Int) : IndexStatus
    data class Error(val message: String) : IndexStatus
}

/**
 * Keeps an up-to-date list of the files on the connected camera's card.
 *
 * Reading every file's details takes about 120 ms each on the D5500, so this:
 * 1. shows the cached list for this camera at once,
 * 2. uses MTP GetObjectPropList (one request for the whole card) when the camera offers it,
 * 3. otherwise asks only about files it hasn't seen, newest first, publishing as it goes,
 * 4. then watches for ObjectAdded events so new shots appear while connected.
 */
class CardIndex(
    private val cache: ObjectCache,
    private val log: (String) -> Unit = {},
    private val publishEvery: Int = 12,
) {
    private val _files = MutableStateFlow<List<CardFile>>(emptyList())
    val files: StateFlow<List<CardFile>> = _files.asStateFlow()

    private val _status = MutableStateFlow<IndexStatus>(IndexStatus.Idle)
    val status: StateFlow<IndexStatus> = _status.asStateFlow()

    private val _cameraSerial = MutableStateFlow<String?>(null)
    val cameraSerial: StateFlow<String?> = _cameraSerial.asStateFlow()

    /** Shows the last known card for a camera while not connected. */
    fun showCached(cameraSerial: String) {
        if (_files.value.isNotEmpty()) return
        val cached = cache.load(cameraSerial)
        _cameraSerial.value = cameraSerial
        _files.value = filesFrom(cached)
    }

    /** Finds a file by its stable identity, for re-resolving handles after a reconnect. */
    fun find(key: String): CardFile? = _files.value.firstOrNull { it.key == key }

    /**
     * Reads the card, then keeps watching for new shots until cancelled or the connection drops.
     * Returns normally on cancellation; throws [PtpConnectionLostException] if the link goes.
     */
    suspend fun sync(camera: PtpCamera) = coroutineScope {
        val serial = camera.deviceInfo.serialNumber
        val started = System.nanoTime()
        if (_cameraSerial.value != serial) _files.value = emptyList()
        _cameraSerial.value = serial

        val known = HashMap(cache.load(serial))
        // Watch for new shots from the start, so nothing taken during the listing is missed.
        val added = Channel<Int>(Channel.UNLIMITED)
        val watcher = launch {
            camera.events.collect { event ->
                if (event.code == EventCode.OBJECT_ADDED) event.params.firstOrNull()?.let { added.trySend(it) }
            }
        }

        try {
            val handles = camera.getAvailableStorageIds().flatMap { camera.getObjectHandles(it) }
            val handleSet = handles.toHashSet()
            known.keys.retainAll(handleSet)
            if (!cacheStillValid(camera, known)) {
                log("This card's contents changed; reading it again")
                known.clear()
            }
            val fromCache = known.size
            publish(known)

            var missing = handles.filter { it !in known }
            if (missing.size > BULK_THRESHOLD) {
                val bulk = try {
                    camera.getAllObjectInfosInOneRequest()
                } catch (e: PtpResponseException) {
                    null
                }
                if (bulk != null) {
                    log("Read ${bulk.size} items in one request")
                    bulk.filterKeys { it in handleSet }.forEach { (h, info) -> known[h] = info }
                    missing = handles.filter { it !in known }
                }
            }

            _status.value = IndexStatus.Reading(fromCache, handles.size)
            var sinceSave = 0
            for ((index, handle) in missing.sortedDescending().withIndex()) {
                known[handle] = camera.getObjectInfo(handle)
                if ((index + 1) % publishEvery == 0 || index == missing.lastIndex) {
                    publish(known)
                    _status.value = IndexStatus.Reading(known.size, handles.size)
                }
                if (++sinceSave >= SAVE_EVERY) {
                    cache.save(serial, known)
                    sinceSave = 0
                }
            }
            // Cameras that only list top-level folders for "any parent": walk the tree instead.
            if (known.values.none { !it.isFolder } && known.isNotEmpty()) {
                camera.files().collect { f ->
                    known[f.handle] = f.info
                    if (known.size % publishEvery == 0) publish(known)
                }
            }
            publish(known)
            cache.save(serial, known)
            val seconds = (System.nanoTime() - started) / 1e9
            val count = _files.value.size
            _status.value = IndexStatus.Ready(count, seconds, fromCache)
            log("Card: $count files ($fromCache from memory) in ${"%.1f".format(seconds)} s")

            // Keep the list current while connected.
            for (next in added) {
                if (next in known) continue
                val info = camera.getObjectInfo(next)
                known[next] = info
                publish(known)
                cache.save(serial, known)
                if (!info.isFolder) log("New on the card: ${info.filename}")
            }
        } catch (e: PtpConnectionLostException) {
            cache.save(serial, known)
            throw e
        } catch (e: PtpException) {
            cache.save(serial, known)
            _status.value = IndexStatus.Error(e.message ?: "Couldn't read the card")
            log("Reading the card failed: ${e.message}")
        } finally {
            watcher.cancel()
        }
    }

    /**
     * Handles can be reused after a card is swapped or reformatted. Spot-check a few cached entries
     * (newest, oldest, middle) against the camera; any mismatch invalidates the cache.
     */
    private suspend fun cacheStillValid(camera: PtpCamera, known: Map<Int, ObjectInfo>): Boolean {
        val files = known.filterValues { !it.isFolder }.keys.sorted()
        if (files.isEmpty()) return true
        val samples = listOf(files.last(), files.first(), files[files.size / 2]).distinct()
        return samples.all { handle ->
            val cached = known.getValue(handle)
            val live = try {
                camera.getObjectInfo(handle)
            } catch (_: PtpResponseException) {
                return@all false
            }
            live.filename == cached.filename && live.compressedSize == cached.compressedSize
        }
    }

    private fun publish(known: Map<Int, ObjectInfo>) {
        _files.value = filesFrom(known)
    }

    private fun filesFrom(objects: Map<Int, ObjectInfo>): List<CardFile> =
        objects.filterValues { !it.isFolder }.map { (h, info) -> CardFile.from(h, info) }

    private companion object {
        const val BULK_THRESHOLD = 20
        const val SAVE_EVERY = 50
    }
}
