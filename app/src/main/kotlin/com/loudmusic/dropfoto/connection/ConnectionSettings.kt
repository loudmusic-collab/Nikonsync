package com.loudmusic.dropfoto.connection

import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.core.content.edit
import com.loudmusic.dropfoto.ptpip.PtpIpConfig
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

/** Persists connection settings and this install's stable PTP/IP identity. */
class SettingsStore(context: Context) {
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

    val friendlyName: String get() = "DropFoto (${Build.MODEL})".take(40)

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_SSID = "ssid"
        const val KEY_PASSPHRASE = "passphrase"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_GUID = "guid"
    }
}
