package com.loudmusic.dropfoto.connection

import android.app.Activity
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.companion.WifiDeviceFilter
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.wifi.ScanResult
import android.os.Build
import androidx.core.content.IntentCompat
import java.util.regex.Pattern

/**
 * One-time pairing with the camera through Android's companion-device manager.
 *
 * Android shows its own picker of nearby `Nikon_WU2_…` networks; the user taps the camera once.
 * DropFoto then knows the camera's exact SSID and BSSID without needing Wi-Fi scan results, and
 * Android treats requests for that access point as pre-approved, so reconnects don't ask again.
 */
object CameraPairing {
    private fun request(): AssociationRequest = AssociationRequest.Builder()
        .addDeviceFilter(
            WifiDeviceFilter.Builder()
                .setNamePattern(Pattern.compile("^" + Pattern.quote(CameraWifiProvider.NIKON_SSID_PREFIX) + ".*"))
                .build(),
        )
        .setSingleDevice(false)
        .build()

    /**
     * Starts pairing. [onPending] must launch the given IntentSender for a result and pass that result
     * to [parseResult]. On Android 13+ [onPaired] may also be called directly by the system callback.
     */
    fun start(
        activity: Activity,
        onPending: (IntentSender) -> Unit,
        onPaired: (KnownCamera) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val cdm = activity.getSystemService(CompanionDeviceManager::class.java)
        if (Build.VERSION.SDK_INT >= 33) {
            cdm.associate(
                request(),
                activity.mainExecutor,
                object : CompanionDeviceManager.Callback() {
                    override fun onAssociationPending(intentSender: IntentSender) = onPending(intentSender)
                    override fun onAssociationCreated(associationInfo: AssociationInfo) {
                        fromAssociation(associationInfo)?.let(onPaired)
                    }
                    override fun onFailure(error: CharSequence?) = onFailure(error?.toString() ?: "unknown error")
                },
            )
        } else {
            cdm.associate(
                request(),
                object : CompanionDeviceManager.Callback() {
                    @Deprecated("Replaced by onAssociationPending on Android 13+")
                    override fun onDeviceFound(intentSender: IntentSender) = onPending(intentSender)
                    override fun onFailure(error: CharSequence?) = onFailure(error?.toString() ?: "unknown error")
                },
                null,
            )
        }
    }

    /** Reads the paired camera from the picker's result intent. */
    fun parseResult(data: Intent?): KnownCamera? {
        if (data == null) return null
        if (Build.VERSION.SDK_INT >= 33) {
            IntentCompat.getParcelableExtra(data, CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
                ?.let { fromAssociation(it) }
                ?.let { return it }
        }
        @Suppress("DEPRECATION")
        val scan = IntentCompat.getParcelableExtra(data, CompanionDeviceManager.EXTRA_DEVICE, ScanResult::class.java)
        @Suppress("DEPRECATION")
        return scan?.let { KnownCamera(it.SSID, it.BSSID) }
    }

    /** Removes DropFoto's pairings, so the next pairing starts fresh. */
    fun forget(context: Context) {
        if (Build.VERSION.SDK_INT >= 33) {
            val cdm = context.getSystemService(CompanionDeviceManager::class.java)
            cdm.myAssociations.forEach { runCatching { cdm.disassociate(it.id) } }
        }
    }

    private fun fromAssociation(info: AssociationInfo): KnownCamera? {
        if (Build.VERSION.SDK_INT < 33) return null
        val bssid = info.deviceMacAddress?.toString() ?: return null
        @Suppress("DEPRECATION")
        val ssid = (if (Build.VERSION.SDK_INT >= 34) info.associatedDevice?.wifiDevice?.SSID else null)
            ?: info.displayName?.toString()
            ?: return null
        return KnownCamera(ssid, bssid)
    }
}
