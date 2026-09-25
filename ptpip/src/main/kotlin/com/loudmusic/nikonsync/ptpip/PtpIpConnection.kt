package com.loudmusic.nikonsync.ptpip

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

public class PtpIpConfig(
    public val host: String = DEFAULT_HOST,
    public val port: Int = DEFAULT_PORT,
    /** 16-byte identifier for this phone. Keep it stable across launches. */
    public val guid: ByteArray,
    /** Name the camera may show for this device. */
    public val friendlyName: String = "NikonSync",
    /** On Android, pass `Network.socketFactory` so traffic stays on the camera's Wi-Fi. */
    public val socketFactory: SocketFactory = SocketFactory.getDefault(),
    public val connectTimeoutMillis: Int = 5_000,
    /** How long a single read on the command channel may block before the camera is considered gone. */
    public val readTimeoutMillis: Int = 15_000,
    public val logger: PacketLogger? = null,
) {
    init {
        require(guid.size == PtpIpPacket.GUID_SIZE) { "GUID must be ${PtpIpPacket.GUID_SIZE} bytes" }
    }

    public companion object {
        public const val DEFAULT_HOST: String = "192.168.1.1"
        public const val DEFAULT_PORT: Int = 15740
    }
}

/** An event pushed by the camera on the event channel. */
public data class PtpEvent(val code: Int, val params: List<Int>) {
    override fun toString(): String = "${EventCode.name(code)}(${params.joinToString { hex32(it) }})"
}

/** Result of one PTP transaction. */
public data class OperationResult(val responseCode: Int, val params: List<Int>) {
    val isOk: Boolean get() = responseCode == ResponseCode.OK
}

/**
 * A live PTP/IP connection: one command/data socket and one event socket.
 *
 * PTP allows only one transaction at a time, so [transaction] calls are serialized. Each transaction
 * runs to completion even if the caller is cancelled, because abandoning it midway would leave
 * unread bytes on the socket. Keep data phases short (use GetPartialObject chunks) so cancellation
 * and interleaving stay responsive.
 *
 * Any I/O or protocol error closes the connection; [awaitClose] reports why.
 */
public class PtpIpConnection private constructor(
    private val commandSocket: Socket,
    private val command: PacketStream,
    private val eventSocket: Socket,
    private val eventStream: PacketStream,
    public val connectionNumber: Int,
    /** Friendly name the camera sent in its handshake. */
    public val cameraName: String,
    public val cameraGuid: ByteArray,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandMutex = Mutex()
    private val closed = CompletableDeferred<Throwable?>()
    private val pendingProbe = AtomicReference<CompletableDeferred<Unit>?>(null)

    // Guarded by commandMutex.
    private var sessionOpen = false
    private var nextTransactionId = 1

    private val _events = MutableSharedFlow<PtpEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    public val events: SharedFlow<PtpEvent> = _events.asSharedFlow()

    public val isOpen: Boolean get() = !closed.isCompleted

    init {
        scope.launch { eventLoop() }
    }

    /** Suspends until the connection closes. Returns the error that closed it, or null for a normal close. */
    public suspend fun awaitClose(): Throwable? = closed.await()

    /**
     * Runs one PTP transaction.
     *
     * @param dataOut bytes to send in a data-out phase, if the operation takes any
     * @param dataIn receives a data-in phase; if null, incoming data is discarded
     * @throws PtpConnectionLostException if the connection fails; it is closed afterwards
     */
    public suspend fun transaction(
        code: Int,
        params: List<Int> = emptyList(),
        dataOut: ByteArray? = null,
        dataIn: DataSink? = null,
    ): OperationResult = commandMutex.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            if (!isOpen) throw PtpConnectionLostException("Connection is closed")
            val tid = allocateTransactionId(code)
            try {
                runTransaction(code, tid, params, dataOut, dataIn ?: DiscardSink).also { result ->
                    when {
                        code == OperationCode.OPEN_SESSION &&
                            (result.isOk || result.responseCode == ResponseCode.SESSION_ALREADY_OPEN) ->
                            sessionOpen = true
                        code == OperationCode.CLOSE_SESSION && result.isOk -> sessionOpen = false
                    }
                }
            } catch (e: Throwable) {
                val error = when (e) {
                    is PtpProtocolException -> e
                    is SocketTimeoutException -> PtpConnectionLostException("Camera stopped responding", e)
                    is IOException -> PtpConnectionLostException("Lost connection to camera: ${e.message}", e)
                    else -> e
                }
                fail(error)
                throw error
            }
        }
    }

    /**
     * Sends a PTP/IP Probe Request on the event channel and waits for the reply.
     * Returns false if the camera didn't answer in time. Doesn't close the connection on failure.
     */
    public suspend fun probe(timeoutMillis: Long = 3_000): Boolean {
        if (!isOpen) return false
        val deferred = CompletableDeferred<Unit>()
        pendingProbe.getAndSet(deferred)?.cancel()
        try {
            withContext(Dispatchers.IO) { eventStream.write(PtpIpPacket.ProbeRequest) }
        } catch (e: IOException) {
            fail(PtpConnectionLostException("Lost event channel: ${e.message}", e))
            return false
        }
        return withTimeoutOrNull(timeoutMillis) { deferred.await() } != null
    }

    /** Closes both sockets immediately. Prefer [PtpCamera.close], which ends the session first. */
    override fun close() {
        if (closed.complete(null)) shutdown()
    }

    private fun fail(error: Throwable) {
        if (closed.complete(error)) shutdown()
    }

    private fun shutdown() {
        runCatching { commandSocket.close() }
        runCatching { eventSocket.close() }
        pendingProbe.getAndSet(null)?.cancel()
        scope.cancel()
    }

    private fun allocateTransactionId(code: Int): Int {
        // OpenSession and sessionless operations use transaction ID 0; in-session IDs start at 1.
        if (code == OperationCode.OPEN_SESSION) {
            nextTransactionId = 1
            return 0
        }
        if (!sessionOpen) return 0
        val tid = nextTransactionId
        nextTransactionId = if (tid == -2) 1 else tid + 1 // skip 0xFFFFFFFF and 0
        return tid
    }

    private fun runTransaction(
        code: Int,
        tid: Int,
        params: List<Int>,
        dataOut: ByteArray?,
        sink: DataSink,
    ): OperationResult {
        val phase = if (dataOut != null) DataPhase.OUT else DataPhase.NONE_OR_IN
        command.write(PtpIpPacket.OperationRequest(phase, code, tid, params))
        if (dataOut != null) {
            command.write(PtpIpPacket.StartData(tid, dataOut.size.toLong()))
            command.write(PtpIpPacket.EndData(tid, dataOut))
        }
        while (true) {
            val header = command.readHeader()
            when (header.type) {
                PacketType.START_DATA -> {
                    val start = command.readBody(header) as PtpIpPacket.StartData
                    checkTid(code, tid, start.transactionId)
                    sink.onStart(start.totalLength)
                }
                PacketType.DATA, PacketType.END_DATA -> {
                    checkTid(code, tid, command.streamDataBody(header, sink))
                }
                PacketType.OPERATION_RESPONSE -> {
                    val response = command.readBody(header) as PtpIpPacket.OperationResponse
                    checkTid(code, tid, response.transactionId)
                    return OperationResult(response.code, response.params)
                }
                PacketType.PROBE_REQUEST -> {
                    command.readBody(header)
                    command.write(PtpIpPacket.ProbeResponse)
                }
                PacketType.PROBE_RESPONSE -> command.readBody(header)
                else -> throw PtpProtocolException(
                    "Unexpected ${PacketType.name(header.type)} during ${OperationCode.name(code)}",
                )
            }
        }
    }

    private fun checkTid(code: Int, expected: Int, actual: Int) {
        if (expected != actual) {
            throw PtpProtocolException(
                "${OperationCode.name(code)}: transaction ID mismatch (sent $expected, camera answered $actual)",
            )
        }
    }

    private suspend fun eventLoop() {
        try {
            while (true) {
                val header = eventStream.readHeader()
                when (val packet = eventStream.readBody(header)) {
                    is PtpIpPacket.Event -> _events.emit(PtpEvent(packet.code, packet.params))
                    is PtpIpPacket.ProbeRequest -> eventStream.write(PtpIpPacket.ProbeResponse)
                    is PtpIpPacket.ProbeResponse -> pendingProbe.getAndSet(null)?.complete(Unit)
                    else -> Unit // Nothing else is expected here; ignore rather than drop the link.
                }
            }
        } catch (e: Throwable) {
            if (isOpen) {
                fail(
                    if (e is IOException && e !is PtpException) {
                        PtpConnectionLostException("Lost event channel: ${e.message}", e)
                    } else {
                        e
                    },
                )
            }
        }
    }

    private object DiscardSink : DataSink {
        override fun write(buffer: ByteArray, offset: Int, length: Int) {}
    }

    public companion object {
        /**
         * Opens both PTP/IP channels and completes the handshake. Doesn't open a PTP session;
         * see [PtpCamera.connect] for the full sequence.
         */
        public suspend fun connect(config: PtpIpConfig): PtpIpConnection = withContext(Dispatchers.IO) {
            val sockets = mutableListOf<Socket>()
            try {
                val cmdSocket = openSocket(config, readTimeout = config.readTimeoutMillis).also { sockets += it }
                val cmd = PacketStream(
                    "cmd",
                    BufferedInputStream(cmdSocket.getInputStream()),
                    BufferedOutputStream(cmdSocket.getOutputStream()),
                    config.logger,
                )
                cmd.write(PtpIpPacket.InitCommandRequest(config.guid, config.friendlyName))
                val ack = when (val reply = cmd.readPacket()) {
                    is PtpIpPacket.InitCommandAck -> reply
                    is PtpIpPacket.InitFail -> throw PtpInitFailedException(reply.reason)
                    else -> throw PtpProtocolException("Expected InitCommandAck, got ${PacketType.name(reply.type)}")
                }

                // Events can be minutes apart, so the event socket has no read timeout;
                // liveness comes from probes and from the command channel's timeout.
                val evtSocket = openSocket(config, readTimeout = config.readTimeoutMillis).also { sockets += it }
                val evt = PacketStream(
                    "evt",
                    BufferedInputStream(evtSocket.getInputStream()),
                    BufferedOutputStream(evtSocket.getOutputStream()),
                    config.logger,
                )
                evt.write(PtpIpPacket.InitEventRequest(ack.connectionNumber))
                when (val reply = evt.readPacket()) {
                    is PtpIpPacket.InitEventAck -> Unit
                    is PtpIpPacket.InitFail -> throw PtpInitFailedException(reply.reason)
                    else -> throw PtpProtocolException("Expected InitEventAck, got ${PacketType.name(reply.type)}")
                }
                evtSocket.soTimeout = 0

                PtpIpConnection(cmdSocket, cmd, evtSocket, evt, ack.connectionNumber, ack.friendlyName, ack.guid)
            } catch (e: Throwable) {
                sockets.forEach { runCatching { it.close() } }
                throw when (e) {
                    is PtpException -> e
                    is IOException -> PtpConnectionLostException(
                        "Couldn't connect to camera at ${config.host}:${config.port}: ${e.message}", e,
                    )
                    else -> e
                }
            }
        }

        private fun openSocket(config: PtpIpConfig, readTimeout: Int): Socket =
            config.socketFactory.createSocket().apply {
                tcpNoDelay = true
                keepAlive = true
                soTimeout = readTimeout
                connect(InetSocketAddress(config.host, config.port), config.connectTimeoutMillis)
            }
    }
}
