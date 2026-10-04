package com.loudmusic.dropfoto

import android.app.Application
import com.loudmusic.dropfoto.connection.CameraController
import com.loudmusic.dropfoto.connection.CameraNetworkProvider
import com.loudmusic.dropfoto.connection.CameraWifiProvider
import com.loudmusic.dropfoto.connection.ConnectionMode
import com.loudmusic.dropfoto.connection.CurrentWifiProvider
import com.loudmusic.dropfoto.connection.SettingsStore
import com.loudmusic.dropfoto.download.DownloadQueue
import com.loudmusic.dropfoto.download.MediaStorePhotoStore
import com.loudmusic.dropfoto.gallery.CardIndex
import com.loudmusic.dropfoto.gallery.ObjectCache
import com.loudmusic.dropfoto.gallery.ThumbnailLoader
import com.loudmusic.dropfoto.ptpip.PtpException
import com.loudmusic.dropfoto.ptpip.PtpIpConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/** App-wide objects. The connection outlives activities; the foreground service keeps the process alive. */
class DropFotoApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings by lazy { SettingsStore(this) }
    val controller by lazy { CameraController(appScope) }
    val index by lazy { CardIndex(ObjectCache(File(filesDir, "cards")), controller::log) }
    val photoStore by lazy { MediaStorePhotoStore(this, controller::log) }
    val downloads by lazy { DownloadQueue(appScope, photoStore, controller.camera, index::find, controller::log) }
    val thumbnails by lazy { ThumbnailLoader(this, controller.camera, index.cameraSerial) }

    override fun onCreate() {
        super.onCreate()
        appScope.launch(Dispatchers.IO) {
            photoStore.refresh()
            settings.lastCameraSerial?.let(index::showCached)
        }
        // Whenever a camera connection opens, read its card and keep the list current.
        appScope.launch {
            controller.camera.collectLatest { camera ->
                if (camera == null) return@collectLatest
                settings.lastCameraSerial = camera.deviceInfo.serialNumber
                try {
                    index.sync(camera)
                } catch (_: PtpException) {
                    // The controller reports and handles connection problems.
                }
            }
        }
    }

    /** Starts connecting with the saved settings. Called by [connection.CameraService]. */
    fun connectWithSavedSettings() {
        val s = settings.load()
        val provider: CameraNetworkProvider = when (s.mode) {
            ConnectionMode.CAMERA_WIFI -> CameraWifiProvider(this, s.ssid, s.passphrase, settings, controller::log)
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
