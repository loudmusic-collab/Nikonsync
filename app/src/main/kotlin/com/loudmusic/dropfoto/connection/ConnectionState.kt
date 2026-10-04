package com.loudmusic.dropfoto.connection

import com.loudmusic.dropfoto.ptpip.DeviceInfo
import com.loudmusic.dropfoto.ptpip.OperationCode

/** Where the connection stands. The UI and the notification both render this. */
sealed interface ConnectionState {
    data object Idle : ConnectionState

    /** @param rejoining true when the camera's Wi-Fi went away mid-session and we're waiting for it */
    data class JoiningNetwork(val rejoining: Boolean = false) : ConnectionState

    data class Connecting(val attempt: Int) : ConnectionState

    data class Connected(
        val camera: CameraSummary,
        /** Number of successful keep-alive checks since this connection opened. */
        val checks: Int = 0,
        /** Round trip of the latest check, in milliseconds. */
        val lastCheckMillis: Long? = null,
    ) : ConnectionState

    data class Reconnecting(val attempt: Int, val maxAttempts: Int, val delaySeconds: Long, val reason: String) :
        ConnectionState

    data class Failed(val message: String) : ConnectionState

    /** True while the controller is working on, or holding, a connection. */
    val isActive: Boolean get() = this !is Idle && this !is Failed
}

data class CameraSummary(
    val manufacturer: String,
    val model: String,
    val firmware: String,
    val serialNumber: String,
    val supportsResume: Boolean,
    val supportsLargePreview: Boolean,
) {
    companion object {
        fun from(info: DeviceInfo) = CameraSummary(
            manufacturer = info.manufacturer,
            model = info.model,
            firmware = info.deviceVersion,
            serialNumber = info.serialNumber,
            supportsResume = info.supports(OperationCode.GET_PARTIAL_OBJECT),
            supportsLargePreview = info.supports(OperationCode.NIKON_GET_LARGE_THUMB),
        )
    }
}
