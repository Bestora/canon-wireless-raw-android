package de.bestora.canonwirelessrawandroid.ptp

import android.util.Log
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "PtpClient"

/** PTP object format code for an association (folder). */
private const val FORMAT_ASSOCIATION = 0x3001

/** libpict PTP_CHECK_CODE: the camera answered with a non-OK response code. The only failure
 *  after which the session is still usable. */
const val CODE_CAMERA_REFUSED = -8

/** Cancelled-download sentinel code (see [PtpClient.cancelIo]). */
const val CODE_CANCELLED = -99

/** Sentinel code: the loop ended (short/empty read) before the object's full size was written. */
private const val CODE_SHORT = -98

/** EOS remote mode for PC software (EOS Utility pairing). */
const val REMOTE_MODE_PC = 1

/** EOS remote mode Canon's mobile SDK sets for a phone app (CPtpCamera::OpenSessionSelf). */
const val REMOTE_MODE_SMARTPHONE = 0x15

/**
 * Port implemented by [PtpClient], so [de.bestora.canonwirelessrawandroid.data.CameraRepository]
 * can be driven by fakes in JVM tests. Every method throws [PtpException] on failure; none of them
 * reports a failure as an empty result.
 */
interface PtpPort {
    /** [remoteMode] 0 skips the EOS remote/event mode setup. */
    suspend fun connect(
        ip: String,
        port: Int = 15740,
        name: String = "Canon Wireless RAW",
        guid: ByteArray,
        remoteMode: Int = REMOTE_MODE_PC,
    ): DeviceInfo
    /** Just the object handles (one GetObjectHandles per storage), no per-object GetObjectInfo,
     *  so a caller can load metadata progressively instead of paying 1000+ round-trips up front. */
    suspend fun listHandles(): List<Int>
    /** GetObjectInfo for one handle; folders come back as [FileKind.OTHER] like any non-image. */
    suspend fun objectInfo(handle: Int): CameraObject?
    suspend fun readPartial(handle: Int, offset: Long, len: Int): ByteArray
    /** Writes [obj] from byte [from] on into [sink]; [onProgress] gets the absolute offset reached. */
    suspend fun downloadTo(obj: CameraObject, sink: OutputStream, from: Long = 0, chunk: Int = 1 shl 20, onProgress: (Long) -> Unit)
    fun cancelIo()
    suspend fun disconnect()
    /** Canon DoNothing (0x902F). Canon's mobile SDK sends it every 5 s; without it a camera in
     *  smartphone mode drops the connection after a minute or two. */
    suspend fun ping()
    /** Canon EOS GetEvent: the raw event records queued since the last call. */
    suspend fun events(): ByteArray
}

class PtpException(val step: String, val code: Int) : Exception("$step failed: $code") {
    /** libpict latches its runtime after every failure except a refused command: each later
     *  call fails until the next connect. */
    val sessionDead: Boolean get() = code != CODE_CAMERA_REFUSED
    val cancelled: Boolean get() = code == CODE_CANCELLED
}

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

    override suspend fun connect(ip: String, port: Int, name: String, guid: ByteArray, remoteMode: Int): DeviceInfo = io {
        // Drop whatever is left of an earlier session first; on a dead or never-opened runtime
        // this returns at once, and it keeps the old sockets from leaking.
        PtpNative.disconnect(rt)
        check("connect", PtpNative.connect(rt, ip, port, guid, name))
        check("openSession", PtpNative.openSession(rt))
        val model = PtpNative.getDeviceInfo(rt)
        Log.i(TAG, "device: ${model ?: "GetDeviceInfo failed: ${PtpNative.lastError(rt)}"}")
        if (remoteMode != 0) {
            val remoteRc = PtpNative.eosSetRemoteMode(rt, remoteMode)
            if (remoteRc != 0) Log.w(TAG, "eosSetRemoteMode($remoteMode) failed: $remoteRc")
            val eventRc = PtpNative.eosSetEventMode(rt, 1)
            if (eventRc != 0) Log.w(TAG, "eosSetEventMode failed: $eventRc")
            // The first GetEvent returns the camera's full state; Canon's SDK reads it right away.
            Log.i(TAG, "first GetEvent: ${PtpNative.eosGetEvent(rt)?.size} bytes")
        }
        DeviceInfo(model, model?.let { PtpNative.deviceSerial(rt) }?.ifBlank { null })
    }

    override suspend fun ping() = io { check("ping", PtpNative.canonPing(rt)) }

    override suspend fun events(): ByteArray = io { PtpNative.eosGetEvent(rt) ?: fail("eosGetEvent") }


    override suspend fun listHandles(): List<Int> = io {
        val storageIds = PtpNative.getStorageIds(rt) ?: fail("getStorageIds")
        // ponytail: flat listing only (verified on the R5: 1222 handles in one call). Walk the
        // folder tree per storage if a body ever returns an empty flat set.
        storageIds.flatMap { (PtpNative.getObjectHandles(rt, it, 0, 0) ?: fail("getObjectHandles")).asList() }
    }

    override suspend fun objectInfo(handle: Int): CameraObject? = io {
        val info = PtpNative.getObjectInfo(rt, handle) ?: fail("getObjectInfo")
        val format = info[3].toIntOrNull() ?: 0
        val assocType = info[5].toIntOrNull() ?: 0
        val obj = toCameraObject(handle, info, format)
        if (assocType != 0 || format == FORMAT_ASSOCIATION) obj.copy(kind = FileKind.OTHER) else obj
    }

    override suspend fun readPartial(handle: Int, offset: Long, len: Int): ByteArray = io {
        PtpNative.getPartialObject(rt, handle, offset, len) ?: fail("getPartialObject")
    }

    override suspend fun downloadTo(obj: CameraObject, sink: OutputStream, from: Long, chunk: Int, onProgress: (Long) -> Unit) = io {
        // A cancel between two batch items is the caller's to honour; this one stops mid-file.
        cancelled = false
        var offset = from
        while (offset < obj.size) {
            if (cancelled) throw PtpException("download", CODE_CANCELLED)
            val len = minOf(chunk.toLong(), obj.size - offset).toInt()
            val data = PtpNative.getPartialObject(rt, obj.handle, offset, len) ?: fail("getPartialObject")
            sink.write(data)
            offset += data.size
            onProgress(offset)
            // A short read (incl. empty) is terminal: re-issuing it would loop forever under the mutex.
            if (data.size < len) break
        }
        if (offset < obj.size) throw PtpException("download", CODE_SHORT)
    }

    /**
     * Debug-only step (Milestone-0 verification): sets EOS remote + event mode individually so a
     * caller can log each step's return code on its own, unlike [connect]'s `eosMode` flag which
     * only warns. Not on [PtpPort].
     */
    suspend fun eosMode(remote: Int = 1, event: Int = 1): EosModeResult = io {
        EosModeResult(PtpNative.eosSetRemoteMode(rt, remote), PtpNative.eosSetEventMode(rt, event))
    }

    /** Stops a running download after its current chunk (≤ 1 MiB, a fraction of a second). Cutting
     *  the transfer mid-packet instead made the R5 drop its Wi-Fi; this way the session stays open. */
    override fun cancelIo() {
        cancelled = true
    }

    override suspend fun disconnect() = io { PtpNative.disconnect(rt) }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    private fun fail(step: String): Nothing = throw PtpException(step, PtpNative.lastError(rt))

    private fun check(step: String, rc: Int) {
        if (rc != 0) throw PtpException(step, rc)
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
