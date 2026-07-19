package io.github.canonwirelessraw.data

import java.io.File

data class ImageMeta(val rating: Int?, val thumbFile: File?)

class MetaCache(private val dir: File) {

    fun get(handle: Int, size: Long): ImageMeta? {
        val metaFile = File(dir, "$handle-$size.meta")
        if (!metaFile.exists()) {
            return null
        }

        val rating = metaFile.readText().trim().let {
            if (it == "-") null else it.toIntOrNull()
        }

        val thumbFile = File(dir, "$handle-$size.jpg").let {
            if (it.exists()) it else null
        }

        return ImageMeta(rating, thumbFile)
    }

    fun put(handle: Int, size: Long, rating: Int?, thumb: ByteArray?): ImageMeta {
        dir.mkdirs()

        val thumbFile = File(dir, "$handle-$size.jpg")

        // Write thumbnail bytes first if provided
        if (thumb != null) {
            thumbFile.writeBytes(thumb)
        }

        // Write rating metadata last (for atomic-enough behavior)
        val metaFile = File(dir, "$handle-$size.meta")
        val ratingStr = if (rating == null) "-" else rating.toString()
        metaFile.writeText(ratingStr)

        // Return the resulting ImageMeta
        return ImageMeta(rating, if (thumb != null) thumbFile else null)
    }

    fun clear() {
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }
}
