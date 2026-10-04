package com.loudmusic.dropfoto.connection

import com.loudmusic.dropfoto.ptpip.OperationCode
import com.loudmusic.dropfoto.ptpip.PtpIpConfig
import com.loudmusic.dropfoto.ptpip.fake.FakeCamera
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CameraControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fakes = mutableListOf<FakeCamera>()

    private val fastTiming = CameraController.Timing(
        keepAliveMillis = 100,
        probeTimeoutMillis = 200,
        backoffMillis = listOf(50, 100),
        maxAttempts = 4,
        rejoinWindowMillis = 1_500,
    )

    /** Stands in for the camera's Wi-Fi: always available until the test says it's lost. */
    private class TestNetwork : CameraNetwork {
        val lost = CompletableDeferred<Unit>()
        var released = false
        override val socketFactory: SocketFactory = SocketFactory.getDefault()
        override suspend fun awaitLost() = lost.await()
        override fun release() {
            released = true
        }
    }

    /** Hands out networks; the first [failReconnects] rejoin attempts fail, like a camera Wi-Fi still booting. */
    private class TestProvider(private var failReconnects: Int = 0) : CameraNetworkProvider {
        val acquired = AtomicInteger()
        val reconnectFlags = mutableListOf<Boolean>()
        val networks = mutableListOf<TestNetwork>()
        override suspend fun acquire(reconnecting: Boolean): CameraNetwork {
            acquired.incrementAndGet()
            synchronized(reconnectFlags) { reconnectFlags += reconnecting }
            if (reconnecting && failReconnects > 0) {
                failReconnects--
                throw NetworkUnavailableException("camera Wi-Fi not back yet")
            }
            return TestNetwork().also { synchronized(networks) { networks += it } }
        }
    }

    private fun fake(options: FakeCamera.Options = FakeCamera.Options()) =
        FakeCamera(FakeCamera.sampleCard(), options).start().also { fakes += it }

    private fun config(fake: FakeCamera): (SocketFactory) -> PtpIpConfig = { factory ->
        PtpIpConfig(host = fake.host, port = fake.port, guid = ByteArray(16) { 3 }, socketFactory = factory, readTimeoutMillis = 2_000)
    }

    @After
    fun tearDown() {
        scope.cancel()
        fakes.forEach { it.close() }
    }

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private suspend fun CameraController.awaitState(predicate: (ConnectionState) -> Boolean) =
        state.first(predicate)

    @Test
    fun `connects and keeps the link checked`() = test {
        val fake = fake()
        val controller = CameraController(scope, fastTiming)
        controller.connect(TestProvider(), config(fake))
        val connected = controller.awaitState { it is ConnectionState.Connected } as ConnectionState.Connected
        assertEquals("D5500", connected.camera.model)
        assertTrue(connected.camera.supportsResume)
        controller.awaitState { it is ConnectionState.Connected && it.checks >= 3 }
        assertTrue(controller.camera.value != null)
    }

    @Test
    fun `reconnects after the camera drops the connection`() = test {
        val fake = fake()
        val controller = CameraController(scope, fastTiming)
        controller.connect(TestProvider(), config(fake))
        controller.awaitState { it is ConnectionState.Connected }

        fake.disconnectClients()
        controller.awaitState { it is ConnectionState.Reconnecting }
        controller.awaitState { it is ConnectionState.Connected }
        assertEquals(2, fake.requests.count { it.code == OperationCode.OPEN_SESSION })
    }

    @Test
    fun `falls back to command checks when the camera ignores probes`() = test {
        val fake = fake(FakeCamera.Options(respondToProbes = false))
        val controller = CameraController(scope, fastTiming)
        controller.connect(TestProvider(), config(fake))
        controller.awaitState { it is ConnectionState.Connected && it.checks >= 3 }
        assertTrue(fake.requests.count { it.code == OperationCode.GET_STORAGE_IDS } >= 3)
        assertTrue(controller.log.value.any { "doesn't answer probes" in it.text })
    }

    @Test
    fun `rejoins the network when the camera Wi-Fi disappears`() = test {
        val fake = fake()
        val provider = TestProvider()
        val controller = CameraController(scope, fastTiming)
        controller.connect(provider, config(fake))
        controller.awaitState { it is ConnectionState.Connected }

        val first = synchronized(provider.networks) { provider.networks.single() }
        first.lost.complete(Unit)
        controller.awaitState { it is ConnectionState.Reconnecting && "network lost" in it.reason }
        controller.awaitState { it is ConnectionState.Connected }
        assertEquals(2, provider.acquired.get())
        assertTrue(first.released)
    }

    @Test
    fun `keeps trying to rejoin while the camera Wi-Fi restarts`() = test {
        val fake = fake()
        val provider = TestProvider(failReconnects = 2)
        val controller = CameraController(scope, fastTiming)
        controller.connect(provider, config(fake))
        controller.awaitState { it is ConnectionState.Connected }

        synchronized(provider.networks) { provider.networks.single() }.lost.complete(Unit)
        controller.awaitState { it is ConnectionState.Reconnecting && it.reason == "camera Wi-Fi not back yet" }
        controller.awaitState { it is ConnectionState.Connected }
        assertEquals(listOf(false, true, true, true), synchronized(provider.reconnectFlags) { provider.reconnectFlags.toList() })
    }

    @Test
    fun `gives up when the camera Wi-Fi stays away past the rejoin window`() = test {
        val fake = fake()
        val provider = TestProvider(failReconnects = Int.MAX_VALUE)
        val controller = CameraController(scope, fastTiming)
        controller.connect(provider, config(fake))
        controller.awaitState { it is ConnectionState.Connected }

        synchronized(provider.networks) { provider.networks.single() }.lost.complete(Unit)
        controller.awaitState { it is ConnectionState.JoiningNetwork && it.rejoining }
        val failed = controller.awaitState { it is ConnectionState.Failed } as ConnectionState.Failed
        assertEquals("Camera Wi-Fi turned off", failed.title)
        assertTrue(provider.acquired.get() > 4, "kept retrying for the whole window, not just maxAttempts")
    }

    @Test
    fun `gives up after repeated failures`() = test {
        val fake = fake()
        val controller = CameraController(scope, fastTiming)
        controller.connect(TestProvider(), config(fake))
        controller.awaitState { it is ConnectionState.Connected }

        fake.close() // the camera is switched off for good
        val failed = controller.awaitState { it is ConnectionState.Failed }
        assertIs<ConnectionState.Failed>(failed)
        assertTrue(failed.message.startsWith("Couldn't reach the camera"), failed.message)
    }

    @Test
    fun `reports when the camera network can't be joined`() = test {
        val controller = CameraController(scope, fastTiming)
        controller.connect({ throw NetworkUnavailableException("No camera network found") }) { error("unused") }
        val failed = controller.awaitState { it is ConnectionState.Failed } as ConnectionState.Failed
        assertEquals("No camera network found", failed.message)
    }

    @Test
    fun `disconnect closes the session and releases the network`() = test {
        val fake = fake()
        val provider = TestProvider()
        val controller = CameraController(scope, fastTiming)
        controller.connect(provider, config(fake))
        controller.awaitState { it is ConnectionState.Connected }

        controller.disconnect()
        controller.awaitStopped()
        assertEquals(ConnectionState.Idle, controller.state.value)
        assertEquals(null, controller.camera.value)
        assertEquals(OperationCode.CLOSE_SESSION, fake.requests.last().code)
        assertTrue(provider.networks.single().released)

        // And it can connect again straight away.
        controller.connect(provider, config(fake))
        controller.awaitState { it is ConnectionState.Connected }
    }
}
