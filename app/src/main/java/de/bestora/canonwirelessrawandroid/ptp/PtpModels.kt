package de.bestora.canonwirelessrawandroid.ptp

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

enum class FileKind { CR3, JPEG, HEIF, OTHER }

/** What GetDeviceInfo says about the camera; [serial] tells two bodies of the same model apart. */
data class DeviceInfo(val model: String?, val serial: String?)

data class CameraObject(
    val handle: Int,
    val name: String,
    val size: Long,
    val takenAtMillis: Long,
    val kind: FileKind,
    val format: Int,
)

private const val PTP_DATE_PATTERN = "yyyyMMdd'T'HHmmss"

/** EOS event "object added" (libpict PTP_EC_EOS_ObjectAddedEx); data starts with the handle. */
private const val EOS_OBJECT_ADDED = 0xC181

/** The 64-bit-size variant newer bodies may send (libgphoto2 PTP_EC_CANON_EOS_ObjectAddedEx64). */
private const val EOS_OBJECT_ADDED_64 = 0xC1A7

/**
 * Handles of objects the camera reports as new in a GetEvent payload. Records are
 * [size u32][type u32][data], little-endian, ended by a record of type 0.
 */
fun addedObjectHandles(events: ByteArray): List<Int> {
    val buf = java.nio.ByteBuffer.wrap(events).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    val handles = ArrayList<Int>()
    var off = 0
    while (off + 8 <= events.size) {
        val size = buf.getInt(off)
        val type = buf.getInt(off + 4)
        if (type == 0 || size < 8 || off + size > events.size) break
        if ((type == EOS_OBJECT_ADDED || type == EOS_OBJECT_ADDED_64) && size >= 12) handles += buf.getInt(off + 8)
        off += size
    }
    return handles
}

/** "yyyyMMdd'T'HHmmss" (+ optional ".x"/"Z" suffix) -> epoch millis in the local TZ, 0 on parse failure. */
fun parsePtpDate(s: String): Long {
    val cut = s.indexOfAny(charArrayOf('.', 'Z'))
    val base = if (cut >= 0) s.substring(0, cut) else s
    return try {
        SimpleDateFormat(PTP_DATE_PATTERN, Locale.US).parse(base)?.time ?: 0L
    } catch (e: ParseException) {
        0L
    }
}

/** File extension, case-insensitive -> FileKind. */
fun kindFromName(name: String): FileKind = when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
    "cr3" -> FileKind.CR3
    "jpg", "jpeg" -> FileKind.JPEG
    "hif", "heif" -> FileKind.HEIF
    else -> FileKind.OTHER
}
