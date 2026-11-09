package de.bestora.canonwirelessrawandroid

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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.bestora.canonwirelessrawandroid.data.GalleryItem
import de.bestora.canonwirelessrawandroid.data.Link
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import de.bestora.canonwirelessrawandroid.ui.AppTheme
import de.bestora.canonwirelessrawandroid.ui.ConnectScreen
import de.bestora.canonwirelessrawandroid.ui.CredentialsScreen
import de.bestora.canonwirelessrawandroid.ui.DebugScreen
import de.bestora.canonwirelessrawandroid.ui.DownloadScreen
import de.bestora.canonwirelessrawandroid.ui.GalleryScreen
import de.bestora.canonwirelessrawandroid.ui.ImageViewerScreen
import de.bestora.canonwirelessrawandroid.ui.Palette
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContainer.init(applicationContext)
        setContent { App() }
    }
}

/** How long the app may sit in the background (screen off, app switch) before it ends the camera
 *  session and hands the camera Wi-Fi back. */
private const val BACKGROUND_GRACE_MS = 5 * 60_000L

/** Plain state screens — no nav-lib needed for a flow this small. */
sealed interface Screen {
    data object Connect : Screen
    data object Credentials : Screen
    data object Gallery : Screen
    data class Viewer(val item: GalleryItem) : Screen
    data class Download(val items: List<GalleryItem>, val openInEditor: Boolean) : Screen
    data object Debug : Screen
}

@Composable
fun App() {
    var screen by remember { mutableStateOf<Screen>(Screen.Connect) }
    val scope = rememberCoroutineScope()

    // A foreground service for as long as a session is open: without it Android drops the camera
    // Wi-Fi a few seconds after the user switches to another app.
    val context = LocalContext.current
    val link by AppContainer.repo.link.collectAsState()
    LaunchedEffect(link) {
        when (link) {
            Link.CONNECTED -> CameraSessionService.start(context, AppContainer.repo.model ?: "camera")
            Link.DISCONNECTED -> CameraSessionService.stop(context)
            Link.RECONNECTING, Link.LOST -> {}
        }
    }

    fun endSession() {
        AppContainer.wifi.release()
        scope.launch { AppContainer.connector.disconnect() }
        screen = Screen.Connect
    }

    // Releasing the camera Wi-Fi drops the network, and with it the session. Doing that on every
    // ON_STOP killed the session whenever the screen went off; wait out a grace period instead.
    // ponytail: no foreground service, so Doze can still cut a long screen-off download short.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var pending: Job? = null
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> pending = scope.launch {
                    delay(BACKGROUND_GRACE_MS)
                    endSession()
                }
                Lifecycle.Event.ON_START -> pending?.cancel()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            AppContainer.wifi.release()
            AppContainer.ble.close()
        }
    }

    BackHandler(enabled = screen is Screen.Debug) { screen = Screen.Connect }
    BackHandler(enabled = screen is Screen.Credentials) { screen = Screen.Connect }
    // Back from the gallery kills the PTP session, and reconnecting needs a fresh confirmation on
    // the camera (few-second window) — too expensive to lose to an accidental back gesture, so ask.
    var confirmDisconnect by remember { mutableStateOf(false) }
    BackHandler(enabled = screen is Screen.Gallery) { confirmDisconnect = true }
    // Viewer is an overlay over the gallery: back just returns, session stays alive.
    BackHandler(enabled = screen is Screen.Viewer) { screen = Screen.Gallery }

    if (confirmDisconnect) {
        AppTheme {
            AlertDialog(
                onDismissRequest = { confirmDisconnect = false },
                title = { Text("Disconnect from camera?") },
                text = { Text("Reconnecting requires confirming again on the camera.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDisconnect = false
                        endSession()
                    }) { Text("Disconnect") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDisconnect = false }) { Text("Stay") }
                },
            )
        }
    }

    // targetSdk 35 draws edge to edge: keep every screen clear of the status and navigation bars.
    Box(Modifier.fillMaxSize().background(Palette.LoupeGrey).safeDrawingPadding()) {
        when (screen) {
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
            // Gallery, Viewer and Download share one branch: the gallery stays composed underneath
            // while the others overlay it, so scroll position, rating filter and selection all survive
            // the trip into the viewer/download and back (they live in GalleryScreen's remembered state).
            Screen.Gallery, is Screen.Viewer, is Screen.Download -> Box(Modifier.fillMaxSize()) {
                GalleryScreen(
                    AppContainer,
                    onOpen = { item -> screen = Screen.Viewer(item) },
                    onDownload = { items, open -> screen = Screen.Download(items, open) },
                )
                (screen as? Screen.Viewer)?.let { v ->
                    ImageViewerScreen(
                        AppContainer,
                        v.item,
                        onBack = { screen = Screen.Gallery },
                        onDownload = { open -> screen = Screen.Download(listOf(v.item), open) },
                    )
                }
                (screen as? Screen.Download)?.let { d ->
                    DownloadScreen(
                        AppContainer,
                        d.items,
                        d.openInEditor,
                        onDone = { screen = Screen.Gallery },
                    )
                }
            }
            Screen.Debug -> DebugScreen(AppContainer)
        }
    }
}
