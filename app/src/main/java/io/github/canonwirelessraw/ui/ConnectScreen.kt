package io.github.canonwirelessraw.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.canonwirelessraw.AppContainer
import io.github.canonwirelessraw.ptp.PtpException
import kotlinx.coroutines.launch

/**
 * First screen shown on app start: enter the camera's WLAN IP and connect. Task 12 wires this in
 * (currently unreferenced by MainActivity, which still renders DebugScreen directly).
 */
@Composable
fun ConnectScreen(container: AppContainer, onConnected: () -> Unit, onDebug: () -> Unit) {
    val scope = rememberCoroutineScope()

    var ip by remember { mutableStateOf(container.prefs.lastIp ?: "192.168.1.2") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryHint by remember { mutableStateOf(false) }

    fun connect() {
        if (busy) return
        busy = true
        error = null
        retryHint = false
        scope.launch {
            runCatching { container.repo.connect(ip) }
                .onSuccess { onConnected() }
                .onFailure { e ->
                    error = e.message ?: e.toString()
                    retryHint = e is PtpException && e.step == "connect" && e.code == -1
                }
            busy = false
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text("Mit Kamera verbinden", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(16.dp))

                Text("1. Kamera: Menü → WLAN → „Mit Smartphone verbinden\"")
                Text("2. S25 mit dem Kamera-WLAN verbinden")
                Text("3. IP unten bestätigen")
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text("Kamera-IP") },
                    enabled = !busy,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { connect() },
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    } else {
                        Text("Verbinden")
                    }
                }

                error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                    if (retryHint) {
                        Text(
                            "Bestätige die Verbindung an der Kamera und versuche es sofort erneut " +
                                "(Zeitfenster nur wenige Sekunden).",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = onDebug) {
                    Text("Debug", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
