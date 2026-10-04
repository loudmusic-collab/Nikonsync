package com.loudmusic.dropfoto.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.loudmusic.dropfoto.DropFotoApp
import com.loudmusic.dropfoto.connection.ConnectionSettings
import com.loudmusic.dropfoto.ptpip.CameraFile
import com.loudmusic.dropfoto.ptpip.PtpException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch

/** What "List files" found on the card. */
data class CardSummary(
    val files: Int,
    val totalBytes: Long,
    val rawJpegPairs: Int,
    val newest: List<CameraFile>,
    val seconds: Double,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as DropFotoApp
    private val controller = app.controller

    val state = controller.state
    val log = controller.log
    val camera = controller.camera

    private val _settings = MutableStateFlow(app.settings.load())
    val settings: StateFlow<ConnectionSettings> = _settings.asStateFlow()

    private val _card = MutableStateFlow<CardSummary?>(null)
    val card: StateFlow<CardSummary?> = _card.asStateFlow()

    private val _listing = MutableStateFlow(false)
    val listing: StateFlow<Boolean> = _listing.asStateFlow()

    fun updateSettings(settings: ConnectionSettings) {
        _settings.value = settings
        app.settings.save(settings)
    }

    fun disconnect() = controller.disconnect()

    fun listFiles() {
        val camera = camera.value ?: return
        if (_listing.value) return
        _listing.value = true
        viewModelScope.launch {
            val started = System.nanoTime()
            try {
                controller.log("Listing files…")
                val files = camera.files().toList()
                val seconds = (System.nanoTime() - started) / 1e9
                _card.value = CardSummary(
                    files = files.size,
                    totalBytes = files.sumOf { it.size },
                    rawJpegPairs = countRawJpegPairs(files),
                    newest = files.take(10),
                    seconds = seconds,
                )
                controller.log("Listed ${files.size} files in ${"%.1f".format(seconds)} s")
            } catch (e: PtpException) {
                controller.log("Listing failed: ${e.message}")
            } finally {
                _listing.value = false
            }
        }
    }

    private fun countRawJpegPairs(files: List<CameraFile>): Int =
        files.groupBy { it.info.parent to it.filename.substringBeforeLast('.').uppercase() }
            .count { (_, group) -> group.any { it.info.extension == "NEF" } && group.any { it.info.extension == "JPG" } }
}
