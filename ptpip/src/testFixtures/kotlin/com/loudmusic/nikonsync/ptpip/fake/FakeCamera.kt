package com.loudmusic.nikonsync.ptpip.fake

import com.loudmusic.nikonsync.ptpip.DeviceInfo
import com.loudmusic.nikonsync.ptpip.EventCode
import com.loudmusic.nikonsync.ptpip.ObjectFormat
import com.loudmusic.nikonsync.ptpip.ObjectInfo
import com.loudmusic.nikonsync.ptpip.ObjectQuery
import com.loudmusic.nikonsync.ptpip.OperationCode
import com.loudmusic.nikonsync.ptpip.PacketStream
import com.loudmusic.nikonsync.ptpip.PtpIpPacket
import com.loudmusic.nikonsync.ptpip.PtpWriter
import com.loudmusic.nikonsync.ptpip.ResponseCode
import com.loudmusic.nikonsync.ptpip.StorageInfo
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** One object on the fake card: a folder (format ASSOCIATION) or a file. */
public class FakeObject(
    public val handle: Int,
    public val parent: Int,
    public val filename: String,
    public val format: Int,
    public val data: ByteArray = ByteArray(0),
    public val thumb: ByteArray? = null,
    public val captureDate: String = "",
    public val storageId: Int = FakeCamera.STORAGE_ID,
) {
    public val isFolder: Boolean get() = format == ObjectFormat.ASSOCIATION

    public fun objectInfo(): ObjectInfo = ObjectInfo(
        storageId = storageId,
        format = format,
        protectionStatus = 0,
        compressedSize = data.size.toLong(),
        thumbFormat = if (thumb != null) ObjectFormat.EXIF_JPEG else 0,
        thumbCompressedSize = (thumb?.size ?: 0).toLong(),
        thumbWidth = if (thumb != null) 160 else 0,
        thumbHeight = if (thumb != null) 120 else 0,
        imageWidth = if (isFolder) 0 else 6000,
        imageHeight = if (isFolder) 0 else 4000,
        imageBitDepth = if (isFolder) 0 else 24,
        parent = parent,
        associationType = if (isFolder) 1 else 0,
        associationDesc = 0,
        sequenceNumber = 0,
        filename = filename,
        captureDate = captureDate,
        modificationDate = captureDate,
        keywords = "",
    )
}

/**
 * An in-process PTP/IP camera that behaves like a Nikon D5500 closely enough to test the client:
 * two-socket handshake, one client at a time, sessions, listing, thumbnails, whole and partial
 * object transfers, events and probes. It also offers fault injection for testing reconnects.
 */
public class FakeCamera(
    objects: List<FakeObject> = sampleCard(),
    public val options: Options = Options(),
    port: Int = 0,
    bindAddress: InetAddress = InetAddress.getLoopbackAddress(),
) : Closeable {
    public data class Options(
        val model: String = "D5500",
        val serialNumber: String = "FAKE0000001",
        val supportsPartialObject: Boolean = true,
        val supportsLargeThumb: Boolean = false,
        /** Data phases are split into Data packets of at most this many bytes. */
        val dataPacketSize: Int = 32 * 1024,
        val respondToProbes: Boolean = true,
        /** After a client drops without closing its session, refuse new clients for this long (ms). */
        val busyAfterDropMillis: Long = 0,
        /** Emulates cameras whose "any parent" handle query returns only top-level objects. */
        val anyParentReturnsRootOnly: Boolean = false,
    )

    /** A request the camera received, for test assertions. */
    public data class Request(val code: Int, val transactionId: Int, val params: List<Int>)

    private val server = ServerSocket(port, 50, bindAddress)
    private val objects = CopyOnWriteArrayList(objects)
    private val nextConnectionNumber = AtomicInteger(1)
    private val clients = CopyOnWriteArrayList<Client>()
    private val connectionAttemptCount = AtomicInteger(0)

    @Volatile private var busyUntil = 0L
    @Volatile private var dropAfterObjectBytes: Long? = null

    public val port: Int get() = server.localPort
    public val host: String get() = server.inetAddress.hostAddress
    public val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())
    public val connectionAttempts: Int get() = connectionAttemptCount.get()
    public val connectedClients: Int get() = clients.size

    public val deviceInfo: DeviceInfo = DeviceInfo(
        standardVersion = 100,
        vendorExtensionId = DeviceInfo.VENDOR_NIKON,
        vendorExtensionVersion = 100,
        vendorExtensionDesc = "microsoft.com: 1.0",
        functionalMode = 0,
        operationsSupported = buildList {
            addAll(
                listOf(
                    OperationCode.GET_DEVICE_INFO, OperationCode.OPEN_SESSION, OperationCode.CLOSE_SESSION,
                    OperationCode.GET_STORAGE_IDS, OperationCode.GET_STORAGE_INFO, OperationCode.GET_OBJECT_HANDLES,
                    OperationCode.GET_OBJECT_INFO, OperationCode.GET_OBJECT, OperationCode.GET_THUMB,
                ),
            )
            if (options.supportsPartialObject) add(OperationCode.GET_PARTIAL_OBJECT)
            if (options.supportsLargeThumb) add(OperationCode.NIKON_GET_LARGE_THUMB)
        },
        eventsSupported = listOf(EventCode.OBJECT_ADDED, EventCode.STORAGE_INFO_CHANGED),
        devicePropertiesSupported = emptyList(),
        captureFormats = listOf(ObjectFormat.EXIF_JPEG),
        imageFormats = listOf(ObjectFormat.EXIF_JPEG, ObjectFormat.UNDEFINED, ObjectFormat.QUICKTIME),
        manufacturer = "Nikon Corporation",
        model = options.model,
        deviceVersion = "V1.02",
        serialNumber = options.serialNumber,
    )

    public val cameraGuid: ByteArray = ByteArray(16) { (0xA0 + it).toByte() }

    public fun start(): FakeCamera = apply {
        thread(name = "fake-camera-accept", isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                thread(name = "fake-camera-conn", isDaemon = true) { handleSocket(socket) }
            }
        }
    }

    /**
     * Makes the camera drop every connection once it has sent [bytes] of object data (GetObject /
     * GetPartialObject payload), mid-packet. One-shot: the counter resets after it fires.
     */
    public fun dropConnectionAfterObjectBytes(bytes: Long) {
        dropAfterObjectBytes = bytes
    }

    /** Abruptly closes all client sockets, like the camera's Wi-Fi turning off. */
    public fun disconnectClients() {
        clients.forEach { it.abort() }
    }

    /** Pushes an event to every connected client. */
    public fun emitEvent(code: Int, vararg params: Int) {
        val packet = PtpIpPacket.Event(code, 0, params.toList())
        clients.forEach { c -> runCatching { c.eventStream?.write(packet) } }
    }

    /** Simulates taking a new photo: adds the object and sends ObjectAdded. */
    public fun addObject(obj: FakeObject) {
        objects += obj
        emitEvent(EventCode.OBJECT_ADDED, obj.handle)
    }

    override fun close() {
        runCatching { server.close() }
        clients.forEach { it.abort() }
    }

    private inner class Client(val number: Int, val socket: Socket, val stream: PacketStream) {
        @Volatile var eventSocket: Socket? = null
        @Volatile var eventStream: PacketStream? = null
        var sessionOpen = false
        var objectBytesSent = 0L

        fun abort() {
            runCatching { socket.close() }
            runCatching { eventSocket?.close() }
        }
    }

    private fun streamFor(socket: Socket, name: String) = PacketStream(
        name,
        BufferedInputStream(socket.getInputStream()),
        BufferedOutputStream(socket.getOutputStream()),
    )

    private fun handleSocket(socket: Socket) {
        socket.tcpNoDelay = true
        val stream = streamFor(socket, "fake")
        try {
            when (val first = stream.readPacket()) {
                is PtpIpPacket.InitCommandRequest -> handleCommandChannel(socket, stream)
                is PtpIpPacket.InitEventRequest -> handleEventChannel(socket, stream, first.connectionNumber)
                else -> socket.close()
            }
        } catch (_: IOException) {
            runCatching { socket.close() }
        }
    }

    private fun handleCommandChannel(socket: Socket, stream: PacketStream) {
        connectionAttemptCount.incrementAndGet()
        if (clients.isNotEmpty() || System.currentTimeMillis() < busyUntil) {
            stream.write(PtpIpPacket.InitFail(REASON_BUSY))
            socket.close()
            return
        }
        val client = Client(nextConnectionNumber.getAndIncrement(), socket, stream)
        clients += client
        try {
            stream.write(PtpIpPacket.InitCommandAck(client.number, cameraGuid, options.model))
            while (true) {
                val packet = stream.readPacket()
                if (packet is PtpIpPacket.ProbeRequest) {
                    stream.write(PtpIpPacket.ProbeResponse)
                    continue
                }
                val request = packet as? PtpIpPacket.OperationRequest ?: continue
                requests += Request(request.code, request.transactionId, request.params)
                if (!handleOperation(client, request)) return
            }
        } catch (_: IOException) {
            // Client went away.
        } finally {
            if (client.sessionOpen && options.busyAfterDropMillis > 0) {
                busyUntil = System.currentTimeMillis() + options.busyAfterDropMillis
            }
            clients -= client
            client.abort()
        }
    }

    private fun handleEventChannel(socket: Socket, stream: PacketStream, connectionNumber: Int) {
        val client = clients.firstOrNull { it.number == connectionNumber }
        if (client == null) {
            stream.write(PtpIpPacket.InitFail(REASON_UNKNOWN_CONNECTION))
            socket.close()
            return
        }
        client.eventSocket = socket
        client.eventStream = stream
        stream.write(PtpIpPacket.InitEventAck)
        try {
            while (true) {
                if (stream.readPacket() is PtpIpPacket.ProbeRequest && options.respondToProbes) {
                    stream.write(PtpIpPacket.ProbeResponse)
                }
            }
        } catch (_: IOException) {
            client.abort()
        }
    }

    /** Returns false if the connection was dropped on purpose. */
    private fun handleOperation(client: Client, req: PtpIpPacket.OperationRequest): Boolean {
        val tid = req.transactionId
        val p = req.params
        fun respond(code: Int, vararg params: Int) =
            client.stream.write(PtpIpPacket.OperationResponse(code, tid, params.toList()))

        if (req.code != OperationCode.GET_DEVICE_INFO && req.code != OperationCode.OPEN_SESSION && !client.sessionOpen) {
            respond(ResponseCode.SESSION_NOT_OPEN)
            return true
        }
        when (req.code) {
            OperationCode.GET_DEVICE_INFO -> {
                sendData(client, tid, deviceInfo.encode())
                respond(ResponseCode.OK)
            }
            OperationCode.OPEN_SESSION -> {
                if (client.sessionOpen) {
                    respond(ResponseCode.SESSION_ALREADY_OPEN)
                } else {
                    client.sessionOpen = true
                    respond(ResponseCode.OK)
                }
            }
            OperationCode.CLOSE_SESSION -> {
                client.sessionOpen = false
                respond(ResponseCode.OK)
            }
            OperationCode.GET_STORAGE_IDS -> {
                sendData(client, tid, PtpWriter().u32Array(listOf(STORAGE_ID)).toByteArray())
                respond(ResponseCode.OK)
            }
            OperationCode.GET_STORAGE_INFO -> {
                if (p.getOrNull(0) != STORAGE_ID) return true.also { respond(ResponseCode.INVALID_STORAGE_ID) }
                val used = objects.sumOf { it.data.size.toLong() }
                val info = StorageInfo(
                    storageType = 4, filesystemType = 2, accessCapability = 0,
                    maxCapacity = 32L shl 30, freeSpaceBytes = (32L shl 30) - used, freeSpaceImages = 999,
                    description = "SD", volumeLabel = "NIKON D5500",
                )
                sendData(client, tid, info.encode())
                respond(ResponseCode.OK)
            }
            OperationCode.GET_OBJECT_HANDLES -> {
                val storage = p.getOrElse(0) { ObjectQuery.ALL_STORAGES }
                val format = p.getOrElse(1) { 0 }
                val parent = p.getOrElse(2) { 0 }
                val handles = objects.filter { o ->
                    (storage == ObjectQuery.ALL_STORAGES || o.storageId == storage) &&
                        (format == 0 || o.format == format) &&
                        when (parent) {
                            ObjectQuery.ANY_PARENT -> !options.anyParentReturnsRootOnly || o.parent == 0
                            ObjectQuery.ROOT_ONLY -> o.parent == 0
                            else -> o.parent == parent
                        }
                }.map { it.handle }
                sendData(client, tid, PtpWriter().u32Array(handles).toByteArray())
                respond(ResponseCode.OK)
            }
            OperationCode.GET_OBJECT_INFO -> {
                val obj = find(p) ?: return true.also { respond(ResponseCode.INVALID_OBJECT_HANDLE) }
                sendData(client, tid, obj.objectInfo().encode())
                respond(ResponseCode.OK)
            }
            OperationCode.GET_THUMB, OperationCode.NIKON_GET_LARGE_THUMB -> {
                if (req.code == OperationCode.NIKON_GET_LARGE_THUMB && !options.supportsLargeThumb) {
                    return true.also { respond(ResponseCode.OPERATION_NOT_SUPPORTED) }
                }
                val obj = find(p) ?: return true.also { respond(ResponseCode.INVALID_OBJECT_HANDLE) }
                val thumb = obj.thumb ?: return true.also { respond(ResponseCode.NO_THUMBNAIL_PRESENT) }
                sendData(client, tid, thumb)
                respond(ResponseCode.OK)
            }
            OperationCode.GET_OBJECT -> {
                val obj = find(p) ?: return true.also { respond(ResponseCode.INVALID_OBJECT_HANDLE) }
                if (!sendData(client, tid, obj.data, countsAsObjectData = true)) return false
                respond(ResponseCode.OK)
            }
            OperationCode.GET_PARTIAL_OBJECT -> {
                if (!options.supportsPartialObject) return true.also { respond(ResponseCode.OPERATION_NOT_SUPPORTED) }
                val obj = find(p) ?: return true.also { respond(ResponseCode.INVALID_OBJECT_HANDLE) }
                val offset = p.getOrElse(1) { 0 }.toLong() and 0xFFFFFFFFL
                val max = p.getOrElse(2) { 0 }.toLong() and 0xFFFFFFFFL
                if (offset > obj.data.size) return true.also { respond(ResponseCode.INVALID_PARAMETER) }
                val end = minOf(obj.data.size.toLong(), offset + max).toInt()
                val slice = obj.data.copyOfRange(offset.toInt(), end)
                if (!sendData(client, tid, slice, countsAsObjectData = true)) return false
                respond(ResponseCode.OK, slice.size)
            }
            else -> respond(ResponseCode.OPERATION_NOT_SUPPORTED)
        }
        return true
    }

    private fun find(params: List<Int>): FakeObject? = objects.firstOrNull { it.handle == params.getOrNull(0) }

    /** Sends a data phase. Returns false if fault injection dropped the connection partway. */
    private fun sendData(client: Client, tid: Int, data: ByteArray, countsAsObjectData: Boolean = false): Boolean {
        val stream = client.stream
        stream.write(PtpIpPacket.StartData(tid, data.size.toLong()))
        var offset = 0
        do {
            val n = minOf(options.dataPacketSize, data.size - offset)
            val piece = data.copyOfRange(offset, offset + n)
            val last = offset + n >= data.size
            val limit = dropAfterObjectBytes
            if (countsAsObjectData && limit != null && client.objectBytesSent + n > limit) {
                // Send part of the packet, then vanish.
                val partial = (limit - client.objectBytesSent).toInt()
                val bytes = PtpIpPacket.Data(tid, piece).encode()
                runCatching {
                    client.socket.getOutputStream().write(bytes, 0, PtpIpPacket.HEADER_SIZE + 4 + partial)
                    client.socket.getOutputStream().flush()
                }
                dropAfterObjectBytes = null
                client.abort()
                return false
            }
            stream.write(if (last) PtpIpPacket.EndData(tid, piece) else PtpIpPacket.Data(tid, piece))
            if (countsAsObjectData) client.objectBytesSent += n
            offset += n
        } while (!last)
        return true
    }

    public companion object {
        public const val STORAGE_ID: Int = 0x00010001
        public const val REASON_BUSY: Int = 0x00000001
        public const val REASON_UNKNOWN_CONNECTION: Int = 0x00000002

        /**
         * A deterministic card: DCIM/100D5500 with [pairs] RAW+JPEG shots, plus one JPEG-only shot
         * and one movie. Sizes are small to keep tests fast but still span several chunks.
         */
        public fun sampleCard(
            pairs: Int = 3,
            jpegSize: Int = 300_000,
            nefSize: Int = 1_500_000,
        ): List<FakeObject> {
            val list = mutableListOf(
                FakeObject(handle = 1, parent = 0, filename = "DCIM", format = ObjectFormat.ASSOCIATION),
                FakeObject(handle = 2, parent = 1, filename = "100D5500", format = ObjectFormat.ASSOCIATION),
            )
            var handle = 0x100
            var number = 1
            fun date(n: Int) = "20260925T10%02d%02d".format(n / 60, n % 60)
            repeat(pairs) {
                val name = "DSC_%04d".format(number)
                list += FakeObject(handle++, 2, "$name.JPG", ObjectFormat.EXIF_JPEG, bytes(jpegSize, number), thumbFor(number), date(number))
                list += FakeObject(handle++, 2, "$name.NEF", ObjectFormat.UNDEFINED, bytes(nefSize, number + 1000), thumbFor(number), date(number))
                number++
            }
            list += FakeObject(handle++, 2, "DSC_%04d.JPG".format(number), ObjectFormat.EXIF_JPEG, bytes(jpegSize, number), thumbFor(number), date(number))
            number++
            list += FakeObject(handle, 2, "DSC_%04d.MOV".format(number), ObjectFormat.QUICKTIME, bytes(200_000, number), thumbFor(number), date(number))
            return list
        }

        /** Deterministic pseudo-random content so downloads can be checked byte for byte. */
        public fun bytes(size: Int, seed: Int): ByteArray {
            val random = java.util.Random(seed.toLong())
            return ByteArray(size).also { random.nextBytes(it) }
        }

        private fun thumbFor(n: Int): ByteArray =
            byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + "thumb-$n".toByteArray() + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }
}
