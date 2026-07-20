package io.github.canonwirelessraw.data

import android.content.Context
import android.net.Uri
import io.github.canonwirelessraw.cr3.Cr3Container
import io.github.canonwirelessraw.cr3.ImageHeaderParser
import io.github.canonwirelessraw.ptp.CameraObject
import io.github.canonwirelessraw.ptp.FileKind
import io.github.canonwirelessraw.ptp.PtpPort
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** rating==null && thumbFile==null → not yet scanned. orientation is the EXIF value (1..8) used to
 *  rotate the thumbnail for display (the embedded JPEG itself carries no EXIF). */
data class GalleryItem(val obj: CameraObject, val rating: Int?, val orientation: Int, val thumbFile: File?)

/** Full-HD preview for the zoom viewer: the JPEG bytes plus its EXIF orientation (1..8). */
data class PreviewData(val jpeg: ByteArray, val orientation: Int)

/** Bytes pulled for rating + thumbnail extraction. 256 KB (not 128) because the small embedded
 *  thumbnail's offset varies with metadata size and can sit past 128 KB on some frames — too small
 *  a window left those cells blank. */
private const val HEADER_BYTES = 256 * 1024

/** Bytes pulled to extract the full-HD preview JPEG for the zoom viewer. On an R5 CR3 the
 *  ~1624×1080 PreviewImage (~256 KB) ends around 354 KB in, so 512 KB safely contains it. */
private const val PREVIEW_BYTES = 512 * 1024

/** Download chunk size (1 MiB). */
private const val DOWNLOAD_CHUNK = 1 shl 20

/** Objects fetched per batch before the gallery list is re-emitted during [CameraRepository.refreshList]. */
private const val LIST_BATCH = 25

/**
 * Central store: connects to the camera, lists images, lazily extracts per-image rating + preview
 * (cached to disk), and downloads originals. Depends on [PtpPort]/[PrefsPort] interfaces so it can
 * be driven by in-memory fakes in JVM unit tests.
 */
class CameraRepository(
    private val ptp: PtpPort,
    private val cache: MetaCache,
    private val prefs: PrefsPort,
) {
    private val _items = MutableStateFlow<List<GalleryItem>>(emptyList())

    /** Gallery in newest-first order; CR3/JPEG/HEIF only. Populated progressively by [refreshList]. */
    val items: StateFlow<List<GalleryItem>> = _items.asStateFlow()

    suspend fun connect(ip: String) {
        ptp.connect(ip = ip, port = 15740, name = "Canon Wireless RAW", guid = prefs.pairingGuid(), eosMode = true)
        prefs.lastIp = ip
    }

    /**
     * Loads the gallery progressively so the UI shows images within ~1s instead of waiting for all
     * ~1000+ per-object GetObjectInfo round-trips. Fetches handles once (fast), then pulls
     * GetObjectInfo in batches, emitting the accumulated list after each batch. Cache hits fill
     * rating/thumb immediately; misses stay lazy (see [ensureMeta]).
     *
     * Order: handles reversed = newest-first as an approximation (Canon assigns handles
     * chronologically), avoiding an up-front sort that would need every object's date first.
     */
    suspend fun refreshList() {
        _items.value = emptyList()
        // Handles reversed = fetch newest first (Canon assigns handles chronologically), so the
        // first batches the user sees are the newest photos.
        val handles = ptp.listHandles().asReversed()
        for (batch in handles.chunked(LIST_BATCH)) {
            val newItems = ArrayList<GalleryItem>(batch.size)
            for (h in batch) {
                val obj = ptp.objectInfo(h) ?: continue
                if (obj.kind == FileKind.OTHER) continue
                val meta = cache.get(obj.handle, obj.size)
                newItems.add(GalleryItem(obj, meta?.rating, meta?.orientation ?: 1, meta?.thumbFile))
            }
            // APPEND (via update, not assignment): a plain reassign would rebuild the whole list
            // from freshly-created items and clobber thumbnails/ratings that ensureMeta wrote
            // concurrently for already-visible cells. Keep it sorted newest-first by capture date
            // so ordering is exact even if handle order isn't perfectly chronological.
            _items.update { current ->
                (current + newItems).sortedByDescending { it.obj.takenAtMillis }
            }
        }
    }

    /** Reads the header for [handle] (unless already scanned), parses rating + preview, caches it. */
    suspend fun ensureMeta(handle: Int) {
        val item = _items.value.firstOrNull { it.obj.handle == handle } ?: return
        if (item.rating != null || item.thumbFile != null) return

        // A prior scan may have recorded "no rating, no thumb" — the cache is the only proof of it.
        cache.get(handle, item.obj.size)?.let {
            applyMeta(handle, it.rating, it.orientation, it.thumbFile)
            return
        }

        val header = ptp.readPartial(handle, 0, HEADER_BYTES)
        val rating = ImageHeaderParser.rating(item.obj.kind, header)
        // CR3: pull the thumbnail from the THMB box (deterministic) + orientation from CMT1 (the
        // embedded JPEG has no EXIF). JPEG/HEIF: marker scan, orientation left as normal.
        val thumb: ByteArray?
        val orientation: Int
        if (item.obj.kind == FileKind.CR3) {
            thumb = Cr3Container.thumbnailJpeg(header)
            orientation = Cr3Container.orientation(header)
        } else {
            thumb = ImageHeaderParser.embeddedJpeg(header)
            orientation = 1
        }
        val meta = cache.put(handle, item.obj.size, rating, orientation, thumb)
        applyMeta(handle, meta.rating, meta.orientation, meta.thumbFile)
    }

    /** Loads a sharp preview (the CR3's embedded full-HD PreviewImage) plus its display orientation
     *  for the zoom viewer, or null if none can be extracted. */
    suspend fun loadPreview(handle: Int): PreviewData? = withContext(Dispatchers.IO) {
        val buf = ptp.readPartial(handle, 0, PREVIEW_BYTES)
        // CR3: preview from the eaf42b5e uuid box + orientation from CMT1. Others: marker scan.
        val jpeg = Cr3Container.previewJpeg(buf)
            ?: ImageHeaderParser.largestEmbeddedJpeg(buf)
            ?: return@withContext null
        PreviewData(jpeg, Cr3Container.orientation(buf))
    }

    /** Sequentially fills meta for every currently-unscanned item; reports (done, total) after each. */
    suspend fun scanAllMeta(onProgress: (done: Int, total: Int) -> Unit) {
        val pending = _items.value.filter { it.rating == null && it.thumbFile == null }
        var done = 0
        for (item in pending) {
            try {
                ensureMeta(item.obj.handle)
            } catch (e: Exception) {
                // ponytail: one corrupt/unreadable header must not abort the scan. println, not
                // android.util.Log — Log is unmocked (crashes) in plain JVM unit tests.
                println("scanAllMeta: skipping handle ${item.obj.handle}: ${e.message}")
            }
            done++
            onProgress(done, pending.size)
        }
    }

    /** Saves the original to Pictures/CanonRAW via MediaStore; Saver deletes the partial on failure. */
    suspend fun download(context: Context, item: GalleryItem, onProgress: (Long) -> Unit): Uri =
        withContext(Dispatchers.IO) {
            Saver.saveDownload(context, item.obj.name, Saver.mimeFor(item.obj.kind)) { out ->
                // Saver's body is non-suspend; bridge to the suspend transfer (already on IO).
                runBlocking { ptp.downloadTo(item.obj, out, DOWNLOAD_CHUNK, onProgress) }
            }
        }

    fun cancel() = ptp.cancelIo()

    suspend fun disconnect() = ptp.disconnect()

    /** Replaces the matching item with an immutable copy so collectors see a fresh list. */
    private fun applyMeta(handle: Int, rating: Int?, orientation: Int, thumbFile: File?) {
        _items.update { list ->
            list.map {
                if (it.obj.handle == handle) it.copy(rating = rating, orientation = orientation, thumbFile = thumbFile) else it
            }
        }
    }
}
