package io.github.canonwirelessraw.ptp

import android.util.Log
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "PtpClient"

/** PTP object format code for an association (folder). */
private const val FORMAT_ASSOCIATION = 0x3001

/** PTP root parent handle (0xFFFFFFFF): objects with no parent, i.e. top level of the tree. */
private const val PARENT_ROOT = -1

/** Cancelled-download sentinel code (see [PtpClient.cancelIo]). */
private const val CODE_CANCELLED = -99

/** Sentinel code: the loop ended (short/empty read) before the object's full size was written. */
private const val CODE_SHORT = -98

/**
 * Port implemented by [PtpClient]; declared here so Task 8 (camera repository) can depend on
 * an interface instead of the concrete coroutine wrapper (e.g. for fakes in tests).
 * Default values live here (not on the `override` in PtpClient — Kotlin forbids re-declaring
 * defaults on an override) so callers get the same ergonomics through either type.
 */
interface PtpPort {
    suspend fun connect(
        ip: String,
        port: Int = 15740,
        name: String = "Canon Wireless RAW",
        guid: ByteArray,
        eosMode: Boolean = true,
    )
    suspend fun listObjects(): List<CameraObject>
    /** Just the object handles (one fast GetObjectHandles per storage), no per-object GetObjectInfo.
     *  Lets a caller load metadata progressively/lazily instead of paying 1000+ round-trips up front. */
    suspend fun listHandles(): List<Int>
    /** GetObjectInfo for one handle → CameraObject, or null for a folder/association or on error. */
    suspend fun objectInfo(handle: Int): CameraObject?
    suspend fun readPartial(handle: Int, offset: Long, len: Int): ByteArray
    suspend fun downloadTo(obj: CameraObject, sink: OutputStream, chunk: Int = 1 shl 20, onProgress: (Long) -> Unit)
    fun cancelIo()
    suspend fun disconnect()
}

class PtpException(val step: String, val code: Int) : Exception("$step failed: $code")

/** Result of [PtpClient.eosMode]: raw return codes (0 = OK) for each step, so callers can log/inspect both. */
data class EosModeResult(val remoteModeCode: Int, val eventModeCode: Int)

/**
 * Coroutine wrapper around [PtpNative]. All PTP transactions run on [Dispatchers.IO] and are
 * serialized by [mutex] (the underlying PtpRuntime is not safe for concurrent transactions).
 * [cancelIo] is intentionally NOT guarded by the mutex: it must be callable while a transfer
 * is blocked inside the mutex, to interrupt it.
 */
class PtpClient : PtpPort {
    private val rt: Long = PtpNative.create()
    private val mutex = Mutex()

    @Volatile
    private var cancelled = false

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    override suspend fun connect(ip: String, port: Int, name: String, guid: ByteArray, eosMode: Boolean) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                cancelled = false
                val connectRc = PtpNative.connect(rt, ip, port, guid, name)
                if (connectRc != 0) throw PtpException("connect", connectRc)
                val sessionRc = PtpNative.openSession(rt)
                if (sessionRc != 0) throw PtpException("openSession", sessionRc)
                if (eosMode) {
                    val remoteRc = PtpNative.eosSetRemoteMode(rt, 1)
                    if (remoteRc != 0) Log.w(TAG, "eosSetRemoteMode failed: $remoteRc")
                    val eventRc = PtpNative.eosSetEventMode(rt, 1)
                    if (eventRc != 0) Log.w(TAG, "eosSetEventMode failed: $eventRc")
                }
                _connected.value = true
            }
        }
    }

    override suspend fun listObjects(): List<CameraObject> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val storageIds = PtpNative.getStorageIds(rt) ?: IntArray(0)
            val result = mutableListOf<CameraObject>()
            for (storageId in storageIds) {
                val flat = PtpNative.getObjectHandles(rt, storageId, 0, 0) ?: IntArray(0)
                if (flat.isNotEmpty()) {
                    // Flat listing already contains every object regardless of parent: a folder
                    // handle here is just skipped (its children are already in `flat` too), never
                    // recursed into — that would double-count children.
                    for (handle in flat) {
                        val info = PtpNative.getObjectInfo(rt, handle) ?: continue
                        addFile(handle, info, result)
                    }
                } else {
                    // Flat listing unsupported/empty on this device: walk the folder tree instead.
                    walkFolder(storageId, PARENT_ROOT, result)
                }
            }
            result
        }
    }

    override suspend fun listHandles(): List<Int> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val storageIds = PtpNative.getStorageIds(rt) ?: IntArray(0)
            val out = ArrayList<Int>()
            for (storageId in storageIds) {
                val flat = PtpNative.getObjectHandles(rt, storageId, 0, 0) ?: IntArray(0)
                // ponytail: flat listing only (verified on the R5: 1222 handles in one call).
                // The recursive folder-walk fallback lives in listObjects() for devices that
                // return an empty flat set; add here too if such a device ever shows up.
                for (h in flat) out.add(h)
            }
            out
        }
    }

    override suspend fun objectInfo(handle: Int): CameraObject? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val info = PtpNative.getObjectInfo(rt, handle) ?: return@withLock null
            val format = info[3].toIntOrNull() ?: 0
            val assocType = info[5].toIntOrNull() ?: 0
            if (assocType != 0 || format == FORMAT_ASSOCIATION) null
            else toCameraObject(handle, info, format)
        }
    }

    override suspend fun readPartial(handle: Int, offset: Long, len: Int): ByteArray = withContext(Dispatchers.IO) {
        mutex.withLock {
            PtpNative.getPartialObject(rt, handle, offset, len)
                ?: throw PtpException("getPartialObject", PtpNative.lastError(rt))
        }
    }

    override suspend fun downloadTo(obj: CameraObject, sink: OutputStream, chunk: Int, onProgress: (Long) -> Unit) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                // Do NOT reset `cancelled` here — it's cleared only in connect(). Resetting it
                // per batch item would clear a pending cancel and surface the kill-switch failure
                // as a plain getPartialObject error instead of the CODE_CANCELLED sentinel.
                var offset = 0L
                while (offset < obj.size) {
                    // Already cancelled (e.g. a later batch item after the cancel): fail fast with
                    // the sentinel so the caller recognizes it as cancel, not a per-item failure.
                    if (cancelled) throw PtpException("download", CODE_CANCELLED)
                    val len = minOf(chunk.toLong(), obj.size - offset).toInt()
                    val data = PtpNative.getPartialObject(rt, obj.handle, offset, len)
                        ?: throw if (cancelled) {
                            PtpException("download", CODE_CANCELLED)
                        } else {
                            PtpException("getPartialObject", PtpNative.lastError(rt))
                        }
                    sink.write(data)
                    offset += data.size
                    onProgress(offset)
                    // Short read (incl. empty, non-null chunk) is terminal: re-issuing the same
                    // request would loop forever while holding the mutex.
                    if (data.size < len) break
                }
                if (offset < obj.size) throw PtpException("download", CODE_SHORT)
            }
        }
    }

    /**
     * Debug-only step (Task 9, Milestone-0 verification): sets EOS remote + event mode
     * individually so a caller can log each step's return code on its own, unlike [connect]'s
     * `eosMode` flag which only warns. Not on [PtpPort] — the fake/Task 8 doesn't need it.
     */
    suspend fun eosMode(): EosModeResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val remoteRc = PtpNative.eosSetRemoteMode(rt, 1)
            val eventRc = PtpNative.eosSetEventMode(rt, 1)
            EosModeResult(remoteRc, eventRc)
        }
    }

    override fun cancelIo() {
        cancelled = true
        PtpNative.cancel(rt)
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                PtpNative.disconnect(rt)
                _connected.value = false
            }
        }
    }

    /** Appends the object described by [info] to [out], unless it is a folder (association). */
    private fun addFile(handle: Int, info: Array<String>, out: MutableList<CameraObject>) {
        val format = info[3].toIntOrNull() ?: 0
        val assocType = info[5].toIntOrNull() ?: 0
        if (assocType != 0 || format == FORMAT_ASSOCIATION) return
        out += toCameraObject(handle, info, format)
    }

    /** Recursively descends into [parent], collecting files and recursing into sub-folders. */
    private fun walkFolder(storageId: Int, parent: Int, out: MutableList<CameraObject>) {
        val handles = PtpNative.getObjectHandles(rt, storageId, 0, parent) ?: return
        for (handle in handles) {
            val info = PtpNative.getObjectInfo(rt, handle) ?: continue
            val format = info[3].toIntOrNull() ?: 0
            val assocType = info[5].toIntOrNull() ?: 0
            if (assocType != 0 || format == FORMAT_ASSOCIATION) {
                walkFolder(storageId, handle, out)
            } else {
                addFile(handle, info, out)
            }
        }
    }

    private fun toCameraObject(handle: Int, info: Array<String>, format: Int) = CameraObject(
        handle = handle,
        name = info[0],
        size = info[1].toLongOrNull() ?: 0L,
        takenAtMillis = parsePtpDate(info[2]),
        kind = kindFromName(info[0]),
        format = format,
    )
}
