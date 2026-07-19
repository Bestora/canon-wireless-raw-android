package io.github.canonwirelessraw.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.canonwirelessraw.AppContainer
import io.github.canonwirelessraw.data.GalleryItem
import io.github.canonwirelessraw.data.Saver
import io.github.canonwirelessraw.ptp.PtpException

/**
 * The cancel signal from [io.github.canonwirelessraw.data.CameraRepository.cancel] surfaces as
 * this specific exception (PtpClient's CODE_CANCELLED = -99) out of the currently running
 * transfer. Distinguishes "user hit Abbrechen, stop the whole batch" from "this one file failed,
 * keep going with the rest".
 */
fun isCancelDownload(e: Throwable): Boolean = e is PtpException && e.step == "download" && e.code == -99

private sealed interface RowStatus {
    data object Pending : RowStatus
    data object Running : RowStatus
    data object Done : RowStatus
    data class Failed(val message: String) : RowStatus
}

/** Stable per-row holder so a progress update recomposes only this row, not the whole list. */
private class DownloadRow(val item: GalleryItem) {
    var bytesDone by mutableStateOf(0L)
    var status by mutableStateOf<RowStatus>(RowStatus.Pending)
}

/**
 * Sequential download of the items handed over from the gallery selection. The batch runs in
 * this composable's own LaunchedEffect (i.e. the screen's lifecycle scope).
 *
 * ponytail: kein Foreground-Service, Download stirbt mit dem Screen — Service nachruesten wenn
 * Nutzer es brauchen.
 */
@Composable
fun DownloadScreen(container: AppContainer, items: List<GalleryItem>, onDone: () -> Unit) {
    val context = LocalContext.current
    val repo = container.repo

    val rows = remember(items) { items.map { DownloadRow(it) } }
    val doneUris = remember { mutableStateListOf<Uri>() }
    var running by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        for (row in rows) {
            row.status = RowStatus.Running
            try {
                val uri = repo.download(context, row.item) { bytes -> row.bytesDone = bytes }
                row.status = RowStatus.Done
                doneUris.add(uri)
            } catch (e: Exception) {
                row.status = RowStatus.Failed(e.message ?: e.toString())
                if (isCancelDownload(e)) break
            }
        }
        running = false
    }

    // Consumed while the batch runs (no-op — use the Abbrechen button), so back does NOT fall
    // through to the Activity default and kill the app mid-download. Once the batch ends, back
    // returns to the gallery just like "Fertig".
    BackHandler(enabled = running) { /* blockiert waehrend Download; Abbrechen-Button nutzen */ }
    BackHandler(enabled = !running) { onDone() }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text("Download", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(rows, key = { it.item.obj.handle }) { row -> DownloadRowView(row) }
                }

                if (running) {
                    Button(onClick = { repo.cancel() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Abbrechen")
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            enabled = doneUris.isNotEmpty(),
                            onClick = { context.startActivity(Saver.shareIntent(doneUris)) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Teilen…") }
                        Button(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Fertig") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadRowView(row: DownloadRow) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(row.item.obj.name)
            when (val status = row.status) {
                RowStatus.Done -> Text("✓")
                is RowStatus.Failed -> Text("✗ ${status.message}")
                RowStatus.Pending, RowStatus.Running -> {}
            }
        }
        val size = row.item.obj.size
        LinearProgressIndicator(
            progress = { if (size > 0) (row.bytesDone.toFloat() / size).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
