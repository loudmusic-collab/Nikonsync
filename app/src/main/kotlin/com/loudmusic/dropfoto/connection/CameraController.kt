package com.loudmusic.dropfoto.connection

import com.loudmusic.dropfoto.ptpip.PtpCamera
import com.loudmusic.dropfoto.ptpip.PtpException
import com.loudmusic.dropfoto.ptpip.PtpInitFailedException
import com.loudmusic.dropfoto.ptpip.PtpIpConfig
import com.loudmusic.dropfoto.ptpip.PtpResponseException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime
import javax.net.SocketFactory

data class LogLine(val time: LocalTime, val text: String)

/**
 * Owns the camera connection: joins the camera's network, opens the PTP/IP session, checks the
 * link every few seconds, and reconnects with backoff when it drops.
 *
 * Pure Kotlin (no Android APIs), so it's unit-tested against the simulated camera.
 */
class CameraController(
    private val scope: CoroutineScope,
    private val timing: Timing = Timing(),
) {
    data class Timing(
        val keepAliveMillis: Long = 10_000,
        val probeTimeoutMillis: Long = 3_000,
        val backoffMillis: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 15_000),
        /** Consecutive failed attempts before giving up and reporting [ConnectionState.Failed]. */
        val maxAttempts: Int = 8,
        /**
         * After the camera's Wi-Fi disappears, how long to keep trying to rejoin it. Long enough to ride
         * out a glitch; short enough that switching the camera's Wi-Fi off lets the phone go back to its
         * normal Wi-Fi instead of being held searching for the camera.
         */
        val rejoinWindowMillis: Long = 30_000,
    )

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _camera = MutableStateFlow<PtpCamera?>(null)

    /** The live camera while connected, for listing files and downloading. */
    val camera: StateFlow<PtpCamera?> = _camera.asStateFlow()

    private val _log = MutableStateFlow<List<LogLine>>(emptyList())
    val log: StateFlow<List<LogLine>> = _log.asStateFlow()

    private val lock = Any()
    private var job: Job? = null

    /**
     * Starts connecting, unless a connection is already active.
     * @param config builds the PTP/IP settings for sockets from the given factory
     */
    fun connect(provider: CameraNetworkProvider, config: (SocketFactory) -> PtpIpConfig) {
        synchronized(lock) {
            if (job?.isActive == true) return
            val previous = job
            _state.value = ConnectionState.JoiningNetwork()
            job = scope.launch {
                // Let a previous session finish closing first: the camera accepts one client at a time.
                previous?.join()
                session(provider, config)
            }
        }
    }

    /** Ends the session cleanly (CloseSession, then close sockets and release the network). */
    fun disconnect() {
        synchronized(lock) { job?.cancel() }
    }

    suspend fun awaitStopped() {
        synchronized(lock) { job }?.join()
    }

    fun log(text: String) {
        val line = LogLine(LocalTime.now(), text)
        _log.update { (it + line).takeLast(MAX_LOG_LINES) }
    }

    private class GiveUp(override val message: String, val title: String = "Connection failed") : Exception(message)

    private suspend fun session(provider: CameraNetworkProvider, config: (SocketFactory) -> PtpIpConfig) {
        var outcome: ConnectionState = ConnectionState.Idle
        var failures = 0
        var lostAt: Long? = null // when the camera network went away, while we try to rejoin it
        try {
            while (true) {
                val rejoining = lostAt != null
                _state.value = ConnectionState.JoiningNetwork(rejoining)
                log(if (rejoining) "Rejoining the camera's network" else "Joining the camera's network")
                val network = try {
                    provider.acquire(rejoining)
                } catch (e: NetworkUnavailableException) {
                    val reason = e.message ?: "Couldn't join the camera's Wi-Fi"
                    // On the first join, report straight away (wrong settings, dialog declined).
                    // After a drop, the camera's Wi-Fi may be restarting or switched off for a moment.
                    if (lostAt == null) throw GiveUp(reason)
                    log(reason)
                    if (System.currentTimeMillis() - lostAt > timing.rejoinWindowMillis) {
                        throw GiveUp("Tap Connect when it's back on.", title = "Camera Wi-Fi turned off")
                    }
                    failures++
                    backoff(failures, reason, lost = null, showAttempts = false)
                    continue
                }
                lostAt = null
                failures = 0
                log("Camera network ready")
                try {
                    failures = runOnNetwork(network, config, failures)
                } finally {
                    network.release()
                }
                lostAt = System.currentTimeMillis()
                failures = 1
                backoff(failures, "Camera Wi-Fi network lost", lost = null, showAttempts = false)
            }
        } catch (e: GiveUp) {
            outcome = ConnectionState.Failed(e.message, e.title)
            log("Stopped: ${e.message}")
        } finally {
            _camera.value = null
            _state.value = outcome
            if (outcome is ConnectionState.Idle) log("Disconnected")
        }
    }

    /** Connects and reconnects on one network. Returns the failure count once the network is lost. */
    private suspend fun runOnNetwork(
        network: CameraNetwork,
        config: (SocketFactory) -> PtpIpConfig,
        startFailures: Int,
    ): Int = coroutineScope {
        var failures = startFailures
        val lost = async { network.awaitLost() }
        try {
            while (!lost.isCompleted) {
                _state.value = ConnectionState.Connecting(failures + 1)
                val reason = try {
                    val camera = PtpCamera.connect(config(network.socketFactory))
                    failures = 0
                    supervise(camera, lost)
                } catch (_: PtpInitFailedException) {
                    "Camera is still busy with an earlier connection"
                } catch (e: PtpException) {
                    e.message ?: "Connection failed"
                }
                log(reason)
                if (lost.isCompleted) break
                failures++
                if (failures >= timing.maxAttempts) throw GiveUp("Couldn't reach the camera: $reason")
                backoff(failures, reason, lost)
            }
            failures
        } finally {
            lost.cancel()
        }
    }

    /** Waits before the next attempt; wakes early if the network goes away. */
    private suspend fun backoff(failures: Int, reason: String, lost: Deferred<Unit>?, showAttempts: Boolean = true) {
        val wait = timing.backoffMillis[minOf(failures - 1, timing.backoffMillis.lastIndex)]
        val seconds = (wait + 999) / 1000
        _state.value = ConnectionState.Reconnecting(
            attempt = failures,
            maxAttempts = if (showAttempts) timing.maxAttempts else 0,
            delaySeconds = seconds,
            reason = reason,
        )
        log(if (showAttempts) "Retrying in $seconds s (attempt ${failures + 1} of ${timing.maxAttempts})" else "Retrying in $seconds s")
        withTimeoutOrNull(wait) { lost?.await() ?: awaitCancellation() }
    }

    /**
     * Holds an open camera connection, checking it every [Timing.keepAliveMillis]. Returns why it
     * ended. Always closes the camera before returning, and on cancellation ends the session cleanly.
     */
    private suspend fun supervise(camera: PtpCamera, networkLost: Deferred<Unit>): String {
        val summary = CameraSummary.from(camera.deviceInfo)
        _camera.value = camera
        _state.value = ConnectionState.Connected(summary)
        log("Connected to ${summary.manufacturer} ${summary.model}, firmware ${summary.firmware}")
        try {
            return coroutineScope {
                val events = launch { camera.events.collect { log("Camera event: $it") } }
                val closed = async { camera.connection.awaitClose() }
                var checks = 0
                var useProbe = true
                try {
                    while (true) {
                        val ended = withTimeoutOrNull(timing.keepAliveMillis) {
                            select {
                                closed.onAwait { "Connection to camera lost: ${it?.message ?: "closed"}" }
                                networkLost.onAwait { "Camera Wi-Fi network lost" }
                            }
                        }
                        if (ended != null) return@coroutineScope ended

                        val started = System.nanoTime()
                        // Prefer a probe on the event channel. If the camera ignores probes, fall back
                        // to a cheap command; an error *response* still proves the camera is there.
                        val probed = useProbe && camera.probe(timing.probeTimeoutMillis)
                        if (!probed) {
                            try {
                                camera.getStorageIds()
                                if (useProbe) log("Camera doesn't answer probes; using commands to check the link")
                                useProbe = false
                            } catch (_: PtpResponseException) {
                                // Alive, just unhappy with the request.
                            } catch (e: PtpException) {
                                return@coroutineScope "Camera stopped responding: ${e.message}"
                            }
                        }
                        checks++
                        _state.value = ConnectionState.Connected(summary, checks, (System.nanoTime() - started) / 1_000_000)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    error("unreachable")
                } finally {
                    events.cancel()
                    closed.cancel()
                }
            }
        } finally {
            _camera.value = null
            withContext(NonCancellable) {
                if (camera.isOpen) camera.disconnect() else camera.close()
            }
        }
    }

    companion object {
        const val MAX_LOG_LINES = 300
    }
}
