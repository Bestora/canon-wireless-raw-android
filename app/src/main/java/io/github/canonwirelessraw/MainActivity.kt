package io.github.canonwirelessraw

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.canonwirelessraw.data.GalleryItem
import io.github.canonwirelessraw.ui.ConnectScreen
import io.github.canonwirelessraw.ui.CredentialsScreen
import io.github.canonwirelessraw.ui.DebugScreen
import io.github.canonwirelessraw.ui.DownloadScreen
import io.github.canonwirelessraw.ui.GalleryScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContainer.init(applicationContext)
        setContent { App() }
    }
}

/** Five screens, plain state — no nav-lib needed for a flow this small. */
sealed interface Screen {
    data object Connect : Screen
    data object Credentials : Screen
    data object Gallery : Screen
    data class Download(val items: List<GalleryItem>) : Screen
    data object Debug : Screen
}

@Composable
fun App() {
    var screen by remember { mutableStateOf<Screen>(Screen.Connect) }
    val scope = rememberCoroutineScope()

    BackHandler(enabled = screen is Screen.Debug) { screen = Screen.Connect }
    BackHandler(enabled = screen is Screen.Credentials) { screen = Screen.Connect }
    BackHandler(enabled = screen is Screen.Gallery) {
        scope.launch { runCatching { AppContainer.repo.disconnect() } }
        AppContainer.wifi.release()
        screen = Screen.Connect
    }

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
        Screen.Gallery -> GalleryScreen(AppContainer) { items -> screen = Screen.Download(items) }
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
