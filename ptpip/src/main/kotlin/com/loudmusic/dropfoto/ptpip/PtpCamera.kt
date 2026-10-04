package com.loudmusic.dropfoto.ptpip

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.OutputStream

/**
 * Timing of one download request, to see where time goes:
 * [queuedMillis] waiting behind other requests from this app, [firstByteMillis] the camera taking
 * to start sending, [transferMillis] the data actually flowing.
 */
public data class ChunkStats(
    val bytes: Long,
    val queuedMillis: Long,
    val firstByteMillis: Long,
    val transferMillis: Long,
)

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

    /**
     * Largest partial read the camera has accepted so far on this connection. Some cameras refuse
     * big GetPartialObject requests (the D5500 answers StoreNotAvailable), so [download] lowers this
     * when a request is refused and never grows past it again.
     */
    public var partialReadCap: Int = MAX_CHUNK_SIZE
        private set

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
     * Reads names, sizes, formats, parents and dates for every object on the card in one MTP
     * GetObjectPropList request. Returns null if the camera doesn't advertise it or rejects the request,
     * so callers can fall back to one GetObjectInfo per file. Folders are included.
     */
    public suspend fun getAllObjectInfosInOneRequest(): Map<Int, ObjectInfo>? {
        if (!deviceInfo.supports(OperationCode.MTP_GET_OBJECT_PROP_LIST)) return null
        val sink = ByteArraySink()
        val result = connection.transaction(
            OperationCode.MTP_GET_OBJECT_PROP_LIST,
            // all objects, any format, all properties, no group, any depth
            listOf(-1, 0, -1, 0, -1),
            dataIn = sink,
        )
        if (!result.isOk) return null
        return try {
            ObjectPropList.toObjectInfos(ObjectPropList.parse(sink.toByteArray()))
        } catch (_: PtpProtocolException) {
            null
        }
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
    public suspend fun getPartialObject(handle: Int, offset: Long, maxBytes: Int, sink: DataSink): Long =
        getPartialObjectTimed(handle, offset, maxBytes, sink).bytes

    private suspend fun getPartialObjectTimed(handle: Int, offset: Long, maxBytes: Int, sink: DataSink): ChunkStats {
        val started = System.nanoTime()
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
        val firstData = if (result.firstDataNanos > 0) result.firstDataNanos else result.doneNanos
        return ChunkStats(
            bytes = counting.count,
            queuedMillis = (result.sentNanos - started).coerceAtLeast(0) / 1_000_000,
            firstByteMillis = (firstData - result.sentNanos).coerceAtLeast(0) / 1_000_000,
            transferMillis = (result.doneNanos - firstData).coerceAtLeast(0) / 1_000_000,
        )
    }

    /**
     * Downloads an object into [out], starting at [startOffset] (the number of bytes already saved
     * from an earlier, interrupted attempt). Uses GetPartialObject in pieces of at least [chunkSize]
     * when supported, so other requests such as thumbnails can run between chunks. With [adaptive],
     * pieces double (up to [MAX_CHUNK_SIZE]) while the camera's start-up time per request is a large
     * share of each request, since that time is wasted. [onChunk] reports each request's timing.
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
        adaptive: Boolean = true,
        onChunk: (ChunkStats) -> Unit = {},
        onProgress: (bytesDone: Long, total: Long) -> Unit = { _, _ -> },
    ): Long {
        require(startOffset in 0..size) { "startOffset $startOffset outside 0..$size" }
        val sink = OutputStreamSink(out)
        if (deviceInfo.supports(OperationCode.GET_PARTIAL_OBJECT)) {
            // Position comes from what was actually written, so a request that fails after sending
            // some data can neither duplicate nor skip bytes.
            fun offset() = startOffset + sink.bytesWritten
            var piece = minOf(chunkSize, partialReadCap)
            var refusals = 0
            onProgress(offset(), size)
            while (offset() < size) {
                val at = offset()
                val want = minOf(piece.toLong(), size - at).toInt()
                val stats = try {
                    getPartialObjectTimed(handle, at, want, sink)
                } catch (e: PtpResponseException) {
                    if (++refusals > MAX_REFUSALS || e.responseCode !in RETRYABLE_RESPONSES) throw e
                    if (e.responseCode != ResponseCode.DEVICE_BUSY && want > MIN_CHUNK_SIZE) {
                        // Probably too big a request for this camera: halve it and remember the limit.
                        partialReadCap = maxOf(MIN_CHUNK_SIZE, want / 2)
                        piece = partialReadCap
                    }
                    delay(RETRY_DELAY_MILLIS * refusals)
                    continue
                }
                if (stats.bytes <= 0) throw PtpException("Camera returned no data at offset $at of $size")
                refusals = 0
                onChunk(stats)
                onProgress(offset(), size)
                val busy = stats.firstByteMillis + stats.transferMillis
                if (adaptive && busy > 0 && stats.firstByteMillis * 4 > busy) {
                    piece = minOf(piece * 2, partialReadCap)
                }
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
        public const val MAX_CHUNK_SIZE: Int = 8 shl 20
        public const val MIN_CHUNK_SIZE: Int = 64 * 1024
        private const val MAX_REFUSALS = 6
        private const val RETRY_DELAY_MILLIS = 300L

        /** Answers to a partial read that are worth retrying, possibly with a smaller piece. */
        private val RETRYABLE_RESPONSES = setOf(
            ResponseCode.STORE_NOT_AVAILABLE,
            ResponseCode.DEVICE_BUSY,
            ResponseCode.GENERAL_ERROR,
            ResponseCode.INVALID_PARAMETER,
            ResponseCode.PARAMETER_NOT_SUPPORTED,
            ResponseCode.INCOMPLETE_TRANSFER,
        )
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
