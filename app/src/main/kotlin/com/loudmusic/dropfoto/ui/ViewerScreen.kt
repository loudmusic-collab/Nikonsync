package com.loudmusic.dropfoto.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loudmusic.dropfoto.gallery.DownloadChoice
import com.loudmusic.dropfoto.gallery.Shot
import java.time.format.DateTimeFormatter

private val takenFormat = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(vm: MainViewModel, shot: Shot, onBack: () -> Unit) {
    val camera by vm.camera.collectAsStateWithLifecycle()
    val saved by vm.savedKeys.collectAsStateWithLifecycle()
    val queued by vm.queuedKeys.collectAsStateWithLifecycle()
    var preview by remember(shot.primary.key) { mutableStateOf<Bitmap?>(vm.thumbnails.cached(shot.primary)) }
    var loadingLarge by remember(shot.primary.key) { mutableStateOf(true) }
    LaunchedEffect(shot.primary.key, camera) {
        if (camera != null) vm.thumbnails.preview(shot.primary)?.let { preview = it }
        loadingLarge = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(shot.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black), contentAlignment = Alignment.Center) {
                preview?.let {
                    Image(it.asImageBitmap(), contentDescription = shot.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                if (loadingLarge && camera != null) CircularProgressIndicator()
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                shot.captured?.let { Text("Taken ${it.format(takenFormat)}", style = MaterialTheme.typography.bodyMedium) }
                shot.files.forEach { f ->
                    val status = when {
                        f.key in saved -> "on phone"
                        f.key in queued -> "queued"
                        else -> "on camera"
                    }
                    Text("${f.filename} · ${formatSize(f.size)} · $status", style = MaterialTheme.typography.bodyMedium)
                }
                val options = if (shot.video != null) listOf(DownloadChoice.JPEG) else DownloadChoice.entries.filter { c ->
                    // Only offer RAW / both when the shot has a RAW, and JPEG when it has a JPEG.
                    when (c) {
                        DownloadChoice.JPEG -> shot.jpeg != null
                        DownloadChoice.RAW -> shot.raw != null
                        DownloadChoice.BOTH -> shot.jpeg != null && shot.raw != null
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { c ->
                        val files = shot.filesFor(c)
                        val needed = files.filter { it.key !in saved && it.key !in queued }
                        FilledTonalButton(onClick = { vm.download(listOf(shot), c) }, enabled = needed.isNotEmpty()) {
                            Text(if (shot.video != null) "Download" else c.label)
                        }
                    }
                }
            }
        }
    }
}
