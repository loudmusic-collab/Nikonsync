package com.loudmusic.nikonsync.ptpip

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.OutputStream

/** A file (not a folder) on the camera's card. */
public data class CameraFile(val handle: Int, val info: ObjectInfo) {
    val filename: String get() = info.filename
    val size: Long get() = info.compressedSize
}

/**
 * High-level camera API on top of a [PtpIpConnection] with an open PTP session.
 * All methods are safe to call from any coroutine; transactions are serialized underneath.
 */
public class PtpCamera private constructor(
    public val connection: PtpIpConnection,
    public val deviceInfo: DeviceInfo,
) : Closeable {
    public val events: SharedFlow<PtpEvent> get() = connection.events
    public val isOpen: Boolean get() = connection.isOpen
    public val isNikon: Boolean get() = deviceInfo.vendorExtensionId == DeviceInfo.VENDOR_NIKON ||
        deviceInfo.manufacturer.contains("Nikon", ignoreCase = true)

    public suspend fun getStorageIds(): List<Int> =
        PtpReader(dataIn(OperationCode.GET_STORAGE_IDS)).u32Array()

    /** Storage IDs that have a card inserted (low 16 bits non-zero). */
    public suspend fun getAvailableStorageIds(): List<Int> = getStorageIds().filter { it and 0xFFFF != 0 }

    public suspend fun getStorageInfo(storageId: Int): StorageInfo =
        StorageInfo.parse(dataIn(OperationCode.GET_STORAGE_INFO, storageId))

    public suspend fun getObjectHandles(
        storageId: Int = ObjectQuery.ALL_STORAGES,
        format: Int = ObjectQuery.ANY_FORMAT,
        parent: Int = ObjectQuery.ANY_PARENT,
    ): List<Int> = PtpReader(dataIn(OperationCode.GET_OBJECT_HANDLES, storageId, format, parent)).u32Array()

    public suspend fun getObjectInfo(handle: Int): ObjectInfo =
        ObjectInfo.parse(dataIn(OperationCode.GET_OBJECT_INFO, handle))

    /** The small (about 160×120) JPEG thumbnail the camera stores for each image. */
    public suspend fun getThumb(handle: Int): ByteArray = dataIn(OperationCode.GET_THUMB, handle)

    /** A larger preview, if the camera supports Nikon's GetLargeThumb. Returns null otherwise. */
    public suspend fun getLargeThumb(handle: Int): ByteArray? {
        if (!deviceInfo.supports(OperationCode.NIKON_GET_LARGE_THUMB)) return null
        return dataIn(OperationCode.NIKON_GET_LARGE_THUMB, handle)
    }

    /**
     * Lists every file on every inserted card, newest handle first, emitting each as soon as its
     * ObjectInfo arrives so the UI can fill in progressively. Folders are skipped.
     */
    public fun files(): Flow<CameraFile> = flow {
        for (storageId in getAvailableStorageIds()) {
            val handles = getObjectHandles(storageId)
            val folders = mutableListOf<Int>()
            var emittedAny = false
            for (handle in handles.asReversed()) {
                val info = getObjectInfo(handle)
                if (info.isFolder) {
                    folders += handle
                } else {
                    emit(CameraFile(handle, info))
                    emittedAny = true
                }
            }
            // Some cameras only return top-level objects for "any parent"; walk the folders instead.
            if (!emittedAny) {
                val queue = ArrayDeque(folders)
                val seen = folders.toMutableSet()
                while (queue.isNotEmpty()) {
                    val folder = queue.removeFirst()
                    for (handle in getObjectHandles(storageId, parent = folder).asReversed()) {
                        if (!seen.add(handle)) continue
                        val info = getObjectInfo(handle)
                        if (info.isFolder) queue += handle else emit(CameraFile(handle, info))
                    }
                }
            }
        }
    }

    /**
     * Reads up to [maxBytes] of an object starting at [offset] into [sink].
     * Returns the number of bytes actually received.
     */
    public suspend fun getPartialObject(handle: Int, offset: Long, maxBytes: Int, sink: DataSink): Long {
        val counting = CountingSink(sink)
        val result = if (offset <= 0xFFFFFFFFL) {
            connection.transaction(
                OperationCode.GET_PARTIAL_OBJECT,
                listOf(handle, offset.toInt(), maxBytes),
                dataIn = counting,
            )
        } else if (deviceInfo.supports(OperationCode.NIKON_GET_PARTIAL_OBJECT_EX)) {
            connection.transaction(
                OperationCode.NIKON_GET_PARTIAL_OBJECT_EX,
                listOf(handle, offset.toInt(), (offset ushr 32).toInt(), maxBytes, 0),
                dataIn = counting,
            )
        } else {
            throw PtpException("Offset $offset is beyond 4 GB and the camera has no 64-bit partial read")
        }
        result.requireOk(OperationCode.GET_PARTIAL_OBJECT)
        return counting.count
    }

    /**
     * Downloads an object into [out], starting at [startOffset] (the number of bytes already saved
     * from an earlier, interrupted attempt). Uses GetPartialObject in [chunkSize] pieces when
     * supported, so other requests such as thumbnails can run between chunks.
     *
     * Returns the object's total size. Throws [PtpConnectionLostException] if the connection
     * drops. Everything written so far stays valid, so reconnect and call again with the new offset.
     */
    public suspend fun download(
        handle: Int,
        size: Long,
        out: OutputStream,
        startOffset: Long = 0,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        onProgress: (bytesDone: Long, total: Long) -> Unit = { _, _ -> },
    ): Long {
        require(startOffset in 0..size) { "startOffset $startOffset outside 0..$size" }
        val sink = OutputStreamSink(out)
        if (deviceInfo.supports(OperationCode.GET_PARTIAL_OBJECT)) {
            var offset = startOffset
            onProgress(offset, size)
            while (offset < size) {
                val want = minOf(chunkSize.toLong(), size - offset).toInt()
                val got = getPartialObject(handle, offset, want, sink)
                if (got <= 0) throw PtpException("Camera returned no data at offset $offset of $size")
                offset += got
                onProgress(offset, size)
            }
            out.flush()
            return size
        }
        // Fallback: whole-object transfer. Resuming means re-receiving and skipping the saved prefix.
        val skipping = SkippingSink(sink, skip = startOffset) { done -> onProgress(done, size) }
        connection.transaction(OperationCode.GET_OBJECT, listOf(handle), dataIn = skipping)
            .requireOk(OperationCode.GET_OBJECT)
        out.flush()
        return size
    }

    /** Checks the camera is still there without touching the command channel. */
    public suspend fun probe(timeoutMillis: Long = 3_000): Boolean = connection.probe(timeoutMillis)

    /** Ends the PTP session (best effort, bounded wait) and closes both sockets. */
    public suspend fun disconnect(timeoutMillis: Long = 2_000) {
        if (connection.isOpen) {
            withTimeoutOrNull(timeoutMillis) {
                runCatching { connection.transaction(OperationCode.CLOSE_SESSION) }
            }
        }
        connection.close()
    }

    /** Closes the sockets without ending the session. Use [disconnect] where you can suspend. */
    override fun close() {
        connection.close()
    }

    private suspend fun dataIn(code: Int, vararg params: Int): ByteArray {
        val sink = ByteArraySink()
        connection.transaction(code, params.toList(), dataIn = sink).requireOk(code)
        return sink.toByteArray()
    }

    private class CountingSink(private val inner: DataSink) : DataSink {
        var count = 0L
        override fun onStart(totalLength: Long) = inner.onStart(totalLength)
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            inner.write(buffer, offset, length)
            count += length
        }
    }

    private class SkippingSink(
        private val inner: DataSink,
        private var skip: Long,
        private val onProgress: (Long) -> Unit,
    ) : DataSink {
        private var position = 0L
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            val drop = minOf(skip, length.toLong()).toInt()
            skip -= drop
            if (length > drop) inner.write(buffer, offset + drop, length - drop)
            position += length
            onProgress(position)
        }
    }

    public companion object {
        public const val DEFAULT_CHUNK_SIZE: Int = 1 shl 20
        public const val DEFAULT_SESSION_ID: Int = 1

        /**
         * Connects, reads DeviceInfo and opens a PTP session. If the camera says a session is already
         * open (for example, left over from a connection that dropped), it carries on with that session.
         */
        public suspend fun connect(config: PtpIpConfig, sessionId: Int = DEFAULT_SESSION_ID): PtpCamera {
            val connection = PtpIpConnection.connect(config)
            try {
                val info = ByteArraySink()
                connection.transaction(OperationCode.GET_DEVICE_INFO, dataIn = info)
                    .requireOk(OperationCode.GET_DEVICE_INFO)
                val deviceInfo = DeviceInfo.parse(info.toByteArray())
                val open = connection.transaction(OperationCode.OPEN_SESSION, listOf(sessionId))
                if (open.responseCode != ResponseCode.SESSION_ALREADY_OPEN) {
                    open.requireOk(OperationCode.OPEN_SESSION)
                }
                return PtpCamera(connection, deviceInfo)
            } catch (e: Throwable) {
                connection.close()
                throw e
            }
        }
    }
}

internal fun OperationResult.requireOk(operationCode: Int) {
    if (!isOk) throw PtpResponseException(operationCode, responseCode)
}
