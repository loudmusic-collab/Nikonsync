package com.loudmusic.dropfoto

import android.app.Application
import com.loudmusic.dropfoto.connection.CameraController
import com.loudmusic.dropfoto.connection.CameraNetworkProvider
import com.loudmusic.dropfoto.connection.CameraWifiProvider
import com.loudmusic.dropfoto.connection.ConnectionMode
import com.loudmusic.dropfoto.connection.CurrentWifiProvider
import com.loudmusic.dropfoto.connection.SettingsStore
import com.loudmusic.dropfoto.ptpip.PtpIpConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** App-wide objects. The connection outlives activities; the foreground service keeps the process alive. */
class DropFotoApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings by lazy { SettingsStore(this) }
    val controller by lazy { CameraController(appScope) }

    /** Starts connecting with the saved settings. Called by [connection.CameraService]. */
    fun connectWithSavedSettings() {
        val s = settings.load()
        val provider: CameraNetworkProvider = when (s.mode) {
            ConnectionMode.CAMERA_WIFI -> CameraWifiProvider(this, s.ssid, s.passphrase)
            ConnectionMode.CURRENT_WIFI -> CurrentWifiProvider(this)
        }
        controller.connect(provider) { socketFactory ->
            PtpIpConfig(
                host = s.host,
                port = s.port,
                guid = settings.guid,
                friendlyName = settings.friendlyName,
                socketFactory = socketFactory,
            )
        }
    }
}
