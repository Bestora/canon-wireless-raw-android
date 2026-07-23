package de.bestora.canonwirelessrawandroid.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import de.bestora.canonwirelessrawandroid.AppContainer
import de.bestora.canonwirelessrawandroid.data.GalleryItem
import de.bestora.canonwirelessrawandroid.ptp.FileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Exact-rating chips; multi-select. An empty selection means "All" (no rating filter). */
private val RATING_STEPS = 1..5

/** Empty [ratings] shows everything (incl. unrated); otherwise exact-match on the selected stars. */
fun filterByRating(items: List<GalleryItem>, ratings: Set<Int>): List<GalleryItem> =
    if (ratings.isEmpty()) items else items.filter { it.rating != null && it.rating in ratings }

private fun FileKind.badge(): String = when (this) {
    FileKind.CR3 -> "CR3"
    FileKind.JPEG -> "JPG"
    FileKind.HEIF -> "HIF"
    FileKind.OTHER -> "?"
}

/**
 * Main gallery: grid of camera images with rating filter and multi-select for download.
 * Task 12 wires this into navigation (currently unreferenced by MainActivity).
 */
@Composable
fun GalleryScreen(
    container: AppContainer,
    onOpen: (GalleryItem) -> Unit,
    onDownload: (List<GalleryItem>) -> Unit,
) {
    // Screen stays composed while the viewer overlays it, so this state persists across the trip.
    val gridState = rememberLazyGridState()
    val repo = container.repo
    val scope = rememberCoroutineScope()
    val items by repo.items.collectAsState()

    var error by remember { mutableStateOf<String?>(null) }
    var ratingFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var scanning by remember { mutableStateOf(false) }
    var scanDone by remember { mutableIntStateOf(0) }
    var scanTotal by remember { mutableIntStateOf(0) }

    fun refresh() {
        error = null
        scope.launch {
            runCatching { repo.refreshList() }.onFailure { error = it.message ?: it.toString() }
        }
    }

    // Load once on first entry. Returning from the viewer re-composes this screen, but the list is
    // already populated then — re-fetching would clear it (scroll jumps to top) and re-pull 1000+
    // GetObjectInfo. Manual refresh stays available via the refresh button.
    LaunchedEffect(Unit) { if (repo.items.value.isEmpty()) refresh() }

    // Empty selection ("All") also shows not-yet-rated items; ★N hides anything without a rating.
    val filtered = remember(items, ratingFilter) { filterByRating(items, ratingFilter) }

    // Derived from the live items list, not just the handle set, so a refreshList that drops/changes
    // objects can't leave the selection (and the download button's count) pointing at stale items.
    val selectedItems = remember(items, selected) { items.filter { it.obj.handle in selected } }

    AppTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = { refresh() }) { Text("⟳") }
                    Button(
                        enabled = !scanning,
                        onClick = {
                            error = null
                            scanning = true
                            scanDone = 0
                            scanTotal = 0
                            scope.launch {
                                runCatching {
                                    repo.scanAllMeta { done, total ->
                                        scanDone = done
                                        scanTotal = total
                                    }
                                }.onFailure { error = it.message ?: it.toString() }
                                scanning = false
                            }
                        },
                    ) { Text("Scan ratings") }
                    if (scanning) Text("$scanDone/$scanTotal")
                }
                if (scanning) {
                    LinearProgressIndicator(
                        progress = { if (scanTotal > 0) scanDone.toFloat() / scanTotal else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(
                        selected = ratingFilter.isEmpty(),
                        onClick = { ratingFilter = emptySet() },
                        label = { Text("All") },
                    )
                    RATING_STEPS.forEach { step ->
                        FilterChip(
                            selected = step in ratingFilter,
                            onClick = {
                                ratingFilter =
                                    if (step in ratingFilter) ratingFilter - step else ratingFilter + step
                            },
                            label = { Text("★$step") },
                        )
                    }
                    if (filtered.isNotEmpty()) {
                        TextButton(onClick = { selected = filtered.map { it.obj.handle }.toSet() }) {
                            Text("Select all (${filtered.size})")
                        }
                    }
                }

                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (ratingFilter.isEmpty()) "No images"
                            else "No images with this rating — run “Scan ratings” first?",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                } else LazyVerticalGrid(
                    columns = GridCells.Adaptive(96.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    items(filtered, key = { it.obj.handle }) { item ->
                        LaunchedEffect(item.obj.handle) {
                            runCatching { repo.ensureMeta(item.obj.handle) }
                        }
                        GalleryCell(
                            item = item,
                            selected = item.obj.handle in selected,
                            // Tap opens the zoom viewer; long-press toggles download selection.
                            // If a selection is already active, tap toggles too (so you can keep
                            // multi-selecting without long-pressing every cell).
                            onClick = {
                                val handle = item.obj.handle
                                if (selected.isNotEmpty()) {
                                    selected = if (handle in selected) selected - handle else selected + handle
                                } else {
                                    onOpen(item)
                                }
                            },
                            onLongClick = {
                                val handle = item.obj.handle
                                selected = if (handle in selected) selected - handle else selected + handle
                            },
                        )
                    }
                }

                if (selectedItems.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(onClick = { onDownload(selectedItems) }) {
                            Text("Download ${selectedItems.size}")
                        }
                        TextButton(onClick = { selected = emptySet() }) { Text("Clear selection") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryCell(item: GalleryItem, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = item.thumbFile, key2 = item.orientation) {
        value = withContext(Dispatchers.IO) {
            item.thumbFile?.let { file ->
                runCatching {
                    BitmapFactory.decodeFile(file.path)?.let { rotateForOrientation(it, item.orientation).asImageBitmap() }
                }.getOrNull()
            }
        }
    }

    val shape = RoundedCornerShape(8.dp)
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .let { if (selected) it.border(3.dp, accent, shape) else it },
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = item.obj.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(Color.DarkGray), contentAlignment = Alignment.Center) {
                Text(item.obj.kind.badge(), color = Color.White)
            }
        }

        Text(
            item.obj.kind.badge(),
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )

        val rating = item.rating
        if (rating != null && rating > 0) {
            Text(
                "★$rating",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }

        if (selected) {
            Box(modifier = Modifier.fillMaxSize().background(accent.copy(alpha = 0.35f)))
            Text("✓", color = Color.White, modifier = Modifier.align(Alignment.Center))
        }
    }
}
