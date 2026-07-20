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
 * Extra guidance for a connect failure, beyond the raw exception message. `connect` step codes:
 * -1 = TCP connect failed (wrong IP / phone not on the camera's WLAN — no camera dialog involved),
 * -2 = init-command-request/pairing-ACK failed (the camera shows a confirm dialog with a short
 * window to accept it). Returns null when no extra hint applies.
 */
fun connectHint(e: Throwable): String? {
    if (e !is PtpException || e.step != "connect") return null
    return when (e.code) {
        -2 -> "Confirm the connection on the camera and try again immediately " +
            "(the time window is only a few seconds)."
        -1 -> "Camera not reachable — check the IP and make sure your phone is connected to " +
            "the camera's Wi-Fi."
        else -> null
    }
}

/**
 * First screen shown on app start: enter the camera's WLAN IP and connect. Task 12 wires this in
 * (currently unreferenced by MainActivity, which still renders DebugScreen directly).
 */
@Composable
fun ConnectScreen(
    container: AppContainer,
    onConnected: () -> Unit,
    onDebug: () -> Unit,
    onCredentials: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var ip by remember { mutableStateOf(container.prefs.lastIp ?: "192.168.1.2") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    val autoCreds = container.prefs.cameraCredentials()

    fun connect() {
        if (busy) return
        busy = true
        error = null
        hint = null
        scope.launch {
            runCatching { container.repo.connect(ip) }
                .onSuccess { onConnected() }
                .onFailure { e ->
                    error = e.message ?: e.toString()
                    hint = connectHint(e)
                }
            busy = false
        }
    }

    fun autoConnect() {
        if (busy) return
        val creds = container.prefs.cameraCredentials() ?: return
        busy = true
        error = null
        hint = null
        scope.launch {
            runCatching {
                container.wifi.connectToCamera(creds)
                container.repo.connect(ip)
            }.onSuccess { onConnected() }
                .onFailure { e ->
                    // Covers BOTH failure sub-paths (WiFi join itself, or PTP connect after a
                    // successful join): either way the process must not stay bound to a
                    // no-internet camera network.
                    container.wifi.release()
                    error = e.message ?: e.toString()
                    hint = connectHint(e)
                }
            busy = false
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text("Connect to camera", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(16.dp))

                Text("1. Camera: Menu → Remote control (EOS Utility) → Add a device")
                Text("2. Join the camera's Wi-Fi on your phone")
                Text("3. Confirm the IP below, then accept the prompt on the camera")
                Spacer(modifier = Modifier.height(16.dp))

                if (autoCreds != null) {
                    Button(
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { autoConnect() },
                    ) {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        } else {
                            Text("Auto-connect (Wi-Fi + camera)")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                TextButton(onClick = onCredentials) {
                    Text("Wi-Fi credentials…")
                }
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text("Camera IP") },
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
                        Text("Connect")
                    }
                }

                error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                    hint?.let { h -> Text(h, color = MaterialTheme.colorScheme.error) }
                }

                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = onDebug) {
                    Text("Debug", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
