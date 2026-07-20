package io.github.canonwirelessraw.cr3

/**
 * Minimal ISO-BMFF / CR3 box walker. CR3 stores its embedded images and metadata in named boxes
 * with size headers, so we navigate the structure directly instead of scanning the byte stream for
 * JPEG markers (which false-matches on marker-like bytes inside Canon makernotes → blank cells).
 *
 * Layout used here (verified on EOS R5 CR3):
 *   ftyp
 *   moov
 *     uuid(85c0b687-…)          Canon metadata container
 *       CMT1                     EXIF IFD0 (Orientation tag 0x0112)
 *       THMB                     small thumbnail: [size][THMB][version:4][w:2][h:2][jpegSize:4][pad:2][JPEG]
 *   uuid(be7acfcb-…)             XMP
 *   uuid(eaf42b5e-…)             PRVW full-HD preview JPEG
 *   mdat
 */
object Cr3Container {

    private val CANON_UUID = hex("85c0b687820f11e08111f4ce462b6a48")
    private val PREVIEW_UUID = hex("eaf42b5e1c984b88b9fbb7dc406e4d16")

    /** The small embedded thumbnail JPEG (from the THMB box), or null if not present in [buf]. */
    fun thumbnailJpeg(buf: ByteArray): ByteArray? {
        val moov = findBox(buf, 0, buf.size, "moov") ?: return null
        val canon = findUuidBox(buf, moov.first, moov.last + 1, CANON_UUID) ?: return null
        val thmb = findBox(buf, canon.first, canon.last + 1, "THMB") ?: return null
        // THMB payload is a small fixed header (version/w/h/jpegSize/pad) followed by the JPEG.
        // Rather than trust an exact header length, scan for the JPEG *within the THMB box* — it's
        // bounded to this box, which contains only the thumbnail, so no false positive is possible.
        val soi = indexOfSoi(buf, thmb.first, thmb.last + 1) ?: return null
        val eoi = indexOfEoi(buf, soi + 2, thmb.last + 1) ?: return null
        return buf.copyOfRange(soi, eoi + 2)
    }

    /** The full-HD preview JPEG (from the eaf42b5e uuid box), or null. Scans for the JPEG inside
     *  that box only — bounded, so makernote false-positives elsewhere can't interfere. */
    fun previewJpeg(buf: ByteArray): ByteArray? {
        val prvw = findUuidBox(buf, 0, buf.size, PREVIEW_UUID) ?: return null
        val soi = indexOfSoi(buf, prvw.first, prvw.last + 1) ?: return null
        val eoi = indexOfEoi(buf, soi + 2, prvw.last + 1) ?: return null
        return buf.copyOfRange(soi, eoi + 2)
    }

    /** EXIF orientation (1..8) from CMT1's IFD0 tag 0x0112; 1 (normal) if absent/unreadable. */
    fun orientation(buf: ByteArray): Int {
        val moov = findBox(buf, 0, buf.size, "moov") ?: return 1
        val canon = findUuidBox(buf, moov.first, moov.last + 1, CANON_UUID) ?: return 1
        val cmt1 = findBox(buf, canon.first, canon.last + 1, "CMT1") ?: return 1
        return tiffOrientation(buf, cmt1.first, cmt1.last + 1) ?: 1
    }

    // ---- box helpers (payload range = after the 8/16-byte header) ----

    /** Finds the first box of [type] between [start] and [end); returns its PAYLOAD range. */
    private fun findBox(buf: ByteArray, start: Int, end: Int, type: String): IntRange? {
        val t = type.toByteArray(Charsets.US_ASCII)
        var off = start
        while (off + 8 <= end) {
            val size = readU32(buf, off) ?: return null
            var boxLen = size
            var header = 8
            if (size == 1L) {
                val large = readU64(buf, off + 8) ?: return null
                boxLen = large; header = 16
            } else if (size == 0L) {
                boxLen = (end - off).toLong()
            }
            if (boxLen < header || off + boxLen > end) return null
            if (buf[off + 4] == t[0] && buf[off + 5] == t[1] && buf[off + 6] == t[2] && buf[off + 7] == t[3]) {
                return (off + header)..(off + boxLen.toInt() - 1)
            }
            off += boxLen.toInt()
        }
        return null
    }

    /** Finds a uuid box whose 16-byte extended type equals [guid]; returns the range AFTER the guid. */
    private fun findUuidBox(buf: ByteArray, start: Int, end: Int, guid: ByteArray): IntRange? {
        var off = start
        val t = "uuid".toByteArray(Charsets.US_ASCII)
        while (off + 8 <= end) {
            val size = readU32(buf, off) ?: return null
            var boxLen = size
            var header = 8
            if (size == 1L) {
                val large = readU64(buf, off + 8) ?: return null
                boxLen = large; header = 16
            } else if (size == 0L) {
                boxLen = (end - off).toLong()
            }
            if (boxLen < header || off + boxLen > end) return null
            val isUuid = buf[off + 4] == t[0] && buf[off + 5] == t[1] && buf[off + 6] == t[2] && buf[off + 7] == t[3]
            if (isUuid && off + header + 16 <= end && regionEquals(buf, off + header, guid)) {
                return (off + header + 16)..(off + boxLen.toInt() - 1)
            }
            off += boxLen.toInt()
        }
        return null
    }

    /** Reads a TIFF/EXIF block ([start,end)) and returns IFD0 tag 0x0112 (Orientation), or null. */
    private fun tiffOrientation(buf: ByteArray, start: Int, end: Int): Int? {
        if (start + 8 > end) return null
        val little = when {
            buf[start].toInt() == 0x49 && buf[start + 1].toInt() == 0x49 -> true  // "II"
            buf[start].toInt() == 0x4D && buf[start + 1].toInt() == 0x4D -> false // "MM"
            else -> return null
        }
        val ifd0 = start + (readU32E(buf, start + 4, little) ?: return null).toInt()
        if (ifd0 + 2 > end) return null
        val count = readU16E(buf, ifd0, little) ?: return null
        var e = ifd0 + 2
        repeat(count) {
            if (e + 12 > end) return null
            val tag = readU16E(buf, e, little) ?: return null
            if (tag == 0x0112) {
                val value = readU16E(buf, e + 8, little) ?: return null
                return if (value in 1..8) value else null
            }
            e += 12
        }
        return null
    }

    // ---- JPEG marker scan, bounded to [from,end) ----

    private fun indexOfSoi(buf: ByteArray, from: Int, end: Int): Int? {
        var i = from
        while (i + 4 <= end) {
            if ((buf[i].toInt() and 0xFF) == 0xFF && (buf[i + 1].toInt() and 0xFF) == 0xD8 &&
                (buf[i + 2].toInt() and 0xFF) == 0xFF
            ) {
                val m = buf[i + 3].toInt() and 0xFF
                if (m in 0xC0..0xFE && m != 0xD8 && m != 0xD9) return i
            }
            i++
        }
        return null
    }

    private fun indexOfEoi(buf: ByteArray, from: Int, end: Int): Int? {
        var i = from
        while (i + 2 <= end) {
            if ((buf[i].toInt() and 0xFF) == 0xFF && (buf[i + 1].toInt() and 0xFF) == 0xD9) return i
            i++
        }
        return null
    }

    // ---- primitive reads (big-endian; *E variants honour endianness for TIFF) ----

    private fun readU32(buf: ByteArray, at: Int): Long? {
        if (at < 0 || at + 4 > buf.size) return null
        return ((buf[at].toLong() and 0xFF) shl 24) or ((buf[at + 1].toLong() and 0xFF) shl 16) or
            ((buf[at + 2].toLong() and 0xFF) shl 8) or (buf[at + 3].toLong() and 0xFF)
    }

    private fun readU64(buf: ByteArray, at: Int): Long? {
        if (at < 0 || at + 8 > buf.size) return null
        var v = 0L
        for (k in 0 until 8) v = (v shl 8) or (buf[at + k].toLong() and 0xFF)
        return v
    }

    private fun readU16E(buf: ByteArray, at: Int, little: Boolean): Int? {
        if (at < 0 || at + 2 > buf.size) return null
        val a = buf[at].toInt() and 0xFF
        val b = buf[at + 1].toInt() and 0xFF
        return if (little) (b shl 8) or a else (a shl 8) or b
    }

    private fun readU32E(buf: ByteArray, at: Int, little: Boolean): Long? {
        if (at < 0 || at + 4 > buf.size) return null
        val bytes = (0 until 4).map { buf[at + it].toLong() and 0xFF }
        return if (little) (bytes[3] shl 24) or (bytes[2] shl 16) or (bytes[1] shl 8) or bytes[0]
        else (bytes[0] shl 24) or (bytes[1] shl 16) or (bytes[2] shl 8) or bytes[3]
    }

    private fun regionEquals(buf: ByteArray, at: Int, expected: ByteArray): Boolean {
        if (at + expected.size > buf.size) return false
        for (k in expected.indices) if (buf[at + k] != expected[k]) return false
        return true
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((Character.digit(s[it * 2], 16) shl 4) + Character.digit(s[it * 2 + 1], 16)).toByte() }
}
