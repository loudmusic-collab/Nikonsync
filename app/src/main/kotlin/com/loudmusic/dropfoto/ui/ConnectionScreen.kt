package com.loudmusic.dropfoto.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loudmusic.dropfoto.connection.CameraPairing
import com.loudmusic.dropfoto.connection.CameraService
import com.loudmusic.dropfoto.connection.CameraWifiProvider
import com.loudmusic.dropfoto.connection.ConnectionMode
import com.loudmusic.dropfoto.connection.ConnectionSettings
import com.loudmusic.dropfoto.connection.ConnectionState
import com.loudmusic.dropfoto.connection.KnownCamera
import com.loudmusic.dropfoto.connection.LogLine
import java.time.format.DateTimeFormatter

/**
 * Phase 2 test screen: connect, watch the link, list the card, read the log.
 * The real gallery replaces most of this in Phase 3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()
    val card by vm.card.collectAsStateWithLifecycle()
    val listing by vm.listing.collectAsStateWithLifecycle()
    val paired by vm.pairedCamera.collectAsStateWithLifecycle()

    val permissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }.toTypedArray()
    }
    // Connect whatever the answer: a denied permission shows up as a clear failure in the log.
    val requestPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        CameraService.connect(context)
    }

    val pairResult = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            CameraPairing.parseResult(result.data)?.let(vm::onPaired)
        } else {
            vm.log("Pairing cancelled")
        }
    }
    val startPairing = {
        val activity = context as Activity
        vm.log("Pairing: pick your camera's network in the list")
        CameraPairing.start(
            activity,
            onPending = { sender -> pairResult.launch(IntentSenderRequest.Builder(sender).build()) },
            onPaired = vm::onPaired,
            onFailure = { vm.log("Pairing failed: $it") },
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("DropFoto · connection test") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingsCard(settings, enabled = !state.isActive, onChange = vm::updateSettings)
            }
            if (settings.mode == ConnectionMode.CAMERA_WIFI) {
                item {
                    PairingCard(paired, enabled = !state.isActive, onPair = startPairing, onForget = vm::forgetCamera)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.isActive) {
                        Button(onClick = vm::disconnect) { Text("Disconnect") }
                    } else {
                        Button(onClick = { requestPermissions.launch(permissions) }) { Text("Connect") }
                    }
                    TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }) { Text("Battery settings") }
                }
            }
            item { StatusCard(state) }
            if (state is ConnectionState.Connected) {
                item { CardCard(card, listing, onList = vm::listFiles) }
            }
            item { Text("Log", style = MaterialTheme.typography.titleMedium) }
            items(log.asReversed()) { LogRow(it) }
        }
    }
}

@Composable
private fun SettingsCard(settings: ConnectionSettings, enabled: Boolean, onChange: (ConnectionSettings) -> Unit) {
    var showPassword by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("How to reach the camera", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val modes = listOf(ConnectionMode.CAMERA_WIFI to "Join camera Wi-Fi", ConnectionMode.CURRENT_WIFI to "Current Wi-Fi")
                modes.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = settings.mode == mode,
                        onClick = { onChange(settings.copy(mode = mode)) },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        enabled = enabled,
                    ) { Text(label) }
                }
            }
            if (settings.mode == ConnectionMode.CAMERA_WIFI) {
                OutlinedTextField(
                    value = settings.ssid,
                    onValueChange = { onChange(settings.copy(ssid = it)) },
                    label = { Text("Camera network name") },
                    placeholder = { Text("Blank = any ${CameraWifiProvider.NIKON_SSID_PREFIX}…") },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = settings.passphrase,
                    onValueChange = { onChange(settings.copy(passphrase = it)) },
                    label = { Text("Wi-Fi password (blank if none)") },
                    singleLine = true,
                    enabled = enabled,
                    visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    "Uses the Wi-Fi the phone is on now. Pick this if you joined the camera in Android's " +
                        "Wi-Fi settings, or to test with the simulated camera on a laptop.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = settings.host,
                    onValueChange = { onChange(settings.copy(host = it.trim())) },
                    label = { Text("Camera address") },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = settings.port.toString(),
                    onValueChange = { v -> v.toIntOrNull()?.let { onChange(settings.copy(port = it)) } },
                    label = { Text("Port") },
                    singleLine = true,
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PairingCard(paired: KnownCamera?, enabled: Boolean, onPair: () -> Unit, onForget: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Camera pairing", style = MaterialTheme.typography.titleMedium)
            if (paired != null) {
                Text("Paired with ${paired.ssid}")
                Text(
                    "Reconnects to this camera without asking for approval.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    "Not paired yet. Turn on the camera's Wi-Fi and tap Pair camera once; " +
                        "after that Android won't ask to approve every reconnect.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPair, enabled = enabled) { Text(if (paired == null) "Pair camera" else "Pair again") }
                if (paired != null) TextButton(onClick = onForget, enabled = enabled) { Text("Forget") }
            }
        }
    }
}

@Composable
private fun StatusCard(state: ConnectionState) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val busy = state is ConnectionState.JoiningNetwork || state is ConnectionState.Connecting ||
                state is ConnectionState.Reconnecting
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
            }
            Column {
                val (title, detail) = describe(state)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                detail.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

private fun describe(state: ConnectionState): Pair<String, List<String>> = when (state) {
    ConnectionState.Idle -> "Not connected" to listOf("Turn on the camera's Wi-Fi, then tap Connect.")
    is ConnectionState.JoiningNetwork ->
        if (state.rejoining) {
            "Waiting for the camera's Wi-Fi…" to listOf("The connection dropped. DropFoto reconnects as soon as the camera's Wi-Fi is back (keeps trying for 5 minutes).")
        } else {
            "Joining the camera's Wi-Fi…" to listOf("Approve the connection if Android asks.")
        }
    is ConnectionState.Connecting -> "Connecting to the camera…" to listOf("Attempt ${state.attempt}")
    is ConnectionState.Reconnecting ->
        "Reconnecting in ${state.delaySeconds} s" to listOfNotNull(
            state.reason,
            if (state.maxAttempts > 0) "Attempt ${state.attempt} of ${state.maxAttempts}" else null,
        )
    is ConnectionState.Failed -> "Connection failed" to listOf(state.message)
    is ConnectionState.Connected -> {
        val c = state.camera
        "Connected to ${c.manufacturer} ${c.model}" to listOf(
            "Firmware ${c.firmware} · serial ${c.serialNumber}",
            "Resumable downloads: ${if (c.supportsResume) "yes" else "no"} · large previews: ${if (c.supportsLargePreview) "yes" else "no"}",
            "Link checked ${state.checks} ${if (state.checks == 1) "time" else "times"}" + (state.lastCheckMillis?.let { ", last took $it ms" } ?: ""),
        )
    }
}

@Composable
private fun CardCard(card: CardSummary?, listing: Boolean, onList: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Memory card", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onList, enabled = !listing) { Text(if (listing) "Listing…" else "List files") }
            }
            if (card != null) {
                Text(
                    "${card.files} files · ${formatSize(card.totalBytes)} · ${card.rawJpegPairs} RAW+JPEG pairs · " +
                        "listed in ${"%.1f".format(card.seconds)} s",
                )
                card.newest.forEach { f ->
                    Text("${f.filename}  ${formatSize(f.size)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private val logTime = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
private fun LogRow(line: LogLine) {
    Text(
        "${line.time.format(logTime)}  ${line.text}",
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
