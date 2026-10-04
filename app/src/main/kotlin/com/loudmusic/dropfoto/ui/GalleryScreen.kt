package com.loudmusic.dropfoto.ui

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loudmusic.dropfoto.connection.ConnectionState
import com.loudmusic.dropfoto.download.DownloadProgress
import com.loudmusic.dropfoto.download.RawFormat
import com.loudmusic.dropfoto.gallery.CardFile
import com.loudmusic.dropfoto.gallery.DownloadChoice
import com.loudmusic.dropfoto.gallery.FileKind
import com.loudmusic.dropfoto.gallery.IndexStatus
import com.loudmusic.dropfoto.gallery.Shot
import com.loudmusic.dropfoto.gallery.ThumbnailLoader
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(vm: MainViewModel, onOpenShot: (Shot) -> Unit, onOpenConnection: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val camera by vm.camera.collectAsStateWithLifecycle()
    val shots by vm.visibleShots.collectAsStateWithLifecycle()
    val allShots by vm.shots.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val saved by vm.savedKeys.collectAsStateWithLifecycle()
    val queued by vm.queuedKeys.collectAsStateWithLifecycle()
    val indexStatus by vm.indexStatus.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val panelDismissed by vm.downloadPanelDismissed.collectAsStateWithLifecycle()
    val choice by vm.downloadChoice.collectAsStateWithLifecycle()
    val rawFormat by vm.rawFormat.collectAsStateWithLifecycle()
    val connect = rememberConnectAction()
    val selecting = selection.isNotEmpty()

    LaunchedEffect(Unit) { vm.refreshSaved() }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("${selection.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = vm::clearSelection) { Icon(Icons.Default.Close, contentDescription = "Clear selection") }
                    },
                    actions = { TextButton(onClick = vm::selectAllVisible) { Text("Select all") } },
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text("DropFoto")
                            Text(
                                connectionLine(state),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenConnection) { Icon(Icons.Default.Settings, contentDescription = "Connection") }
                    },
                )
            }
        },
        bottomBar = {
            Column {
                if ((downloads.running || downloads.total > 0) && !panelDismissed) {
                    DownloadPanel(downloads, rawFormat, onCancel = vm::cancelDownloads, onDismiss = vm::dismissDownloadPanel)
                }
                if (selecting) {
                    SelectionBar(vm.selectedShots(), saved, choice, onChoice = vm::setDownloadChoice, onDownload = vm::downloadSelection)
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state !is ConnectionState.Connected) {
                ConnectBanner(state, hasPhotos = allShots.isNotEmpty(), onConnect = connect, onOpenConnection = onOpenConnection)
            }
            IndexLine(indexStatus, connected = camera != null)
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GalleryFilter.entries.forEach { f ->
                    FilterChip(selected = filter == f, onClick = { vm.setFilter(f) }, label = { Text(f.label) })
                }
            }
            ShotGrid(
                shots = shots,
                loader = vm.thumbnails,
                cameraAvailable = camera != null,
                selection = selection,
                saved = saved,
                queued = queued,
                onTap = { shot -> if (selecting) vm.toggle(shot) else onOpenShot(shot) },
                onLongPress = vm::toggle,
                onSelectDay = { dayShots, select -> vm.setSelected(dayShots, select) },
            )
        }
    }
}

private fun connectionLine(state: ConnectionState) = when (state) {
    is ConnectionState.Connected -> "Connected to ${state.camera.model}"
    is ConnectionState.JoiningNetwork -> if (state.rejoining) "Waiting for camera Wi-Fi…" else "Joining camera Wi-Fi…"
    is ConnectionState.Connecting -> "Connecting…"
    is ConnectionState.Reconnecting -> "Reconnecting…"
    is ConnectionState.Failed -> state.title
    ConnectionState.Idle -> "Not connected"
}

@Composable
private fun ConnectBanner(state: ConnectionState, hasPhotos: Boolean, onConnect: () -> Unit, onOpenConnection: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val busy = state.isActive
            Text(
                when {
                    busy -> connectionLine(state)
                    state is ConnectionState.Failed -> "${state.title}. ${state.message}"
                    hasPhotos -> "Not connected. Showing the card as it was last time."
                    else -> "Turn on the camera's Wi-Fi, then connect."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Button(onClick = onConnect) { Text("Connect") }
                }
                TextButton(onClick = onOpenConnection) { Text("Connection settings") }
            }
        }
    }
}

@Composable
private fun IndexLine(status: IndexStatus, connected: Boolean) {
    when (status) {
        is IndexStatus.Reading -> if (connected) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text("Reading the card… ${status.done} of ${status.total}", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(
                    progress = { if (status.total > 0) status.done.toFloat() / status.total else 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
        is IndexStatus.Error -> Text(
            status.message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        else -> Unit
    }
}

private val dayFormat = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShotGrid(
    shots: List<Shot>,
    loader: ThumbnailLoader,
    cameraAvailable: Boolean,
    selection: Set<String>,
    saved: Set<String>,
    queued: Set<String>,
    onTap: (Shot) -> Unit,
    onLongPress: (Shot) -> Unit,
    onSelectDay: (List<Shot>, Boolean) -> Unit,
) {
    val days = remember(shots) { shots.groupBy { it.day }.entries.toList() }
    val gridState = rememberLazyGridState()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        state = gridState,
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        for ((day, dayShots) in days) {
            item(key = "day-$day", span = { GridItemSpan(maxLineSpan) }) {
                DayHeader(day, dayShots, selection, onSelectDay)
            }
            items(dayShots, key = { it.id }) { shot ->
                ShotTile(
                    shot = shot,
                    loader = loader,
                    cameraAvailable = cameraAvailable,
                    selected = shot.id in selection,
                    saved = saved,
                    queued = shot.files.any { it.key in queued },
                    modifier = Modifier.combinedClickable(onClick = { onTap(shot) }, onLongClick = { onLongPress(shot) }),
                )
            }
        }
    }
}

@Composable
private fun DayHeader(day: LocalDate?, shots: List<Shot>, selection: Set<String>, onSelectDay: (List<Shot>, Boolean) -> Unit) {
    val allSelected = shots.all { it.id in selection }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            (day?.format(dayFormat) ?: "Unknown date") + " · ${shots.size}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onSelectDay(shots, !allSelected) }) { Text(if (allSelected) "Deselect" else "Select") }
    }
}

@Composable
private fun ShotTile(
    shot: Shot,
    loader: ThumbnailLoader,
    cameraAvailable: Boolean,
    selected: Boolean,
    saved: Set<String>,
    queued: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier),
    ) {
        Thumbnail(shot.primary, loader, cameraAvailable, Modifier.fillMaxSize())
        if (selected) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)))
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).background(Color.White, CircleShape),
            )
        }
        Badge(shot.label, Modifier.align(Alignment.BottomStart))
        savedMark(shot, saved, queued)?.let { Badge(it, Modifier.align(Alignment.TopEnd), strong = true) }
    }
}

/** "✓" when every file is on the phone, "JPG ✓"/"RAW ✓" for half a pair, "↓" while queued. */
private fun savedMark(shot: Shot, saved: Set<String>, queued: Boolean): String? {
    val onPhone = shot.files.filter { it.key in saved }
    return when {
        onPhone.size == shot.files.size -> "✓"
        queued -> "↓"
        onPhone.isNotEmpty() -> onPhone.joinToString(" ") { if (it == shot.raw) "RAW" else "JPG" } + " ✓"
        else -> null
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier, strong: Boolean = false) {
    Surface(
        color = if (strong) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.55f),
        contentColor = if (strong) MaterialTheme.colorScheme.onPrimary else Color.White,
        shape = RoundedCornerShape(4.dp),
        modifier = modifier.padding(4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
    }
}

@Composable
fun Thumbnail(file: CardFile, loader: ThumbnailLoader, cameraAvailable: Boolean, modifier: Modifier = Modifier) {
    var bitmap by remember(file.key) { mutableStateOf<Bitmap?>(loader.cached(file)) }
    LaunchedEffect(file.key, cameraAvailable) {
        if (bitmap == null) bitmap = loader.thumbnail(file)
    }
    bitmap?.let {
        Image(it.asImageBitmap(), contentDescription = file.filename, contentScale = ContentScale.Crop, modifier = modifier)
    }
}

@Composable
private fun SelectionBar(
    shots: List<Shot>,
    saved: Set<String>,
    choice: DownloadChoice,
    onChoice: (DownloadChoice) -> Unit,
    onDownload: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                DownloadChoice.entries.forEachIndexed { i, c ->
                    SegmentedButton(
                        selected = c == choice,
                        onClick = { onChoice(c) },
                        shape = SegmentedButtonDefaults.itemShape(i, DownloadChoice.entries.size),
                    ) { Text(c.label) }
                }
            }
            val files = shots.flatMap { it.filesFor(choice) }.filter { it.key !in saved }
            val bytes = files.sumOf { it.size }
            Button(onClick = onDownload, enabled = files.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (files.isEmpty()) {
                        "Already on the phone"
                    } else {
                        "Download ${files.size} ${if (files.size == 1) "file" else "files"} · ${formatSize(bytes)}"
                    },
                )
            }
        }
    }
}

@Composable
private fun DownloadPanel(p: DownloadProgress, rawFormat: RawFormat, onCancel: () -> Unit, onDismiss: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (p.running) {
                val fraction = if (p.batchBytesTotal > 0) (p.batchBytesDone.toFloat() / p.batchBytesTotal).coerceIn(0f, 1f) else 0f
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Downloading ${minOf(p.done + p.failed + 1, p.total)} of ${p.total}" + (p.current?.let { " · ${it.filename}" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    when {
                        p.waitingForCamera -> "Waiting for the camera to reconnect…"
                        p.saving -> if (p.current?.kind == FileKind.RAW && rawFormat != RawFormat.NEF) "Converting to DNG…" else "Saving…"
                        p.bytesPerSecond > 0 -> {
                            val left = (p.batchBytesTotal - p.batchBytesDone).coerceAtLeast(0)
                            "%.1f MB/s · about %s left".format(p.bytesPerSecond / 1e6, duration(left / p.bytesPerSecond))
                        }
                        else -> "Starting…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Saved ${p.done} ${if (p.done == 1) "file" else "files"} to Pictures/DropFoto" +
                            if (p.failed > 0) " · ${p.failed} failed" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text("OK") }
                }
                p.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                p.lastTiming?.let { Text("Last file: $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

private fun duration(seconds: Double): String = when {
    seconds < 60 -> "${seconds.toInt().coerceAtLeast(1)} s"
    seconds < 3600 -> "${(seconds / 60).toInt()} min"
    else -> "%.1f h".format(seconds / 3600)
}
