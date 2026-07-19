package io.github.canonwirelessraw.data

import android.content.Context
import android.net.Uri
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

/** rating==null && thumbFile==null → not yet scanned. */
data class GalleryItem(val obj: CameraObject, val rating: Int?, val thumbFile: File?)

/** Bytes of the header pulled for rating/preview extraction. */
private const val HEADER_BYTES = 128 * 1024

/** Download chunk size (1 MiB). */
private const val DOWNLOAD_CHUNK = 1 shl 20

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

    /** Gallery, sorted takenAtMillis DESC; CR3/JPEG/HEIF only. */
    val items: StateFlow<List<GalleryItem>> = _items.asStateFlow()

    suspend fun connect(ip: String) {
        ptp.connect(ip = ip, port = 15740, name = "Canon Wireless RAW", guid = prefs.pairingGuid(), eosMode = true)
        prefs.lastIp = ip
    }

    /** Lists objects, drops non-image kinds, sorts newest-first, and fills meta from cache hits. */
    suspend fun refreshList() {
        _items.value = ptp.listObjects()
            .filter { it.kind != FileKind.OTHER }
            .sortedByDescending { it.takenAtMillis }
            .map { obj ->
                val meta = cache.get(obj.handle, obj.size)
                GalleryItem(obj, meta?.rating, meta?.thumbFile)
            }
    }

    /** Reads the header for [handle] (unless already scanned), parses rating + preview, caches it. */
    suspend fun ensureMeta(handle: Int) {
        val item = _items.value.firstOrNull { it.obj.handle == handle } ?: return
        if (item.rating != null || item.thumbFile != null) return

        // A prior scan may have recorded "no rating, no thumb" — the cache is the only proof of it.
        cache.get(handle, item.obj.size)?.let {
            applyMeta(handle, it.rating, it.thumbFile)
            return
        }

        val header = ptp.readPartial(handle, 0, HEADER_BYTES)
        val rating = ImageHeaderParser.rating(item.obj.kind, header)
        val thumb = ImageHeaderParser.embeddedJpeg(header)
        val meta = cache.put(handle, item.obj.size, rating, thumb)
        applyMeta(handle, meta.rating, meta.thumbFile)
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
            Saver.saveToPictures(context, item.obj.name, Saver.mimeFor(item.obj.kind)) { out ->
                // Saver's body is non-suspend; bridge to the suspend transfer (already on IO).
                runBlocking { ptp.downloadTo(item.obj, out, DOWNLOAD_CHUNK, onProgress) }
            }
        }

    fun cancel() = ptp.cancelIo()

    suspend fun disconnect() = ptp.disconnect()

    /** Replaces the matching item with an immutable copy so collectors see a fresh list. */
    private fun applyMeta(handle: Int, rating: Int?, thumbFile: File?) {
        _items.update { list ->
            list.map { if (it.obj.handle == handle) it.copy(rating = rating, thumbFile = thumbFile) else it }
        }
    }
}
