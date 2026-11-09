package de.bestora.canonwirelessrawandroid.ui

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
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.bestora.canonwirelessrawandroid.AppContainer
import de.bestora.canonwirelessrawandroid.data.GalleryItem
import de.bestora.canonwirelessrawandroid.data.Saver
import de.bestora.canonwirelessrawandroid.ptp.PtpException

/** "User hit Cancel, stop the whole batch" as opposed to "this one file failed, keep going". */
fun isCancelDownload(e: Throwable): Boolean = e is PtpException && e.cancelled

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
 * Sequential download of the items handed over from the gallery or viewer. The batch runs in this
 * composable's LaunchedEffect; the camera session's foreground service keeps the process (and the
 * camera Wi-Fi) alive while the user is in another app. With [openInEditor] the downloaded RAWs go
 * straight to the remembered editor once the batch is done.
 */
@Composable
fun DownloadScreen(
    container: AppContainer,
    items: List<GalleryItem>,
    openInEditor: Boolean,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val repo = container.repo
    val editor = container.editor

    val rows = remember(items) { items.map { DownloadRow(it) } }
    val doneUris = remember { mutableStateListOf<Uri>() }
    var running by remember { mutableStateOf(true) }
    var cancelRequested by remember { mutableStateOf(false) }
    val editorName = rememberEditorName(editor)
    val cancel = {
        cancelRequested = true
        repo.cancel()
    }
    val mime = Saver.mimeFor(items.first().obj.kind)
    fun openEditor(pickApp: Boolean) = editor.open(doneUris.toList(), mime, pickApp)

    LaunchedEffect(Unit) {
        for (row in rows) {
            // A cancel tapped between two files has no transfer to interrupt; stop here.
            if (cancelRequested) break
            row.status = RowStatus.Running
            try {
                val uri = repo.download(context, row.item) { bytes -> row.bytesDone = bytes }
                row.status = RowStatus.Done
                doneUris.add(uri)
            } catch (e: Exception) {
                row.status = RowStatus.Failed(e.message ?: e.toString())
                // The cancel latches the PTP session; the next camera call reconnects on its own.
                if (isCancelDownload(e)) break
            }
        }
        running = false
        if (openInEditor && !cancelRequested && doneUris.isNotEmpty()) openEditor(pickApp = false)
    }

    // Leaving the screen mid-batch fires the kill-switch so the in-flight native transfer aborts
    // instead of running on as a zombie inside runBlocking. Reads current `running`.
    DisposableEffect(Unit) { onDispose { if (running) cancel() } }

    // Back is consumed while the batch runs (use Cancel), so it doesn't fall through and close the
    // app mid-download. Once the batch ends, back returns to the gallery like "Done".
    BackHandler(enabled = running) { /* blockiert waehrend Download; Abbrechen-Button nutzen */ }
    BackHandler(enabled = !running) { onDone() }

    val saved = doneUris.size
    AppTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    when {
                        running -> "Downloading ${items.size} ${if (items.size == 1) "photo" else "photos"}"
                        saved == items.size -> "$saved ${if (saved == 1) "photo" else "photos"} saved"
                        else -> "$saved of ${items.size} saved"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    "In Downloads on this phone",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Graphite,
                )
                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(rows, key = { it.item.obj.handle }) { row -> DownloadRowView(row) }
                }

                if (running) {
                    SecondaryButton("Cancel", cancel, Modifier.fillMaxWidth())
                } else {
                    if (saved > 0) {
                        PrimaryButton(editorName?.let { "Open in $it" } ?: "Open in…", { openEditor(pickApp = false) }, Modifier.fillMaxWidth())
                        // Without a remembered app the main button already shows the picker.
                        if (editorName != null) {
                            TextButton(
                                onClick = { openEditor(pickApp = true) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Open in another app…", color = Palette.Graphite) }
                        }
                    }
                    SecondaryButton("Back to photos", onDone, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun DownloadRowView(row: DownloadRow) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(row.item.obj.name, style = MaterialTheme.typography.bodyLarge)
            when (val status = row.status) {
                RowStatus.Done -> Text("Saved", color = Palette.Graphite, style = MaterialTheme.typography.bodyMedium)
                is RowStatus.Failed -> Text("Failed", color = Palette.Amber, style = MaterialTheme.typography.bodyMedium)
                RowStatus.Pending, RowStatus.Running -> {}
            }
        }
        (row.status as? RowStatus.Failed)?.let {
            Text(it.message, color = Palette.Amber, style = MaterialTheme.typography.bodySmall)
        }
        val size = row.item.obj.size
        LinearProgressIndicator(
            progress = { if (size > 0) (row.bytesDone.toFloat() / size).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            color = Palette.Paper,
            trackColor = Palette.Raised,
        )
    }
}
