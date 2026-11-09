package de.bestora.canonwirelessrawandroid.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import de.bestora.canonwirelessrawandroid.AppContainer
import de.bestora.canonwirelessrawandroid.data.GalleryItem
import de.bestora.canonwirelessrawandroid.data.Link
import de.bestora.canonwirelessrawandroid.ptp.FileKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Exact-rating chips; multi-select. An empty selection means "All" (no rating filter). */
private val RATING_STEPS = 1..5

/** Minimum cell width; the column count follows from the screen width. */
private val CELL_MIN = 96.dp

/**
 * Grid cells for the unfiltered gallery; null is an empty cell. Photos shot while connected
 * ([liveCount], newest first) fill a top row of their own, so a new photo never shifts the rows
 * below it: [n2, n1, -, -] then [n5, -, -, -] [n4, n3, n2, n1].
 */
fun galleryCells(items: List<GalleryItem>, liveCount: Int, columns: Int): List<GalleryItem?> {
    val inTopRow = liveCount.coerceAtMost(items.size) % columns
    if (inTopRow == 0) return items
    return items.take(inTopRow) + List(columns - inTopRow) { null } + items.drop(inTopRow)
}

/** One cell of the gallery grid. */
sealed interface GridEntry {
    val key: String
    /** Day header spanning the whole row. */
    data class Day(val label: String) : GridEntry { override val key get() = "d$label" }
    data class Photo(val item: GalleryItem) : GridEntry { override val key get() = "h${item.obj.handle}" }
    /** Empty cell keeping live photos in a top row of their own (see [galleryCells]). */
    data class Gap(override val key: String) : GridEntry
}

/** Header per day (items are newest first); live photos get their own top row inside the newest day. */
fun galleryEntries(items: List<GalleryItem>, liveCount: Int, columns: Int, dayOf: (Long) -> String): List<GridEntry> {
    val out = ArrayList<GridEntry>(items.size + 64)
    var start = 0
    while (start < items.size) {
        val label = dayOf(items[start].obj.takenAtMillis)
        var end = start
        while (end < items.size && dayOf(items[end].obj.takenAtMillis) == label) end++
        out += GridEntry.Day(label)
        val day = items.subList(start, end)
        val live = if (start == 0) liveCount.coerceAtMost(day.size) else 0
        galleryCells(day, live, columns).forEachIndexed { i, cell ->
            out += cell?.let { GridEntry.Photo(it) } ?: GridEntry.Gap("gap$label$i")
        }
        start = end
    }
    return out
}

/** Empty [ratings] shows everything (incl. unrated); otherwise exact-match on the selected stars. */
fun filterByRating(items: List<GalleryItem>, ratings: Set<Int>): List<GalleryItem> =
    if (ratings.isEmpty()) items else items.filter { it.rating != null && it.rating in ratings }

/**
 * The contact sheet: photos newest first, grouped by day, marked with a grease-pencil loop for
 * download or for the editor. Rating filter and "Scan ratings" sit above; the marked frames' actions
 * sit at the bottom, where a thumb reaches them.
 */
@Composable
fun GalleryScreen(
    container: AppContainer,
    onOpen: (GalleryItem) -> Unit,
    onDownload: (items: List<GalleryItem>, openInEditor: Boolean) -> Unit,
) {
    // Screen stays composed while the viewer overlays it, so this state persists across the trip.
    val gridState = rememberLazyGridState()
    val repo = container.repo
    val scope = rememberCoroutineScope()
    val items by repo.items.collectAsState()
    val liveCount by repo.liveCount.collectAsState()
    val link by repo.link.collectAsState()
    var reconnectStatus by remember { mutableStateOf<String?>(null) }
    var reconnecting by remember { mutableStateOf(false) }

    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var ratingFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    val scanning = scanJob != null
    var scanDone by remember { mutableIntStateOf(0) }
    var scanTotal by remember { mutableIntStateOf(0) }
    val editorName = rememberEditorName(container.editor)

    fun refresh() {
        error = null
        loading = true
        scope.launch {
            runCatching { repo.refreshList() }.onFailure { error = it.message ?: it.toString() }
            loading = false
        }
    }

    fun toggleScan() {
        scanJob?.let { it.cancel(); return }
        error = null
        scanDone = 0
        scanTotal = 0
        scanJob = scope.launch {
            try {
                repo.scanAllMeta { done, total ->
                    scanDone = done
                    scanTotal = total
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                scanJob = null
            }
        }
    }

    fun reconnect() {
        if (reconnecting) return
        error = null
        reconnecting = true
        scope.launch {
            runCatching {
                container.connector.reconnect(status = { reconnectStatus = it }, onRetry = { reconnectStatus = connectStatus(it) })
            }.onFailure { e -> error = connectHint(e) ?: e.message ?: e.toString() }
            reconnectStatus = null
            reconnecting = false
        }
    }

    // Load once on first entry. Returning from the viewer re-composes this screen, but the list is
    // already populated then; re-fetching would clear it. The reload button stays available.
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
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(repo.model?.removePrefix("Canon ") ?: "Camera", style = MaterialTheme.typography.titleLarge)
                        val status = when (link) {
                            Link.RECONNECTING -> reconnectStatus ?: "Reconnecting…"
                            Link.LOST -> "Connection lost"
                            else -> if (loading) "${items.size} photos, loading…" else "${items.size} photos"
                        }
                        Text(
                            status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (link == Link.LOST) Palette.Amber else Palette.Graphite,
                        )
                    }
                    TextButton(onClick = { toggleScan() }) {
                        Text(if (scanning) "Stop $scanDone/$scanTotal" else "Scan ratings", color = Palette.Paper)
                    }
                    IconButton(onClick = { refresh() }, enabled = !loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload list", tint = Palette.Graphite)
                    }
                }
                when {
                    scanning -> LinearProgressIndicator(
                        progress = { if (scanTotal > 0) scanDone.toFloat() / scanTotal else 0f },
                        modifier = Modifier.fillMaxWidth(),
                        color = Palette.Paper,
                        trackColor = Palette.Raised,
                    )
                    loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Palette.Graphite, trackColor = Palette.Raised)
                }

                if (link == Link.LOST) {
                    Row(
                        modifier = Modifier.fillMaxWidth().background(Palette.Sheet).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (reconnecting) reconnectStatus ?: "Reconnecting…" else "The camera stopped answering.",
                            color = if (reconnecting) Palette.Paper else Palette.Amber,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (reconnecting) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Palette.Paper, strokeWidth = 2.dp)
                        } else {
                            TextButton(onClick = { reconnect() }) { Text("Reconnect", color = Palette.Paper) }
                        }
                    }
                }
                error?.let {
                    Text(it, color = Palette.Amber, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    RatingChip("All", ratingFilter.isEmpty()) { ratingFilter = emptySet() }
                    RATING_STEPS.forEach { step ->
                        RatingChip("★ $step", step in ratingFilter) {
                            ratingFilter = if (step in ratingFilter) ratingFilter - step else ratingFilter + step
                        }
                    }
                }

                if (filtered.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            when {
                                loading -> "Reading the card…"
                                ratingFilter.isEmpty() -> "No photos on the card."
                                else -> "No photos with this rating yet. Ratings show up once a photo has been " +
                                    "looked at; Scan ratings reads them all."
                            },
                            color = Palette.Graphite,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                } else BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    val columns = maxOf(3, (maxWidth / CELL_MIN).toInt())
                    // Live gaps only make sense in the full list; a rating filter re-flows anyway.
                    val entries = remember(filtered, liveCount, columns, ratingFilter.isEmpty()) {
                        galleryEntries(filtered, if (ratingFilter.isEmpty()) liveCount else 0, columns, ::dayLabel)
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        state = gridState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 1.dp),
                    ) {
                        items(
                            entries,
                            key = { it.key },
                            span = { if (it is GridEntry.Day) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                        ) { entry ->
                            when (entry) {
                                is GridEntry.Day -> Text(
                                    entry.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Palette.Graphite,
                                    modifier = Modifier.padding(start = 11.dp, top = 18.dp, bottom = 8.dp),
                                )
                                is GridEntry.Gap -> Box(modifier = Modifier.aspectRatio(1f))
                                is GridEntry.Photo -> {
                                    val item = entry.item
                                    // Keyed on the link too, so cells that failed while the connection was
                                    // down load once it is back.
                                    LaunchedEffect(item.obj.handle, link == Link.CONNECTED) {
                                        runCatching { repo.ensureMeta(item.obj.handle) }
                                    }
                                    val handle = item.obj.handle
                                    fun toggle() {
                                        selected = if (handle in selected) selected - handle else selected + handle
                                    }
                                    // Tap opens the photo; long-press marks it. While anything is marked, tap marks too.
                                    GalleryCell(
                                        item = item,
                                        marked = handle in selected,
                                        onClick = { if (selected.isNotEmpty()) toggle() else onOpen(item) },
                                        onLongClick = { toggle() },
                                    )
                                }
                            }
                        }
                    }
                    DateScrubber(gridState, entries, modifier = Modifier.align(Alignment.CenterEnd))
                }

                if (selectedItems.isNotEmpty()) {
                    Column(modifier = Modifier.fillMaxWidth().background(Palette.Sheet).padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${selectedItems.size} selected",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { selected = filtered.map { it.obj.handle }.toSet() }) {
                                Text("Select all", color = Palette.Graphite)
                            }
                            TextButton(onClick = { selected = emptySet() }) { Text("Clear", color = Palette.Graphite) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            SecondaryButton("Download", { onDownload(selectedItems, false) }, Modifier.weight(1f))
                            PrimaryButton(editorName?.let { "Open in $it" } ?: "Open in…", { onDownload(selectedItems, true) }, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RatingChip(label: String, active: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = active,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            labelColor = Palette.Graphite,
            selectedContainerColor = Palette.Raised,
            selectedLabelColor = Palette.Paper,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = active,
            borderColor = Palette.Raised,
            selectedBorderColor = Palette.Raised,
        ),
    )
}

/** Frame number as a contact sheet prints it: the digits of "0U7A8405.CR3" → "8405". */
private fun frameNumber(name: String): String = name.substringBeforeLast('.').takeLastWhile { it.isDigit() }.ifEmpty { name }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryCell(item: GalleryItem, marked: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = item.thumbFile, key2 = item.orientation) {
        value = withContext(Dispatchers.IO) {
            item.thumbFile?.let { file ->
                runCatching {
                    BitmapFactory.decodeFile(file.path)?.let { rotateForOrientation(it, item.orientation).asImageBitmap() }
                }.getOrNull()
            }
        }
    }
    val loop = remember(item.obj.handle) { greasePencilLoop(item.obj.handle) }

    Box(
        modifier = Modifier
            .padding(1.dp)
            .aspectRatio(1f)
            .background(Palette.Sheet)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .drawWithContent {
                drawContent()
                if (marked) {
                    val path = Path()
                    loop.forEachIndexed { i, p ->
                        val x = p.x * size.width
                        val y = p.y * size.height
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, Palette.GreasePencil, style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            },
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bitmap = bmp, contentDescription = item.obj.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(
                frameNumber(item.obj.name),
                style = MaterialTheme.typography.titleSmall,
                color = Palette.Graphite,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        // CR3 is the normal case; only say so when a frame is something else.
        if (item.obj.kind == FileKind.JPEG || item.obj.kind == FileKind.HEIF) {
            CellLabel(if (item.obj.kind == FileKind.JPEG) "JPG" else "HIF", Modifier.align(Alignment.TopEnd))
        }
        val rating = item.rating
        if (rating != null && rating > 0) CellLabel("★".repeat(rating), Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun CellLabel(text: String, modifier: Modifier) {
    Text(
        text,
        color = Palette.Paper,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .padding(4.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/**
 * A hand-drawn loop around the frame, in unit coordinates: an ellipse with a little wobble that
 * overshoots its start, like a china marker on a contact sheet. Seeded by the handle so a frame
 * keeps its loop across recompositions.
 */
private fun greasePencilLoop(seed: Int): List<Offset> {
    val rnd = Random(seed)
    val start = rnd.nextDouble(0.0, 2 * PI)
    val sweep = 2 * PI * 1.12
    val steps = 48
    return List(steps + 1) { i ->
        val t = start + sweep * i / steps
        val r = 1.0 + (rnd.nextDouble() - 0.5) * 0.035
        Offset(
            (0.5 + 0.44 * r * cos(t)).toFloat(),
            (0.5 + 0.44 * r * sin(t) + 0.012 * i / steps).toFloat(),
        )
    }
}

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)

/** "Sunday, 9 November 2026" in the phone's language. */
private fun dayLabel(millis: Long): String =
    if (millis <= 0) "Unknown date" else Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().format(DAY_FORMAT)

/** Show the scrubber only once there is enough to scroll through. */
private const val SCRUBBER_MIN_ENTRIES = 60

/**
 * Drag handle on the right edge, like Google Photos: dragging jumps through the whole gallery
 * (proportional to the number of cells, so busy days get more track) and shows the day.
 */
@Composable
private fun DateScrubber(gridState: LazyGridState, entries: List<GridEntry>, modifier: Modifier = Modifier) {
    if (entries.size < SCRUBBER_MIN_ENTRIES) return
    val scope = rememberCoroutineScope()
    // Day of every entry, so the bubble is a lookup while dragging.
    val dayAt = remember(entries) {
        var current = ""
        entries.map { e -> (e as? GridEntry.Day)?.label?.also { current = it } ?: current }
    }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val last = entries.lastIndex.coerceAtLeast(1)
    val fraction = if (dragging) dragFraction else gridState.firstVisibleItemIndex.toFloat() / last

    BoxWithConstraints(modifier = modifier.fillMaxHeight().width(SCRUBBER_TOUCH_WIDTH)) {
        val trackPx = constraints.maxHeight.toFloat()
        val thumbPx = with(LocalDensity.current) { SCRUBBER_THUMB_HEIGHT.toPx() }
        fun jumpTo(y: Float) {
            dragFraction = ((y - thumbPx / 2) / (trackPx - thumbPx)).coerceIn(0f, 1f)
            val index = (dragFraction * last).toInt()
            scope.launch { gridState.scrollToItem(index) }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(entries) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true; jumpTo(it.y) },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onVerticalDrag = { change, _ -> jumpTo(change.position.y) },
                    )
                },
        )
        val thumbY = with(LocalDensity.current) { (fraction * (trackPx - thumbPx)).toDp() }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = thumbY)
                .padding(end = 4.dp)
                .size(width = 6.dp, height = SCRUBBER_THUMB_HEIGHT)
                .background(Palette.Paper.copy(alpha = if (dragging) 1f else 0.55f), RoundedCornerShape(3.dp)),
        )
        if (dragging) {
            Text(
                dayAt[(dragFraction * last).toInt().coerceIn(0, entries.lastIndex)],
                color = Palette.Paper,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-24).dp, y = thumbY)
                    .wrapContentWidth(unbounded = true, align = Alignment.End)
                    .background(Palette.Raised, RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

private val SCRUBBER_TOUCH_WIDTH = 28.dp
private val SCRUBBER_THUMB_HEIGHT = 44.dp

