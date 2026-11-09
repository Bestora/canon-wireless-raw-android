package de.bestora.canonwirelessrawandroid.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.bestora.canonwirelessrawandroid.AppContainer
import de.bestora.canonwirelessrawandroid.data.CameraProfile
import de.bestora.canonwirelessrawandroid.ptp.PtpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val NOTIFICATION_PERMISSION =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

/** API 31+: BLUETOOTH_SCAN/CONNECT are runtime permissions; below, BLE scanning needs location. */
private val BLE_PERMISSIONS =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

/** What a retrying connect is waiting for, shown live under the button. Codes: see PtpNative.connect. */
fun connectStatus(e: PtpException): String = when {
    e.step != "connect" -> "Attempt failed (${e.message}), retrying…"
    e.code == -2 -> "Confirm the connection on the camera…"
    e.code == -1 -> "Camera not reachable yet, retrying…"
    e.code == -6 -> "Waiting for the camera to answer…"
    else -> "Attempt failed (${e.message}), retrying…"
}

/** Guidance once connect gave up, beyond the raw exception message; null when none applies. */
fun connectHint(e: Throwable): String? {
    if (e !is PtpException || e.step != "connect") return null
    return when (e.code) {
        -2 -> "The camera did not confirm the connection in time. Accept the prompt on the camera " +
            "and try again."
        -1 -> "Camera not reachable — check the IP and make sure your phone is connected to " +
            "the camera's Wi-Fi."
        -6 -> "The camera did not answer. Make sure it is in Remote control (EOS Utility) mode " +
            "and waiting for a connection."
        else -> null
    }
}

/** First screen: paired cameras (Bluetooth), adding one, and the manual Wi-Fi/IP fallback. */
@Composable
fun ConnectScreen(
    container: AppContainer,
    onConnected: () -> Unit,
    onDebug: () -> Unit,
    onCredentials: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var cameras by remember { mutableStateOf(container.prefs.cameras()) }
    var ip by remember { mutableStateOf(container.prefs.lastIp ?: "192.168.1.2") }
    var job by remember { mutableStateOf<Job?>(null) }
    val busy = job != null
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var showManual by remember { mutableStateOf(cameras.isEmpty() && container.prefs.lastIp != null) }
    val autoCreds = container.prefs.cameraCredentials()

    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (BLE_PERMISSIONS.all { result[it] != false && context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            afterPermission?.invoke()
        } else {
            error = "Bluetooth permission is needed to talk to the camera."
        }
        afterPermission = null
    }
    // The notification permission rides along: it only makes the "connected" notification visible.
    fun withBluetooth(action: () -> Unit) {
        val wanted = BLE_PERMISSIONS + NOTIFICATION_PERMISSION
        if (wanted.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            action()
        } else {
            afterPermission = action
            permissionLauncher.launch(wanted)
        }
    }

    fun launchTask(block: suspend () -> Unit) {
        if (busy) return
        error = null
        hint = null
        notice = null
        status = "Connecting…"
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.toString()
                hint = connectHint(e)
            } finally {
                job = null
                status = null
            }
        }
    }

    fun connectCamera(camera: CameraProfile) = withBluetooth {
        launchTask {
            container.connector.connect(camera, status = { status = it }, onRetry = { status = connectStatus(it) })
            onConnected()
        }
    }

    fun pairCamera() = withBluetooth {
        launchTask {
            val camera = container.connector.pair { status = it }
            cameras = container.prefs.cameras()
            notice = "${camera.displayName} is paired. Press MENU on the camera to leave the pairing " +
                "screen, then tap Connect."
        }
    }

    fun connectIp() = launchTask {
        container.repo.connect(ip) { status = connectStatus(it) }
        onConnected()
    }

    fun autoConnectWifi() {
        val creds = container.prefs.cameraCredentials() ?: return
        launchTask {
            try {
                status = "Joining the camera's Wi-Fi…"
                container.wifi.connectToCamera(creds)
                container.repo.connect(ip) { status = connectStatus(it) }
            } catch (e: Throwable) {
                // Wi-Fi join failed, PTP connect gave up, or the user cancelled: either way the
                // process must not stay bound to a no-internet camera network.
                container.wifi.release()
                throw e
            }
            onConnected()
        }
    }

    var connecting by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(cameras.isEmpty()) }

    AppTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Text("Canon Wireless RAW", style = MaterialTheme.typography.bodyMedium, color = Palette.Graphite)
                Spacer(modifier = Modifier.height(20.dp))

                cameras.forEachIndexed { i, camera ->
                    if (i > 0) HorizontalDivider(color = Palette.Raised, modifier = Modifier.padding(vertical = 20.dp))
                    val thisOne = busy && connecting == camera.address
                    Text(camera.displayName.removePrefix("Canon "), style = MaterialTheme.typography.displaySmall)
                    Text(
                        if (thisOne) status ?: "Connecting…" else "Paired over Bluetooth",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.Graphite,
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    if (thisOne) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Palette.Paper, trackColor = Palette.Raised)
                        TextButton(onClick = { job?.cancel() }) { Text("Cancel", color = Palette.Paper) }
                    } else {
                        PrimaryButton("Connect", { connecting = camera.address; connectCamera(camera) }, Modifier.fillMaxWidth(), enabled = !busy)
                        TextButton(enabled = !busy, contentPadding = PaddingValues(0.dp), onClick = {
                            container.prefs.removeCamera(camera.address)
                            cameras = container.prefs.cameras()
                        }) { Text("Remove from this list", color = Palette.Graphite, style = MaterialTheme.typography.bodyMedium) }
                    }
                }

                if (busy && connecting == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Palette.Paper, strokeWidth = 2.dp)
                        Text(status ?: "", modifier = Modifier.weight(1f).padding(start = 12.dp))
                        TextButton(onClick = { job?.cancel() }) { Text("Cancel", color = Palette.Paper) }
                    }
                }
                notice?.let { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 8.dp)) }
                error?.let {
                    Text(it, color = Palette.Amber, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                    hint?.let { h -> Text(h, color = Palette.Amber, style = MaterialTheme.typography.bodyMedium) }
                }

                Spacer(modifier = Modifier.height(28.dp))
                if (cameras.isNotEmpty() && !showAdd) {
                    TextButton(onClick = { showAdd = true }, contentPadding = PaddingValues(0.dp)) {
                        Text("Add another camera", color = Palette.Paper)
                    }
                } else {
                    Text(if (cameras.isEmpty()) "Pair your camera" else "Add a camera", style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(10.dp))
                    PairingStep(1, "On the camera, open Menu → Wi-Fi/Bluetooth connection → Connect to smartphone → Add a device.")
                    PairingStep(2, "If it asks about a QR code, choose “Do not display”.")
                    PairingStep(3, "Tap Search, then press OK on the camera when it asks.")
                    Spacer(modifier = Modifier.height(12.dp))
                    // The main action when no camera is paired yet; secondary next to a camera's Connect.
                    if (cameras.isEmpty()) {
                        PrimaryButton("Search for camera", { connecting = null; pairCamera() }, Modifier.fillMaxWidth(), enabled = !busy)
                    } else {
                        SecondaryButton("Search for camera", { connecting = null; pairCamera() }, Modifier.fillMaxWidth(), enabled = !busy)
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))
                TextButton(onClick = { showManual = !showManual }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (showManual) "Hide other ways to connect" else "Other ways to connect", color = Palette.Graphite)
                }
                if (showManual) {
                    Text(
                        "For cameras without Bluetooth, or EOS Utility pairing: on the camera choose " +
                            "Remote control (EOS Utility) → Add a device, join its Wi-Fi, then connect by IP.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.Graphite,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    if (autoCreds != null) {
                        SecondaryButton("Join saved Wi-Fi and connect", { connecting = null; autoConnectWifi() }, Modifier.fillMaxWidth(), enabled = !busy)
                    }
                    TextButton(onClick = onCredentials, contentPadding = PaddingValues(0.dp)) {
                        Text("Wi-Fi name and password…", color = Palette.Paper)
                    }
                    OutlinedTextField(
                        value = ip,
                        onValueChange = { ip = it },
                        label = { Text("Camera IP address") },
                        enabled = !busy,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SecondaryButton("Connect by IP", { connecting = null; connectIp() }, Modifier.fillMaxWidth(), enabled = !busy)
                }

                Spacer(modifier = Modifier.height(20.dp))
                TextButton(onClick = onDebug, contentPadding = PaddingValues(0.dp)) {
                    Text("Diagnostics", style = MaterialTheme.typography.labelSmall, color = Palette.Graphite)
                }
            }
        }
    }
}

/** The pairing is a real sequence on the camera, so it is numbered. */
@Composable
private fun PairingStep(n: Int, text: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text("$n", style = MaterialTheme.typography.titleLarge, color = Palette.Graphite, modifier = Modifier.width(28.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

