package io.github.canonwirelessraw

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.canonwirelessraw.data.GalleryItem
import io.github.canonwirelessraw.ui.ConnectScreen
import io.github.canonwirelessraw.ui.CredentialsScreen
import io.github.canonwirelessraw.ui.DebugScreen
import io.github.canonwirelessraw.ui.DownloadScreen
import io.github.canonwirelessraw.ui.GalleryScreen
import io.github.canonwirelessraw.ui.ImageViewerScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContainer.init(applicationContext)
        setContent { App() }
    }
}

/** Plain state screens — no nav-lib needed for a flow this small. */
sealed interface Screen {
    data object Connect : Screen
    data object Credentials : Screen
    data object Gallery : Screen
    data class Viewer(val item: GalleryItem) : Screen
    data class Download(val items: List<GalleryItem>) : Screen
    data object Debug : Screen
}

@Composable
fun App() {
    var screen by remember { mutableStateOf<Screen>(Screen.Connect) }
    val scope = rememberCoroutineScope()

    // App backgrounded (Home button etc.) while bound to the camera's internet-less WLAN would
    // otherwise strand the process on it until restart — release on ON_STOP, same as any other
    // "leaving a session" path. Idempotent; only fires when the app is no longer visible, not on
    // in-app screen switches.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) AppContainer.wifi.release()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = screen is Screen.Debug) { screen = Screen.Connect }
    BackHandler(enabled = screen is Screen.Credentials) { screen = Screen.Connect }
    BackHandler(enabled = screen is Screen.Gallery) {
        scope.launch { runCatching { AppContainer.repo.disconnect() } }
        AppContainer.wifi.release()
        screen = Screen.Connect
    }
    // Viewer is an overlay over the gallery: back just returns, session stays alive.
    BackHandler(enabled = screen is Screen.Viewer) { screen = Screen.Gallery }

    when (val s = screen) {
        Screen.Connect -> ConnectScreen(
            AppContainer,
            onConnected = { screen = Screen.Gallery },
            onDebug = { screen = Screen.Debug },
            onCredentials = { screen = Screen.Credentials },
        )
        Screen.Credentials -> CredentialsScreen(
            AppContainer,
            onSaved = { screen = Screen.Connect },
            onBack = { screen = Screen.Connect },
        )
        // Gallery and Viewer share one branch: the gallery stays composed underneath while the
        // viewer overlays it, so scroll position, rating filter and selection all survive the
        // trip into the viewer and back (they live in GalleryScreen's own remembered state).
        Screen.Gallery, is Screen.Viewer -> Box(Modifier.fillMaxSize()) {
            GalleryScreen(
                AppContainer,
                onOpen = { item -> screen = Screen.Viewer(item) },
                onDownload = { items -> screen = Screen.Download(items) },
            )
            (screen as? Screen.Viewer)?.let { v ->
                ImageViewerScreen(AppContainer, v.item, onBack = { screen = Screen.Gallery })
            }
        }
        is Screen.Download -> DownloadScreen(
            AppContainer,
            s.items,
            onDone = { screen = Screen.Gallery },
            // Cancelled batch left the session dead: drop it and route back to reconnect.
            onSessionDead = {
                scope.launch { runCatching { AppContainer.repo.disconnect() } }
                AppContainer.wifi.release()
                screen = Screen.Connect
            },
        )
        Screen.Debug -> DebugScreen(AppContainer)
    }
}
