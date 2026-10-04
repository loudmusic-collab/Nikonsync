package com.loudmusic.dropfoto.connection

import javax.net.SocketFactory

/**
 * A network path to the camera. On Android this wraps a `Network` obtained from
 * ConnectivityManager, so sockets go over the camera's Wi-Fi even though it has no internet.
 */
interface CameraNetwork {
    val socketFactory: SocketFactory

    /** Suspends until the network disappears (camera Wi-Fi off, out of range, phone switched away). */
    suspend fun awaitLost()

    /** Gives the network back to the system. Safe to call more than once. */
    fun release()
}

fun interface CameraNetworkProvider {
    /** Joins or finds the camera's network. Throws [NetworkUnavailableException] if that fails. */
    suspend fun acquire(): CameraNetwork
}

class NetworkUnavailableException(message: String) : Exception(message)
