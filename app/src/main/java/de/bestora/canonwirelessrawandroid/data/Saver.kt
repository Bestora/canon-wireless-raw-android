package de.bestora.canonwirelessrawandroid.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import de.bestora.canonwirelessrawandroid.ptp.FileKind
import java.io.OutputStream

object Saver {
    /** Legt Download/CanonRAW/<name> via MediaStore an (IS_PENDING-Muster).
     *  Ruft body mit dem OutputStream auf; bei Exception wird der Eintrag gelöscht.
     *  Rückgabe: content-Uri der fertigen Datei.
     *  Downloads (nicht Images): die Images-Collection lehnt CR3 mit
     *  "Unsupported MIME type image/x-canon-cr3" ab; Downloads akzeptiert jeden Typ,
     *  und RAW gehört ohnehin nicht in die Foto-Galerie. */
    fun saveDownload(context: Context, name: String, mime: String, body: (OutputStream) -> Unit): Uri {
        val contentResolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, sanitizeDisplayName(name))
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/CanonRAW")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert failed")

        try {
            val stream = contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("openOutputStream returned null for $uri")
            stream.use { body(it) }

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            try {
                contentResolver.delete(uri, null, null)
            } catch (deleteEx: Exception) {
                e.addSuppressed(deleteEx)
            }
            throw e
        }

        return uri
    }

    /** A hostile PTP responder can supply `../../x.jpg`; keep only the final path segment (both
     *  separators) so it can't traverse out of Pictures/CanonRAW. Blank → "image". */
    internal fun sanitizeDisplayName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "image" }

    fun mimeFor(kind: FileKind): String = when (kind) {
        FileKind.CR3 -> "image/x-canon-cr3"
        FileKind.JPEG -> "image/jpeg"
        FileKind.HEIF -> "image/heif"
        FileKind.OTHER -> "application/octet-stream"
    }

    /** ACTION_SEND_MULTIPLE mit FLAG_GRANT_READ_URI_PERMISSION, als Chooser. */
    fun shareIntent(uris: List<Uri>): Intent {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(intent, null)
    }
}
