package de.bestora.canonwirelessrawandroid.data

import java.io.File

data class ImageMeta(val rating: Int?, val orientation: Int, val thumbFile: File?)

class MetaCache(private val dir: File) {

    fun get(handle: Int, size: Long): ImageMeta? {
        val metaFile = File(dir, "$handle-$size.meta")
        if (!metaFile.exists()) {
            return null
        }

        // Two lines: rating ("-" = none), then orientation (EXIF 1..8; absent → 1).
        val lines = metaFile.readText().lines()
        val rating = lines.getOrNull(0)?.trim()?.let { if (it == "-") null else it.toIntOrNull() }
        val orientation = lines.getOrNull(1)?.trim()?.toIntOrNull() ?: 1

        val thumbFile = File(dir, "$handle-$size.jpg").let {
            if (it.exists()) it else null
        }

        return ImageMeta(rating, orientation, thumbFile)
    }

    fun put(handle: Int, size: Long, rating: Int?, orientation: Int, thumb: ByteArray?): ImageMeta {
        dir.mkdirs()

        val thumbFile = File(dir, "$handle-$size.jpg")

        // Write thumbnail bytes first if provided
        if (thumb != null) {
            thumbFile.writeBytes(thumb)
        }

        // Write metadata last (for atomic-enough behavior): rating line + orientation line.
        val metaFile = File(dir, "$handle-$size.meta")
        val ratingStr = if (rating == null) "-" else rating.toString()
        metaFile.writeText("$ratingStr\n$orientation")

        // Return the resulting ImageMeta
        return ImageMeta(rating, orientation, if (thumb != null) thumbFile else null)
    }

    fun clear() {
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }
}
