package de.bestora.canonwirelessrawandroid.data

import android.content.Context
import android.net.Uri
import de.bestora.canonwirelessrawandroid.cr3.Cr3Container
import de.bestora.canonwirelessrawandroid.cr3.ImageHeaderParser
import de.bestora.canonwirelessrawandroid.ptp.CameraObject
import de.bestora.canonwirelessrawandroid.ptp.DeviceInfo
import de.bestora.canonwirelessrawandroid.ptp.FileKind
import de.bestora.canonwirelessrawandroid.ptp.PtpException
import de.bestora.canonwirelessrawandroid.ptp.PtpPort
import java.io.File
import java.io.OutputStream
import de.bestora.canonwirelessrawandroid.ptp.REMOTE_MODE_PC
import de.bestora.canonwirelessrawandroid.ptp.addedObjectHandles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

/** Save the object list every 20 batches (500 objects), so an interrupted first load keeps its progress. */
private const val SAVE_EVERY_BATCHES = 20

/** How long [CameraRepository.connect] keeps retrying by default: long enough to notice the
 *  prompt on the camera and press OK. */
const val PAIRING_WINDOW_MS = 60_000L

/** Silent reconnect after a dropped session. A known GUID needs no confirmation on the camera. */
private const val RECONNECT_WINDOW_MS = 8_000L

private const val RETRY_DELAY_MS = 1_000L

/** How often the session loop asks the camera for events (new photos and the like). */
private const val POLL_MS = 1_000L

/** Ping every 5th poll: Canon's mobile SDK pings every 5 s (CMobilePtpHelper::DispatchPingCommand). */
private const val PING_EVERY_POLLS = 5

/** PTP/IP name for the EOS Utility pairing path; cameras paired that way know this phone by it. */
const val EOS_UTILITY_NAME = "Canon Wireless RAW"

enum class Link { DISCONNECTED, CONNECTED, RECONNECTING, LOST }

/** Thrown without touching the camera while [Link.LOST]: the silent reconnect already failed. */
class ConnectionLostException(cause: Throwable? = null) : Exception("Connection to the camera lost", cause)

/** One photo, whichever card it is on: dual-slot bodies recording to both cards report it twice,
 *  with a different handle per card. */
private val CameraObject.shot get() = "$name/$size"

/** Where and how the current session was opened, so a reconnect opens it the same way. */
private data class Target(val ip: String, val name: String, val remoteMode: Int)

/**
 * Central store: connects to the camera, lists images, lazily extracts per-image rating + preview
 * (cached to disk), and downloads originals. Depends on [PtpPort]/[PrefsPort] interfaces so it can
 * be driven by in-memory fakes in JVM unit tests.
 */
class CameraRepository(
    private val ptp: PtpPort,
    private val cache: MetaCache,
    private val prefs: PrefsPort,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val lists: ObjectListCache? = null,
) {
    private val _items = MutableStateFlow<List<GalleryItem>>(emptyList())

    /** Gallery in newest-first order; CR3/JPEG/HEIF only. Populated progressively by [refreshList]. */
    val items: StateFlow<List<GalleryItem>> = _items.asStateFlow()

    private val _link = MutableStateFlow(Link.DISCONNECTED)
    val link: StateFlow<Link> = _link.asStateFlow()

    private val _liveCount = MutableStateFlow(0)

    /** How many of the first [items] arrived live (shot while connected) since the last [refreshList]. */
    val liveCount: StateFlow<Int> = _liveCount.asStateFlow()

    /** Camera model from the last connect, e.g. "Canon EOS R5". */
    @Volatile var model: String? = null
        private set

    private val reconnectLock = Mutex()
    @Volatile private var generation = 0
    @Volatile private var target: Target? = null
    /** Which camera's object list to cache: its serial number, else its model. */
    @Volatile private var cameraKey: String? = null
    private var keepAlive: Job? = null

    /**
     * Connects, retrying until [windowMs] runs out. A phone the camera doesn't know yet is refused
     * (-2) while the camera shows its confirmation prompt; retrying every second picks the OK up
     * right away. [onRetry] reports each failed attempt so the UI can say what it is waiting for.
     */
    suspend fun connect(
        ip: String,
        name: String = EOS_UTILITY_NAME,
        remoteMode: Int = REMOTE_MODE_PC,
        windowMs: Long = PAIRING_WINDOW_MS,
        onRetry: (PtpException) -> Unit = {},
    ) {
        open(Target(ip, name, remoteMode), windowMs, onRetry)
        startKeepAlive()
    }

    /** Manual reconnect, e.g. from the "connection lost" banner: the full pairing window again. */
    suspend fun reconnect(onRetry: (PtpException) -> Unit = {}) {
        reconnectLock.withLock {
            val target = target ?: throw ConnectionLostException()
            _link.value = Link.RECONNECTING
            try {
                open(target, PAIRING_WINDOW_MS, onRetry)
            } catch (e: Throwable) {
                _link.value = Link.LOST
                throw e
            }
        }
        startKeepAlive()
    }

    private suspend fun open(target: Target, windowMs: Long, onRetry: (PtpException) -> Unit) {
        var last: PtpException? = null
        var connected = false
        var info: DeviceInfo? = null
        withTimeoutOrNull(windowMs) {
            while (true) {
                try {
                    info = ptp.connect(ip = target.ip, name = target.name, guid = prefs.pairingGuid(), remoteMode = target.remoteMode)
                    connected = true
                    break
                } catch (e: PtpException) {
                    last = e
                    onRetry(e)
                }
                delay(RETRY_DELAY_MS)
            }
        }
        if (!connected) throw last ?: PtpException("connect", -1)
        this.model = info?.model ?: this.model
        cameraKey = info?.serial ?: info?.model ?: cameraKey
        this.target = target
        prefs.lastIp = target.ip
        generation++
        _link.value = Link.CONNECTED
    }

    /** While connected: poll events (new photos land at the top of [items]) and ping. A failed call
     *  takes the same silent reconnect as any other; once that fails too the link is [Link.LOST] and
     *  the loop ends until the next (re)connect. */
    private fun startKeepAlive() {
        keepAlive?.cancel()
        keepAlive = scope.launch {
            var polls = 0
            while (_link.value != Link.LOST && _link.value != Link.DISCONNECTED) {
                delay(POLL_MS)
                try {
                    if (++polls % PING_EVERY_POLLS == 0) session { ptp.ping() }
                    val added = addedObjectHandles(session { ptp.events() })
                    if (added.isNotEmpty()) addLive(added)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Surfaces through the link state: RECONNECTING / LOST drive the gallery banner.
                }
            }
        }
    }

    private suspend fun addLive(handles: List<Int>) {
        val known = _items.value.mapTo(HashSet()) { it.obj.handle }
        val shots = _items.value.mapTo(HashSet()) { it.obj.shot }
        val fresh = handles.filter { it !in known }
            .mapNotNull { session { ptp.objectInfo(it) } }
            .filter { it.kind != FileKind.OTHER && shots.add(it.shot) }
            .sortedByDescending { it.takenAtMillis }
            .map { GalleryItem(it, rating = null, orientation = 1, thumbFile = null) }
        if (fresh.isEmpty()) return
        _items.update { fresh + it }
        _liveCount.update { it + fresh.size }
    }

    /**
     * Runs one camera call. When libpict reports the session dead (timeout, camera closed the
     * socket, Wi-Fi hiccup), reconnects once silently and runs [block] again. Safe because every
     * call here only reads; [block] must pick up any progress it made (see [download]).
     */
    private suspend fun <T> session(block: suspend () -> T): T {
        if (_link.value == Link.LOST) throw ConnectionLostException()
        val seen = generation
        try {
            return block()
        } catch (e: PtpException) {
            if (!e.sessionDead || e.cancelled) throw e
            recover(seen, e)
        }
        return block()
    }

    /** One silent reconnect per dead session; callers that saw the same [seen] generation share it. */
    private suspend fun recover(seen: Int, cause: PtpException) = reconnectLock.withLock {
        if (generation != seen) return@withLock
        if (_link.value == Link.LOST) throw ConnectionLostException(cause)
        val target = target ?: throw cause
        _link.value = Link.RECONNECTING
        try {
            open(target, RECONNECT_WINDOW_MS) {}
        } catch (e: PtpException) {
            _link.value = Link.LOST
            throw ConnectionLostException(e)
        }
    }

    /**
     * Shows the gallery: the object list cached for this camera at once, then whatever the camera
     * has that the cache doesn't (newest first, in batches, so the first photos show within a
     * second even on a fresh 10 000-image card). Handles gone from the camera drop out.
     */
    suspend fun refreshList() {
        _liveCount.value = 0
        val key = cameraKey
        val handles = session { ptp.listHandles() }
        val onCamera = handles.toHashSet()
        val known = HashMap<Int, CameraObject>()
        key?.let { lists?.load(it) }.orEmpty().filter { it.handle in onCamera }.associateByTo(known) { it.handle }
        if (known.isNotEmpty() && !cacheStillMatches(known.values)) known.clear()
        _items.value = toItems(known.values)

        // Canon assigns handles chronologically: reversed = newest first.
        val missing = handles.asReversed().filter { it !in known }
        for ((i, batch) in missing.chunked(LIST_BATCH).withIndex()) {
            val objects = batch.mapNotNull { session { ptp.objectInfo(it) } }
            objects.associateByTo(known) { it.handle }
            val newItems = toItems(objects)
            // Merge (not replace): ensureMeta may have filled thumbnails of visible cells meanwhile.
            _items.update { current ->
                (current + newItems).distinctBy { it.obj.shot }.sortedByDescending { it.obj.takenAtMillis }
            }
            if (key != null && (i + 1) % SAVE_EVERY_BATCHES == 0) lists?.save(key, known.values)
        }
        if (key != null) lists?.save(key, known.values)
    }

    private fun toItems(objects: Collection<CameraObject>): List<GalleryItem> =
        objects.filter { it.kind != FileKind.OTHER }
            .map { obj ->
                val meta = cache.get(obj.handle, obj.size)
                GalleryItem(obj, meta?.rating, meta?.orientation ?: 1, meta?.thumbFile)
            }
            .distinctBy { it.obj.shot }
            .sortedByDescending { it.obj.takenAtMillis }

    /** A reformatted card can hand out the same handles for different files: spot-check a few. */
    private suspend fun cacheStillMatches(cached: Collection<CameraObject>): Boolean {
        val byHandle = cached.sortedBy { it.handle }
        return listOf(byHandle.first(), byHandle[byHandle.size / 2], byHandle.last()).distinct().all { c ->
            session { ptp.objectInfo(c.handle) }?.let { it.name == c.name && it.size == c.size } == true
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

        val header = session { ptp.readPartial(handle, 0, HEADER_BYTES) }
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
        val buf = session { ptp.readPartial(handle, 0, PREVIEW_BYTES) }
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
            } catch (e: ConnectionLostException) {
                throw e
            } catch (e: CancellationException) {
                throw e
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
                runBlocking { transfer(item.obj, out, onProgress) }
            }
        }

    /** A retry after a reconnect resumes at `written`, so [out] never gets bytes twice. */
    internal suspend fun transfer(obj: CameraObject, out: OutputStream, onProgress: (Long) -> Unit) {
        var written = 0L
        session { ptp.downloadTo(obj, out, written, DOWNLOAD_CHUNK) { written = it; onProgress(it) } }
    }

    /** Stops the running download after its current chunk; the session stays open. */
    fun cancel() = ptp.cancelIo()

    suspend fun disconnect() {
        keepAlive?.cancel()
        _link.value = Link.DISCONNECTED
        target = null
        _items.value = emptyList()
        ptp.disconnect()
    }

    /** Replaces the matching item with an immutable copy so collectors see a fresh list. */
    private fun applyMeta(handle: Int, rating: Int?, orientation: Int, thumbFile: File?) {
        _items.update { list ->
            list.map {
                if (it.obj.handle == handle) it.copy(rating = rating, orientation = orientation, thumbFile = thumbFile) else it
            }
        }
    }
}
