package com.loudmusic.dropfoto.connection

import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.core.content.edit
import com.loudmusic.dropfoto.download.RawFormat
import com.loudmusic.dropfoto.gallery.DownloadChoice
import com.loudmusic.dropfoto.ptpip.PtpIpConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

enum class ConnectionMode {
    /** The app joins the camera's Wi-Fi itself. The normal way. */
    CAMERA_WIFI,

    /** Use the Wi-Fi the phone is already on (joined in Settings, or a test setup). */
    CURRENT_WIFI,
}

data class ConnectionSettings(
    val mode: ConnectionMode = ConnectionMode.CAMERA_WIFI,
    /** Camera network name; blank matches any Nikon_WU2_ network. */
    val ssid: String = "",
    val passphrase: String = "",
    val host: String = PtpIpConfig.DEFAULT_HOST,
    val port: Int = PtpIpConfig.DEFAULT_PORT,
)

/** Persists connection settings, the last camera access point, and this install's stable PTP/IP identity. */
class SettingsStore(context: Context) : KnownCameraStore {
    private val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)

    fun load() = ConnectionSettings(
        mode = runCatching { ConnectionMode.valueOf(prefs.getString(KEY_MODE, null)!!) }.getOrDefault(ConnectionMode.CAMERA_WIFI),
        ssid = prefs.getString(KEY_SSID, "")!!,
        passphrase = prefs.getString(KEY_PASSPHRASE, "")!!,
        host = prefs.getString(KEY_HOST, PtpIpConfig.DEFAULT_HOST)!!,
        port = prefs.getInt(KEY_PORT, PtpIpConfig.DEFAULT_PORT),
    )

    fun save(settings: ConnectionSettings) {
        prefs.edit {
            putString(KEY_MODE, settings.mode.name)
            putString(KEY_SSID, settings.ssid)
            putString(KEY_PASSPHRASE, settings.passphrase)
            putString(KEY_HOST, settings.host)
            putInt(KEY_PORT, settings.port)
        }
    }

    /** 16 random bytes generated on first use. The camera sees the same "phone" on every connection. */
    val guid: ByteArray by lazy {
        prefs.getString(KEY_GUID, null)?.let { Base64.decode(it, Base64.NO_WRAP) }?.takeIf { it.size == 16 }
            ?: ByteArray(16).also {
                SecureRandom().nextBytes(it)
                prefs.edit { putString(KEY_GUID, Base64.encodeToString(it, Base64.NO_WRAP)) }
            }
    }

    private val _knownCamera = MutableStateFlow(loadKnownCamera())

    /** The paired (or last seen) camera access point, for the UI. */
    val knownCameraFlow: StateFlow<KnownCamera?> = _knownCamera.asStateFlow()

    override fun knownCamera(): KnownCamera? = _knownCamera.value

    override fun rememberCamera(camera: KnownCamera) {
        prefs.edit {
            putString(KEY_KNOWN_SSID, camera.ssid)
            putString(KEY_KNOWN_BSSID, camera.bssid)
        }
        _knownCamera.value = camera
    }

    fun forgetCamera() {
        prefs.edit {
            remove(KEY_KNOWN_SSID)
            remove(KEY_KNOWN_BSSID)
        }
        _knownCamera.value = null
    }

    private fun loadKnownCamera(): KnownCamera? {
        val ssid = prefs.getString(KEY_KNOWN_SSID, null) ?: return null
        val bssid = prefs.getString(KEY_KNOWN_BSSID, null) ?: return null
        return KnownCamera(ssid, bssid)
    }

    /** Default for the download button: JPEG unless the user picked something else. */
    var downloadChoice: DownloadChoice
        get() = runCatching { DownloadChoice.valueOf(prefs.getString(KEY_DOWNLOAD_CHOICE, null)!!) }.getOrDefault(DownloadChoice.JPEG)
        set(value) = prefs.edit { putString(KEY_DOWNLOAD_CHOICE, value.name) }

    /** How RAW downloads are saved: DNG by default (Snapseed needs DNG). */
    var rawFormat: RawFormat
        get() = runCatching { RawFormat.valueOf(prefs.getString(KEY_RAW_FORMAT, null)!!) }.getOrDefault(RawFormat.DNG)
        set(value) = prefs.edit { putString(KEY_RAW_FORMAT, value.name) }

    /** Serial of the last camera connected, so its card can be shown while offline. */
    var lastCameraSerial: String?
        get() = prefs.getString(KEY_LAST_SERIAL, null)
        set(value) = prefs.edit { putString(KEY_LAST_SERIAL, value) }

    val friendlyName: String get() = "DropFoto (${Build.MODEL})".take(40)

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_SSID = "ssid"
        const val KEY_PASSPHRASE = "passphrase"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_GUID = "guid"
        const val KEY_KNOWN_SSID = "known_ssid"
        const val KEY_DOWNLOAD_CHOICE = "download_choice"
        const val KEY_LAST_SERIAL = "last_serial"
        const val KEY_RAW_FORMAT = "raw_format"
        const val KEY_KNOWN_BSSID = "known_bssid"
    }
}
