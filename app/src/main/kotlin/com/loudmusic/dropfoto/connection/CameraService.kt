package com.loudmusic.dropfoto.connection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.loudmusic.dropfoto.DropFotoApp
import com.loudmusic.dropfoto.R
import com.loudmusic.dropfoto.download.DownloadProgress
import com.loudmusic.dropfoto.ui.MainActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service (type connectedDevice) that keeps the process, CPU and Wi-Fi awake while the
 * phone is connected to the camera, so the link survives the screen turning off. It shows the
 * connection state in a notification and stops itself once the connection ends.
 */
class CameraService : LifecycleService() {
    private val app get() = application as DropFotoApp
    private var observing = false
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(app.controller.state.value, app.downloads.progress.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        when (intent?.action) {
            ACTION_DISCONNECT -> app.controller.disconnect()
            else -> app.connectWithSavedSettings()
        }
        if (!observing) {
            observing = true
            lifecycleScope.launch {
                combine(app.controller.state, app.downloads.progress) { state, downloads -> state to downloads }.collect { (state, downloads) ->
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state, downloads))
                    if (state.isActive) {
                        acquireLocks()
                    } else {
                        releaseLocks()
                        ServiceCompat.stopForeground(this@CameraService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    private fun acquireLocks() {
        if (wifiLock == null) {
            val wifi = getSystemService(WifiManager::class.java)
            // Low-latency mode is the supported lock on Android 14+; high-perf still works on 10-13.
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 34) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wifi.createWifiLock(mode, "DropFoto:camera").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wakeLock == null) {
            val power = getSystemService(PowerManager::class.java)
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DropFoto:camera").apply {
                setReferenceCounted(false)
                acquire(MAX_WAKE_LOCK_MILLIS)
            }
        }
    }

    private fun releaseLocks() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_connection),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(state: ConnectionState, downloads: DownloadProgress = DownloadProgress()): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, CameraService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE,
        )
        val downloading = downloads.running && state is ConnectionState.Connected
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (downloading) "Downloading ${downloads.done + 1} of ${downloads.total}" else title(state))
            .setContentText(if (downloading) downloadDetail(downloads) else detail(state))
            .apply {
                if (downloading && downloads.batchBytesTotal > 0) {
                    setProgress(1000, (downloads.batchBytesDone * 1000 / downloads.batchBytesTotal).toInt().coerceIn(0, 1000), false)
                }
            }
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, getString(R.string.action_disconnect), disconnect)
            .build()
    }

    private fun downloadDetail(d: DownloadProgress): String {
        val name = d.current?.filename ?: ""
        val speed = if (d.bytesPerSecond > 0) " · %.1f MB/s".format(d.bytesPerSecond / 1e6) else ""
        return name + speed
    }

    private fun title(state: ConnectionState) = when (state) {
        is ConnectionState.Connected -> "Connected to ${state.camera.model}"
        is ConnectionState.JoiningNetwork -> if (state.rejoining) "Waiting for camera Wi-Fi…" else "Joining camera Wi-Fi…"
        is ConnectionState.Connecting -> "Connecting to camera…"
        is ConnectionState.Reconnecting -> "Reconnecting to camera…"
        is ConnectionState.Failed -> state.title
        ConnectionState.Idle -> "Not connected"
    }

    private fun detail(state: ConnectionState) = when (state) {
        is ConnectionState.Connected -> "Link checked ${state.checks} ${if (state.checks == 1) "time" else "times"}"
        is ConnectionState.JoiningNetwork -> if (state.rejoining) "Trying to reconnect for a few seconds" else ""
        is ConnectionState.Reconnecting -> state.reason
        is ConnectionState.Failed -> state.message
        else -> ""
    }

    companion object {
        private const val CHANNEL_ID = "connection"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_CONNECT = "com.loudmusic.dropfoto.CONNECT"
        private const val ACTION_DISCONNECT = "com.loudmusic.dropfoto.DISCONNECT"
        private const val MAX_WAKE_LOCK_MILLIS = 4 * 60 * 60 * 1000L

        /** Connects with the saved settings. Call from a visible activity (Android 12+ background-start rules). */
        fun connect(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, CameraService::class.java).setAction(ACTION_CONNECT),
            )
        }
    }
}
