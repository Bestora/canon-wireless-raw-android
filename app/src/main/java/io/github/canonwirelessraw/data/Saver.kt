package io.github.canonwirelessraw.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import io.github.canonwirelessraw.ptp.FileKind
import java.io.OutputStream

object Saver {
    /** Legt Pictures/CanonRAW/<name> via MediaStore an (IS_PENDING-Muster).
     *  Ruft body mit dem OutputStream auf; bei Exception wird der Eintrag gelöscht.
     *  Rückgabe: content-Uri der fertigen Datei. */
    fun saveToPictures(context: Context, name: String, mime: String, body: (OutputStream) -> Unit): Uri {
        val contentResolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CanonRAW")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert failed")

        try {
            contentResolver.openOutputStream(uri).use { stream ->
                if (stream != null) {
                    body(stream)
                }
            }

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }

        return uri
    }

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
