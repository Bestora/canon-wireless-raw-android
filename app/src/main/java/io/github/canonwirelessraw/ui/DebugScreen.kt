package io.github.canonwirelessraw.ui

import android.Manifest
import android.content.pm.PackageManager
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

    val blePermissions = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
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
            runCatching { action() }.onFailure { e -> log("FEHLER: $e") }
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
                    label = { Text("Kamera-IP") },
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
                            log("EOS Remote/Event Mode: setzen...")
                            val result = container.ptp.eosMode()
                            log("eosSetRemoteMode rc=${result.remoteModeCode}")
                            log("eosSetEventMode rc=${result.eventModeCode}")
                            if (result.remoteModeCode == 0 && result.eventModeCode == 0) {
                                log("EOS Mode OK")
                            } else {
                                log("EOS Mode: mindestens ein Schritt fehlgeschlagen")
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
                            log("Liste geladen: ${items.size} Objekte")
                            items.take(5).forEach {
                                log(" - ${it.obj.name} (${dateFmt.format(Date(it.obj.takenAtMillis))})")
                            }
                        }
                    },
                ) { Text("3. Liste laden") }

                Button(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        runStep {
                            val item = container.repo.items.value.firstOrNull { it.obj.kind == FileKind.CR3 }
                                ?: error("Kein CR3 in der Liste (erst Schritt 3 ausführen)")
                            val bytes = container.ptp.readPartial(item.obj.handle, 0, PARTIAL_HEADER_BYTES)
                            log("Partial gelesen: ${bytes.size} Bytes von ${item.obj.name}")
                            val rating = ImageHeaderParser.cr3Rating(bytes)
                            val jpegSize = ImageHeaderParser.embeddedJpeg(bytes)?.size
                            log("Rating: $rating, embeddedJpeg: ${jpegSize?.toString() ?: "keins"} Bytes")
                        }
                    },
                ) { Text("4. 128 KB Partial + Rating") }

                Button(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        runStep {
                            val item = container.repo.items.value.firstOrNull { it.obj.kind == FileKind.CR3 }
                                ?: error("Kein CR3 in der Liste (erst Schritt 3 ausführen)")
                            log("Download: ${item.obj.name} (${item.obj.size} Bytes)")
                            var lastBucket = -1
                            val uri = container.repo.download(context, item) { done ->
                                val pct = if (item.obj.size > 0) (done * 100 / item.obj.size).toInt() else 100
                                val bucket = (pct / 10) * 10
                                if (bucket != lastBucket) {
                                    lastBucket = bucket
                                    log("Download: $bucket%")
                                }
                            }
                            log("Download fertig: $uri")
                        }
                    },
                ) { Text("5. Voll-Download erste CR3") }

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
                                log("Berechtigung angefragt — erneut tippen")
                                return@runStep
                            }
                            log("BLE-Scan: suche Kamera...")
                            val device = container.ble.scanForCamera()
                            foundBleDevice = device
                            if (device != null) {
                                log("Gefunden: ${device.name} (${device.address})")
                            } else {
                                log("nichts gefunden (im Kameramenü koppeln!)")
                            }
                        }
                    },
                ) { Text("BLE: Kamera suchen") }

                Button(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        runStep {
                            if (!hasBlePermissions()) {
                                blePermissionLauncher.launch(blePermissions)
                                log("Berechtigung angefragt — erneut tippen")
                                return@runStep
                            }
                            val device = foundBleDevice ?: run { log("erst suchen"); return@runStep }
                            log("Pairing + Wake: ${device.name} (${device.address})")
                            val result = container.ble.pairAndWake(device)
                            log("WakeResult: $result")
                            when (result) {
                                WakeResult.NEEDS_CONFIRMATION ->
                                    log("am Kamera-Display bestätigen")
                                WakeResult.PAIRED_WAKE_SENT ->
                                    log("Wake gesendet — prüfe, ob das Kamera-WLAN jetzt erscheint (NICHT garantiert)")
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
