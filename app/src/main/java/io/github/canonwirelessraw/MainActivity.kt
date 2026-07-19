package io.github.canonwirelessraw

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import io.github.canonwirelessraw.ui.DebugScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContainer.init(applicationContext)
        setContent { App() }
    }
}

/** Debug screen as the app's start (Milestone 0); Task 12 replaces this with real navigation. */
@Composable
fun App() {
    DebugScreen(AppContainer)
}
