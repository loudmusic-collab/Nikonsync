package com.loudmusic.dropfoto.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
 * Android shows a one-time "connect to this device?" dialog, joins the network without making it
 * the phone's default, and keeps mobile data for everything else. That avoids the classic failure
 * where the phone notices the camera network has no internet and switches away from it.
 *
 * @param ssid exact network name, or blank to match any `Nikon_WU2_…` network
 * @param passphrase WPA2 password, or blank for an open network
 */
class CameraWifiProvider(
    private val context: Context,
    private val ssid: String,
    private val passphrase: String,
    private val timeoutMillis: Int = 60_000,
) : CameraNetworkProvider {
    override suspend fun acquire(): CameraNetwork {
        val specifier = WifiNetworkSpecifier.Builder().apply {
            if (ssid.isBlank()) {
                setSsidPattern(PatternMatcher(NIKON_SSID_PREFIX, PatternMatcher.PATTERN_PREFIX))
            } else {
                setSsid(ssid.trim())
            }
            if (passphrase.isNotEmpty()) setWpa2Passphrase(passphrase)
        }.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val what = if (ssid.isBlank()) "a Nikon camera network" else "\"${ssid.trim()}\""
        return requestNetwork(
            context, request, timeoutMillis,
            "Couldn't join $what. Check the camera's Wi-Fi is on and nearby, and that you allowed the connection.",
        )
    }

    companion object {
        const val NIKON_SSID_PREFIX = "Nikon_WU2_"
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
    override suspend fun acquire(): CameraNetwork {
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
