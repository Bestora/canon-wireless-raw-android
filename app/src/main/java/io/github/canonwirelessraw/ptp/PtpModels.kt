package io.github.canonwirelessraw.ptp

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

enum class FileKind { CR3, JPEG, HEIF, OTHER }

data class CameraObject(
    val handle: Int,
    val name: String,
    val size: Long,
    val takenAtMillis: Long,
    val kind: FileKind,
    val format: Int,
)

private const val PTP_DATE_PATTERN = "yyyyMMdd'T'HHmmss"

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
