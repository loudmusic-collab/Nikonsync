package com.loudmusic.dropfoto.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.loudmusic.dropfoto.DropFotoApp
import com.loudmusic.dropfoto.connection.CameraPairing
import com.loudmusic.dropfoto.connection.ConnectionSettings
import com.loudmusic.dropfoto.connection.KnownCamera
import com.loudmusic.dropfoto.download.RawFormat
import com.loudmusic.dropfoto.gallery.DownloadChoice
import com.loudmusic.dropfoto.gallery.Shot
import com.loudmusic.dropfoto.gallery.groupShots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class GalleryFilter(val label: String) { ALL("All"), NOT_DOWNLOADED("Not downloaded") }

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as DropFotoApp
    private val controller = app.controller

    val state = controller.state
    val log = controller.log
    val camera = controller.camera
    val indexStatus = app.index.status
    val downloads = app.downloads.progress
    val queuedKeys = app.downloads.queuedKeys
    val savedKeys = app.photoStore.saved
    val thumbnails = app.thumbnails
    val pairedCamera: StateFlow<KnownCamera?> = app.settings.knownCameraFlow

    private val _settings = MutableStateFlow(app.settings.load())
    val settings: StateFlow<ConnectionSettings> = _settings.asStateFlow()

    /** Every shot on the card, newest first. */
    val shots: StateFlow<List<Shot>> = app.index.files
        .map { groupShots(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _filter = MutableStateFlow(GalleryFilter.ALL)
    val filter: StateFlow<GalleryFilter> = _filter.asStateFlow()

    /** Shots shown in the grid after the filter. */
    val visibleShots: StateFlow<List<Shot>> = combine(shots, savedKeys, filter) { shots, saved, filter ->
        when (filter) {
            GalleryFilter.ALL -> shots
            GalleryFilter.NOT_DOWNLOADED -> shots.filter { shot -> shot.files.any { it.key !in saved } }
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Selected shots, by [Shot.id]. */
    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    private val _choice = MutableStateFlow(app.settings.downloadChoice)
    val downloadChoice: StateFlow<DownloadChoice> = _choice.asStateFlow()

    private val _downloadPanelDismissed = MutableStateFlow(false)
    val downloadPanelDismissed: StateFlow<Boolean> = _downloadPanelDismissed.asStateFlow()

    fun setFilter(filter: GalleryFilter) {
        _filter.value = filter
    }

    fun toggle(shot: Shot) = _selection.update { if (shot.id in it) it - shot.id else it + shot.id }

    fun setSelected(shots: List<Shot>, selected: Boolean) = _selection.update { current ->
        val keys = shots.map { it.id }
        if (selected) current + keys else current - keys.toSet()
    }

    fun selectAllVisible() = setSelected(visibleShots.value, true)

    fun clearSelection() {
        _selection.value = emptySet()
    }

    fun selectedShots(): List<Shot> {
        val keys = _selection.value
        return shots.value.filter { it.id in keys }
    }

    private val _rawFormat = MutableStateFlow(app.settings.rawFormat)
    val rawFormat: StateFlow<RawFormat> = _rawFormat.asStateFlow()

    fun setRawFormat(format: RawFormat) {
        _rawFormat.value = format
        app.settings.rawFormat = format
    }

    fun setDownloadChoice(choice: DownloadChoice) {
        _choice.value = choice
        app.settings.downloadChoice = choice
    }

    /** Queues [shots] with [choice]. Returns the number of files added. */
    fun download(shots: List<Shot>, choice: DownloadChoice = _choice.value): Int {
        _downloadPanelDismissed.value = false
        return app.downloads.enqueue(shots, choice)
    }

    fun downloadSelection() {
        val added = download(selectedShots())
        if (added == 0) controller.log("Everything selected is already on the phone")
        clearSelection()
    }

    fun cancelDownloads() = app.downloads.cancel()

    fun dismissDownloadPanel() {
        _downloadPanelDismissed.value = true
    }

    fun refreshSaved() {
        viewModelScope.launch(Dispatchers.IO) { app.photoStore.refresh() }
    }

    // Connection screen

    fun updateSettings(settings: ConnectionSettings) {
        _settings.value = settings
        app.settings.save(settings)
    }

    fun disconnect() = controller.disconnect()

    fun log(text: String) = controller.log(text)

    fun onPaired(camera: KnownCamera) {
        if (camera == pairedCamera.value) return
        app.settings.rememberCamera(camera)
        controller.log("Paired with ${camera.ssid} (${camera.bssid})")
    }

    fun forgetCamera() {
        CameraPairing.forget(app)
        app.settings.forgetCamera()
        controller.log("Forgot the paired camera")
    }
}
