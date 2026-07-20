package io.github.canonwirelessraw.cr3

import io.github.canonwirelessraw.ptp.FileKind

/**
 * Pure-Kotlin parser for the rating and embedded preview carried in the first ~128 KB of a
 * camera file. CR3/HEIF are ISO-BMFF containers; the rating lives in a top-level `uuid` box
 * holding an XMP packet. JPEG carries it in the EXIF IFD0 `0x4746` tag.
 */
object ImageHeaderParser {

    private val XMP_GUID = byteArrayOf(
        0xBE.toByte(), 0x7A, 0xCF.toByte(), 0xCB.toByte(),
        0x97.toByte(), 0xA9.toByte(), 0x42, 0xE8.toByte(),
        0x9C.toByte(), 0x71, 0x99.toByte(), 0x94.toByte(),
        0x91.toByte(), 0xE3.toByte(), 0xAF.toByte(), 0xAC.toByte(),
    )

    private val RATING_ATTR = Regex("""xmp:Rating\s*=\s*["'](-?\d+)["']""")
    private val RATING_ELEM = Regex("""<xmp:Rating>\s*(-?\d+)""")

    private const val EXIF_TAG_RATING = 0x4746
    private const val TIFF_TYPE_SHORT = 3

    /**
     * CR3 (ISO-BMFF): top-level box scan for a `uuid` box with the XMP GUID; inside it,
     * `xmp:Rating` as attribute or element. Clamped to -1..5, -1 (rejected) mapped to 0.
     * Returns null if not found / not parseable as BMFF. Never throws on truncated input.
     */
    fun cr3Rating(header: ByteArray): Int? {
        val len = header.size.toLong()
        var offset = 0L
        while (offset + 8 <= len) {
            val sizeField = readU32BE(header, offset) ?: return null
            val headerSize: Long
            val boxSize: Long
            when {
                sizeField == 1L -> {
                    headerSize = 16L
                    boxSize = readU64BE(header, offset + 8) ?: return null
                }
                sizeField == 0L -> {
                    headerSize = 8L
                    boxSize = len - offset
                }
                sizeField in 2..7 -> return null // invalid box size, abort scan
                else -> {
                    headerSize = 8L
                    boxSize = sizeField
                }
            }
            if (boxSize < headerSize) return null
            if (offset + boxSize > len || offset + boxSize < offset) return null // truncated/overflow

            val type = String(header, (offset + 4).toInt(), 4, Charsets.US_ASCII)
            if (type == "uuid") {
                val payloadStart = offset + headerSize
                val payloadEnd = offset + boxSize
                if (payloadEnd - payloadStart >= 16) {
                    val start = payloadStart.toInt()
                    var isXmp = true
                    for (i in 0 until 16) {
                        if (header[start + i] != XMP_GUID[i]) {
                            isXmp = false
                            break
                        }
                    }
                    if (isXmp) {
                        val xml = String(header, start + 16, (payloadEnd - payloadStart - 16).toInt(), Charsets.ISO_8859_1)
                        val match = RATING_ATTR.find(xml) ?: RATING_ELEM.find(xml)
                        val raw = match?.groupValues?.get(1)?.toIntOrNull() ?: return null
                        val clamped = raw.coerceIn(-1, 5)
                        return if (clamped == -1) 0 else clamped
                    }
                }
            }
            if (sizeField == 0L) break // consumed to end of buffer, nothing more to scan
            offset += boxSize
        }
        return null
    }

    /** JPEG: EXIF IFD0 tag 0x4746 (Rating, SHORT). null if absent or unparseable. */
    fun jpegRating(header: ByteArray): Int? {
        val len = header.size
        if (len < 4 || header[0] != 0xFF.toByte() || header[1] != 0xD8.toByte()) return null
        var offset = 2
        while (offset + 4 <= len) {
            if (header[offset] != 0xFF.toByte()) return null
            val marker = header[offset + 1].toInt() and 0xFF
            if (marker == 0xD9 || marker == 0x01 || marker in 0xD0..0xD7) {
                offset += 2
                continue
            }
            val segLen = ((header[offset + 2].toInt() and 0xFF) shl 8) or (header[offset + 3].toInt() and 0xFF)
            if (segLen < 2) return null
            val lenFieldStart = offset + 2
            val segEnd = lenFieldStart + segLen
            if (segEnd > len) return null
            if (marker == 0xE1) return parseExifApp1(header, lenFieldStart + 2, segEnd)
            if (marker == 0xDA) return null // start of scan, no more marker segments follow
            offset = segEnd
        }
        return null
    }

    /** First embedded JPEG (SOI FFD8FF .. EOI FFD9) at/after byte offset minStart. */
    fun embeddedJpeg(header: ByteArray, minStart: Int = 0): ByteArray? {
        val start = indexOfSoi(header, minStart.coerceAtLeast(0)) ?: return null
        val eoi = indexOfEoi(header, start + 2) ?: return null
        return header.copyOfRange(start, eoi + 2)
    }

    /**
     * Largest complete embedded JPEG (SOI…EOI) in [buf], or null if none is complete. A CR3 embeds
     * several JPEGs of increasing size (tiny thumbnail, ~FullHD preview, full-res); this picks the
     * biggest one contained in the buffer — used to load a sharp preview for the zoom viewer.
     * The next FFD9 after an SOI is the real EOI: JPEG byte-stuffing (FF→FF00) means FFD9 never
     * appears inside entropy data, only as a marker.
     */
    fun largestEmbeddedJpeg(buf: ByteArray): ByteArray? {
        var bestStart = -1
        var bestEnd = -1 // exclusive
        var i = 0
        while (true) {
            val soi = indexOfSoi(buf, i) ?: break
            val eoi = indexOfEoi(buf, soi + 2) ?: break // no complete JPEG after this point
            val end = eoi + 2
            if (bestStart < 0 || end - soi > bestEnd - bestStart) {
                bestStart = soi
                bestEnd = end
            }
            i = end
        }
        return if (bestStart < 0) null else buf.copyOfRange(bestStart, bestEnd)
    }

    /** Dispatch by FileKind: CR3->cr3Rating, JPEG->jpegRating, HEIF->cr3Rating (best effort), OTHER->null. */
    fun rating(kind: FileKind, header: ByteArray): Int? = when (kind) {
        FileKind.CR3, FileKind.HEIF -> cr3Rating(header)
        FileKind.JPEG -> jpegRating(header)
        FileKind.OTHER -> null
    }

    private fun parseExifApp1(header: ByteArray, start: Int, end: Int): Int? {
        if (end - start < 6) return null
        val prefix = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
        for (i in 0 until 6) if (header[start + i] != prefix[i]) return null

        val tiffStart = start + 6
        if (end - tiffStart < 8) return null
        val b0 = header[tiffStart].toInt() and 0xFF
        val b1 = header[tiffStart + 1].toInt() and 0xFF
        val little = when {
            b0 == 0x49 && b1 == 0x49 -> true
            b0 == 0x4D && b1 == 0x4D -> false
            else -> return null
        }

        fun u16(at: Int): Int? {
            if (at < 0 || at + 2 > end) return null
            val a = header[at].toInt() and 0xFF
            val b = header[at + 1].toInt() and 0xFF
            return if (little) (b shl 8) or a else (a shl 8) or b
        }
        fun u32(at: Int): Int? {
            if (at < 0 || at + 4 > end) return null
            val a = header[at].toInt() and 0xFF
            val b = header[at + 1].toInt() and 0xFF
            val c = header[at + 2].toInt() and 0xFF
            val d = header[at + 3].toInt() and 0xFF
            return if (little) (d shl 24) or (c shl 16) or (b shl 8) or a else (a shl 24) or (b shl 16) or (c shl 8) or d
        }

        val magic = u16(tiffStart + 2) ?: return null
        if (magic != 42) return null
        val ifd0Rel = u32(tiffStart + 4) ?: return null
        val ifd0Offset = tiffStart + ifd0Rel
        val count = u16(ifd0Offset) ?: return null
        var entryOffset = ifd0Offset + 2
        for (i in 0 until count) {
            if (entryOffset < 0 || entryOffset + 12 > end) return null
            val tag = u16(entryOffset) ?: return null
            val type = u16(entryOffset + 2) ?: return null
            if (tag == EXIF_TAG_RATING && type == TIFF_TYPE_SHORT) {
                return u16(entryOffset + 8)
            }
            entryOffset += 12
        }
        return null
    }

    private fun indexOfSoi(header: ByteArray, from: Int): Int? {
        var i = from
        while (i + 4 <= header.size) {
            if ((header[i].toInt() and 0xFF) == 0xFF &&
                (header[i + 1].toInt() and 0xFF) == 0xD8 &&
                (header[i + 2].toInt() and 0xFF) == 0xFF
            ) {
                // A real JPEG's first segment after SOI is a marker in 0xC0..0xFE (DQT 0xDB,
                // APPn 0xE0-0xEF, SOF 0xC0.., …), never 0xD8/0xD9. Canon's own metadata contains
                // stray FFD8FF byte sequences (e.g. followed by 0xBF) that are NOT image starts;
                // without this check we'd slice a corrupt JPEG the decoder rejects → blank cell.
                val marker = header[i + 3].toInt() and 0xFF
                if (marker in 0xC0..0xFE && marker != 0xD8 && marker != 0xD9) return i
            }
            i++
        }
        return null
    }

    private fun indexOfEoi(header: ByteArray, from: Int): Int? {
        var i = from
        while (i + 2 <= header.size) {
            if ((header[i].toInt() and 0xFF) == 0xFF && (header[i + 1].toInt() and 0xFF) == 0xD9) {
                return i
            }
            i++
        }
        return null
    }

    private fun readU32BE(bytes: ByteArray, at: Long): Long? {
        if (at < 0 || at + 4 > bytes.size) return null
        val i = at.toInt()
        return ((bytes[i].toLong() and 0xFF) shl 24) or
            ((bytes[i + 1].toLong() and 0xFF) shl 16) or
            ((bytes[i + 2].toLong() and 0xFF) shl 8) or
            (bytes[i + 3].toLong() and 0xFF)
    }

    private fun readU64BE(bytes: ByteArray, at: Long): Long? {
        if (at < 0 || at + 8 > bytes.size) return null
        var result = 0L
        val i = at.toInt()
        for (j in 0 until 8) {
            result = (result shl 8) or (bytes[i + j].toLong() and 0xFF)
        }
        return result
    }
}
