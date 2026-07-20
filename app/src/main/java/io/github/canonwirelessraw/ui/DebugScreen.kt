package io.github.canonwirelessraw.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.canonwirelessraw.AppContainer
import io.github.canonwirelessraw.ble.BleDevice
import io.github.canonwirelessraw.ble.WakeResult
import io.github.canonwirelessraw.cr3.ImageHeaderParser
import io.github.canonwirelessraw.ptp.FileKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/** Header bytes read for button 4 (128 KB partial). */
private const val PARTIAL_HEADER_BYTES = 131072

/**
 * Milestone-0 on-camera verification harness (Task 9): six buttons that drive the PTP/IP stack
 * step by step against a real R5, with every result (success and failure, including raw error
 * codes) logged visibly. Not a "real" app screen — Task 12 replaces this with navigation.
 */
@Composable
fun DebugScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var ip by remember { mutableStateOf(container.prefs.lastIp ?: "192.168.1.2") }
    var busy by remember { mutableStateOf(false) }
    var foundBleDevice by remember { mutableStateOf<BleDevice?>(null) }

    val logLines = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    fun log(msg: String) {
        logLines.add("${timeFmt.format(Date())} $msg")
    }

    // API 31+: BLUETOOTH_SCAN/CONNECT are runtime permissions. Below that (down to minSdk 29),
    // those two don't exist yet — BLUETOOTH/BLUETOOTH_ADMIN are install-time there, only
    // ACCESS_FINE_LOCATION needs a runtime grant for BLE scanning.
    val blePermissions =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    fun hasBlePermissions() =
        blePermissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    val blePermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* Ergebnis über hasBlePermissions() beim nächsten Tap geprüft */ }

    // ponytail: one busy flag guards all six buttons — good enough for a single-user debug
    // screen; a per-button flag would just be more state for the same guarantee.
    fun runStep(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { action() }.onFailure { e -> log("ERROR: $e") }
            busy = false
        }
    }

    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) listState.animateScrollToItem(logLines.size - 1)
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text("Camera IP") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        runStep {
                            log("Connect: ip=$ip")
                            container.ptp.connect(ip = ip, guid = container.prefs.pairingGuid(), eosMode = false)
                            container.prefs.lastIp = ip
                            log("Connect OK")
                        }
                    },
                ) { Text("1. Connect (Handshake+Pairing)") }

                Button(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        runStep {
                            log("EOS Remote/Event Mode: setting...")
                            val result = container.ptp.eosMode()
                            log("eosSetRemoteMode rc=${result.remoteModeCode}")
                            log("eosSetEventMode rc=${result.eventModeCode}")
                            if (result.remoteModeCode == 0 && result.eventModeCode == 0) {
                                log("EOS Mode OK")
                            } else {
                                log("EOS Mode: at least one step failed")
                            }
                        }
                    },
                ) { Text("2. EOS Remote/Event Mode") }

                Button(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        runStep {
                            container.repo.refreshList()
                            val items = container.repo.items.value
                            log("List loaded: ${items.size} objects")
                            items.take(5).forEach {
                                log(" - ${it.obj.name} (${dateFmt.format(Date(it.obj.takenAtMillis))})")
                            }
                        }
                    },
                ) { Text("3. Load list") }

                Button(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        runStep {
                            val item = container.repo.items.value.firstOrNull { it.obj.kind == FileKind.CR3 }
                                ?: error("No CR3 in the list (run step 3 first)")
                            val bytes = container.ptp.readPartial(item.obj.handle, 0, PARTIAL_HEADER_BYTES)
                            log("Partial read: ${bytes.size} bytes from ${item.obj.name}")
                            val rating = ImageHeaderParser.cr3Rating(bytes)
                            val jpegSize = ImageHeaderParser.embeddedJpeg(bytes)?.size
                            log("Rating: $rating, embeddedJpeg: ${jpegSize?.toString() ?: "none"} bytes")
                        }
                    },
                ) { Text("4. 128 KB Partial + Rating") }

                Button(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        runStep {
                            val item = container.repo.items.value.firstOrNull { it.obj.kind == FileKind.CR3 }
                                ?: error("No CR3 in the list (run step 3 first)")
                            log("Download: ${item.obj.name} (${item.obj.size} bytes)")
                            var lastBucket = -1
                            val uri = container.repo.download(context, item) { done ->
                                val pct = if (item.obj.size > 0) (done * 100 / item.obj.size).toInt() else 100
                                val bucket = (pct / 10) * 10
                                if (bucket != lastBucket) {
                                    lastBucket = bucket
                                    log("Download: $bucket%")
                                }
                            }
                            log("Download complete: $uri")
                        }
                    },
                ) { Text("5. Full download of first CR3") }

                Button(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        runStep {
                            container.ptp.disconnect()
                            log("Disconnect OK")
                        }
                    },
                ) { Text("Disconnect") }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        runStep {
                            if (!hasBlePermissions()) {
                                blePermissionLauncher.launch(blePermissions)
                                log("Permission requested — tap again")
                                return@runStep
                            }
                            log("BLE scan: searching for camera...")
                            val device = container.ble.scanForCamera()
                            foundBleDevice = device
                            if (device != null) {
                                log("Found: ${device.name} (${device.address})")
                            } else {
                                log("nothing found (pair via the camera menu!)")
                            }
                        }
                    },
                ) { Text("BLE: Scan for camera") }

                Button(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        runStep {
                            if (!hasBlePermissions()) {
                                blePermissionLauncher.launch(blePermissions)
                                log("Permission requested — tap again")
                                return@runStep
                            }
                            val device = foundBleDevice ?: run { log("scan first"); return@runStep }
                            log("Pairing + Wake: ${device.name} (${device.address})")
                            val result = container.ble.pairAndWake(device)
                            log("WakeResult: $result")
                            when (result) {
                                WakeResult.NEEDS_CONFIRMATION ->
                                    log("confirm on the camera display")
                                WakeResult.PAIRED_WAKE_SENT ->
                                    log("Wake sent — check whether the camera's Wi-Fi now appears (not guaranteed)")
                                else -> {}
                            }
                        }
                    },
                ) { Text("BLE: Pairing + Wake") }

                Spacer(modifier = Modifier.height(8.dp))
                Text("Log", style = MaterialTheme.typography.titleSmall)
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    items(logLines) { line ->
                        Text(text = line, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
