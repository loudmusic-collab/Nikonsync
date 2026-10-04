package com.loudmusic.dropfoto.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DropFotoTheme {
                AppRoot()
            }
        }
    }
}

@Composable
fun DropFotoTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private sealed interface Screen {
    data object Gallery : Screen
    data object Connection : Screen
    data class Viewer(val shotKey: String) : Screen
}

/** Gallery is home; connection settings and the photo viewer sit on top of it. */
@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    var screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen>(Screen.Gallery) }
    BackHandler(enabled = screen != Screen.Gallery) { screen = Screen.Gallery }
    when (val s = screen) {
        Screen.Gallery -> GalleryScreen(
            vm,
            onOpenShot = { screen = Screen.Viewer(it.id) },
            onOpenConnection = { screen = Screen.Connection },
        )
        Screen.Connection -> ConnectionScreen(vm, onBack = { screen = Screen.Gallery })
        is Screen.Viewer -> {
            val shots by vm.shots.collectAsStateWithLifecycle()
            val shot = shots.firstOrNull { it.id == s.shotKey }
            if (shot == null) {
                screen = Screen.Gallery
            } else {
                ViewerScreen(vm, shot, onBack = { screen = Screen.Gallery })
            }
        }
    }
}

private val ScreenSaver = androidx.compose.runtime.saveable.Saver<Screen, String>(
    save = {
        when (it) {
            Screen.Gallery -> "gallery"
            Screen.Connection -> "connection"
            is Screen.Viewer -> "viewer:" + it.shotKey
        }
    },
    restore = {
        when {
            it == "connection" -> Screen.Connection
            it.startsWith("viewer:") -> Screen.Viewer(it.removePrefix("viewer:"))
            else -> Screen.Gallery
        }
    },
)
