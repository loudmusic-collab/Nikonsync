package com.loudmusic.dropfoto.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.PatternMatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.net.SocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Asks Android to join the camera's Wi-Fi just for this app (WifiNetworkSpecifier).
 *
 * Android joins the network without making it the phone's default and keeps mobile data for
 * everything else. That avoids the classic failure where the phone notices the camera network has
 * no internet and switches away from it.
 *
 * Android asks the user to approve a broad request ("any Nikon_WU2_ network") every time. A request
 * for one exact access point (SSID + BSSID) that the user approved before is granted silently. So
 * after the first approval, this remembers the camera's SSID and BSSID from the Wi-Fi scan results
 * and asks for exactly that from then on, which keeps reconnects hands-free, even with the screen off.
 *
 * @param ssid exact network name, or blank to match any `Nikon_WU2_…` network
 * @param passphrase WPA2 password, or blank for an open network
 */
class CameraWifiProvider(
    private val context: Context,
    private val ssid: String,
    private val passphrase: String,
    private val knownCameras: KnownCameraStore,
    private val log: (String) -> Unit = {},
) : CameraNetworkProvider {
    private val wantedSsid = ssid.trim()

    override suspend fun acquire(reconnecting: Boolean): CameraNetwork {
        val known = knownCameras.knownCamera()?.takeIf { wantedSsid.isEmpty() || it.ssid == wantedSsid }
        if (known != null) {
            log("Looking for ${known.ssid} (${known.bssid})")
            try {
                return requestNetwork(
                    context, request(exactSpecifier(known)), EXACT_TIMEOUT_MILLIS,
                    "Couldn't find ${known.ssid}. Check the camera's Wi-Fi is on and nearby.",
                )
            } catch (e: NetworkUnavailableException) {
                // While reconnecting, the camera's Wi-Fi may still be starting: let the controller retry.
                if (reconnecting) throw e
                log("Not found at its last address; searching for any matching camera network")
            }
        }
        val what = if (wantedSsid.isEmpty()) "a Nikon camera network" else "\"$wantedSsid\""
        val network = requestNetwork(
            context, request(broadSpecifier()), BROAD_TIMEOUT_MILLIS,
            "Couldn't join $what. Check the camera's Wi-Fi is on and nearby, and that you allowed the connection.",
        )
        rememberAccessPoint()
        return network
    }

    private fun broadSpecifier() = WifiNetworkSpecifier.Builder().apply {
        if (wantedSsid.isEmpty()) {
            setSsidPattern(PatternMatcher(NIKON_SSID_PREFIX, PatternMatcher.PATTERN_PREFIX))
        } else {
            setSsid(wantedSsid)
        }
        if (passphrase.isNotEmpty()) setWpa2Passphrase(passphrase)
    }.build()

    private fun exactSpecifier(camera: KnownCamera) = WifiNetworkSpecifier.Builder().apply {
        setSsid(camera.ssid)
        setBssid(MacAddress.fromString(camera.bssid))
        if (passphrase.isNotEmpty()) setWpa2Passphrase(passphrase)
    }.build()

    private fun request(specifier: WifiNetworkSpecifier) = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .setNetworkSpecifier(specifier)
        .build()

    /** Finds the access point we just joined in the scan results and saves its SSID + BSSID. */
    private fun rememberAccessPoint() {
        val wifi = context.getSystemService(WifiManager::class.java)
        val results = try {
            wifi.scanResults
        } catch (_: SecurityException) {
            log("Can't read Wi-Fi scan results, so Android will ask to approve the camera network on every connect")
            return
        }
        @Suppress("DEPRECATION")
        val match = results
            .filter { r -> if (wantedSsid.isEmpty()) r.SSID.startsWith(NIKON_SSID_PREFIX) else r.SSID == wantedSsid }
            .maxByOrNull { it.level }
        if (match == null) {
            log("Couldn't find the camera in the Wi-Fi scan results; Android may ask to approve again next time")
            return
        }
        @Suppress("DEPRECATION")
        val camera = KnownCamera(match.SSID, match.BSSID)
        if (camera != knownCameras.knownCamera()) {
            knownCameras.rememberCamera(camera)
            log("Remembered camera network ${camera.ssid} (${camera.bssid}) for hands-free reconnects")
        }
    }

    companion object {
        const val NIKON_SSID_PREFIX = "Nikon_WU2_"
        private const val EXACT_TIMEOUT_MILLIS = 30_000
        private const val BROAD_TIMEOUT_MILLIS = 60_000
    }
}

/**
 * Uses whatever Wi-Fi the phone is already on, for example after joining the camera in Android's
 * Wi-Fi settings, or a home network with the simulated camera running on a laptop.
 * Sockets are still bound to that Wi-Fi network, never to mobile data.
 */
class CurrentWifiProvider(
    private val context: Context,
    private val timeoutMillis: Int = 10_000,
) : CameraNetworkProvider {
    override suspend fun acquire(reconnecting: Boolean): CameraNetwork {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        return requestNetwork(context, request, timeoutMillis, "The phone isn't connected to any Wi-Fi network.")
    }
}

private class RequestedNetwork(
    private val connectivity: ConnectivityManager,
    private val callback: ConnectivityManager.NetworkCallback,
    val network: Network,
    private val lost: CompletableDeferred<Unit>,
) : CameraNetwork {
    override val socketFactory: SocketFactory get() = network.socketFactory

    override suspend fun awaitLost() = lost.await()

    override fun release() {
        // Unregistering also tells Android we no longer need a specifier-requested network.
        runCatching { connectivity.unregisterNetworkCallback(callback) }
    }
}

private suspend fun requestNetwork(
    context: Context,
    request: NetworkRequest,
    timeoutMillis: Int,
    unavailableMessage: String,
): CameraNetwork {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    return suspendCancellableCoroutine { cont ->
        val lost = CompletableDeferred<Unit>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            @Volatile private var network: Network? = null

            override fun onAvailable(network: Network) {
                if (this.network != null) return
                this.network = network
                val result = RequestedNetwork(connectivity, this, network, lost)
                cont.resume(result) { _, value, _ -> value.release() }
            }

            override fun onUnavailable() {
                if (cont.isActive) cont.resumeWithException(NetworkUnavailableException(unavailableMessage))
            }

            override fun onLost(network: Network) {
                if (network == this.network) lost.complete(Unit)
            }
        }
        connectivity.requestNetwork(request, callback, timeoutMillis)
        cont.invokeOnCancellation { runCatching { connectivity.unregisterNetworkCallback(callback) } }
    }
}
