package de.bestora.canonwirelessrawandroid.cr3

import de.bestora.canonwirelessrawandroid.ptp.FileKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageHeaderParserTest {

    // ---- synthetic-fixture helpers (per task brief) ----

    /** [size:Int32BE][type:4ASCII][payload] */
    private fun bmffBox(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        val header = byteArrayOf(
            (size ushr 24).toByte(),
            (size ushr 16).toByte(),
            (size ushr 8).toByte(),
            size.toByte(),
        ) + type.toByteArray(Charsets.US_ASCII)
        return header + payload
    }

    /** size==1 64-bit-size variant: [1][type][largesize:Int64BE][payload]. */
    private fun bmffLargeBox(type: String, payload: ByteArray): ByteArray {
        val largesize = 16L + payload.size
        val header = byteArrayOf(0, 0, 0, 1) + type.toByteArray(Charsets.US_ASCII)
        val largesizeBytes = ByteArray(8) { i -> (largesize ushr (8 * (7 - i))).toByte() }
        return header + largesizeBytes + payload
    }

    /** Box header claiming an invalid size in 2..7 (malformed ISO-BMFF). */
    private fun invalidSizeBox(size: Int, type: String): ByteArray =
        byteArrayOf(0, 0, 0, size.toByte()) + type.toByteArray(Charsets.US_ASCII)

    /** size==0 variant: box extends to end of buffer, no explicit length. */
    private fun zeroSizeBox(type: String, payload: ByteArray): ByteArray =
        byteArrayOf(0, 0, 0, 0) + type.toByteArray(Charsets.US_ASCII) + payload

    private val XMP_GUID_HEX = "BE7ACFCB97A942E89C71999491E3AFAC"

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }

    /** uuid box whose payload is the XMP GUID + XML text. */
    private fun xmpUuidBox(xml: String): ByteArray =
        bmffBox("uuid", hexToBytes(XMP_GUID_HEX) + xml.toByteArray(Charsets.ISO_8859_1))

    private val SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())

    private fun u16(v: Int, little: Boolean): ByteArray =
        if (little) byteArrayOf(v.toByte(), (v ushr 8).toByte()) else byteArrayOf((v ushr 8).toByte(), v.toByte())

    private fun u32(v: Int, little: Boolean): ByteArray =
        if (little) {
            byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
        } else {
            byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        }

    /** Minimal synthetic EXIF APP1 segment: Exif\0\0 + TIFF header + IFD0(entries). */
    private fun exifApp1(littleEndian: Boolean, entries: List<Triple<Int, Int, Int>>): ByteArray {
        val byteOrder = if (littleEndian) byteArrayOf(0x49, 0x49) else byteArrayOf(0x4D, 0x4D)
        val tiffHeader = byteOrder + u16(42, littleEndian) + u32(8, littleEndian)
        val count = u16(entries.size, littleEndian)
        val entryBytes = entries.fold(ByteArray(0)) { acc, (tag, type, value) ->
            val valueField = u16(value, littleEndian) + byteArrayOf(0, 0)
            acc + u16(tag, littleEndian) + u16(type, littleEndian) + u32(1, littleEndian) + valueField
        }
        val ifd0 = count + entryBytes + u32(0, littleEndian)
        val tiff = tiffHeader + ifd0
        val exifHeader = "Exif".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0)
        val app1Payload = exifHeader + tiff
        val app1Length = app1Payload.size + 2
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte()) + u16(app1Length, false) + app1Payload
    }

    // ---- cr3Rating ----

    @Test
    fun `cr3Rating reads attribute-form rating`() {
        val header = bmffBox("ftyp", byteArrayOf(1, 2, 3, 4)) +
            xmpUuidBox("""<x:xmpmeta xmp:Rating="3"></x:xmpmeta>""")
        assertEquals(3, ImageHeaderParser.cr3Rating(header))
    }

    @Test
    fun `cr3Rating reads element-form rating`() {
        val header = bmffBox("ftyp", ByteArray(0)) + xmpUuidBox("<xmp:Rating>5</xmp:Rating>")
        assertEquals(5, ImageHeaderParser.cr3Rating(header))
    }

    @Test
    fun `cr3Rating zero rating returns zero not null`() {
        val header = xmpUuidBox("""xmp:Rating="0"""")
        assertEquals(0, ImageHeaderParser.cr3Rating(header))
    }

    @Test
    fun `cr3Rating rejected -1 rating maps to zero`() {
        val header = xmpUuidBox("""xmp:Rating="-1"""")
        assertEquals(0, ImageHeaderParser.cr3Rating(header))
    }

    @Test
    fun `cr3Rating clamps values above five`() {
        val header = xmpUuidBox("""xmp:Rating="42"""")
        assertEquals(5, ImageHeaderParser.cr3Rating(header))
    }

    @Test
    fun `cr3Rating clamps values below -1 to zero`() {
        val header = xmpUuidBox("""xmp:Rating="-99"""")
        assertEquals(0, ImageHeaderParser.cr3Rating(header))
    }

    @Test
    fun `cr3Rating uuid box with foreign guid returns null`() {
        val foreignGuid = ByteArray(16)
        val payload = foreignGuid + """xmp:Rating="3"""".toByteArray(Charsets.ISO_8859_1)
        val header = bmffBox("uuid", payload)
        assertNull(ImageHeaderParser.cr3Rating(header))
    }

    @Test
    fun `cr3Rating non-bmff jpeg bytes returns null`() {
        val header = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(),
            0x00, 0x10, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
        )
        assertNull(ImageHeaderParser.cr3Rating(header))
    }

    @Test
    fun `cr3Rating skips 64-bit largesize box to find rating in following box`() {
        val bigSkip = bmffLargeBox("skip", ByteArray(50))
        val header = bigSkip + xmpUuidBox("""xmp:Rating="4"""")
        assertEquals(4, ImageHeaderParser.cr3Rating(header))
    }

    @Test
    fun `cr3Rating aborts entire scan on invalid box size in 2 to 7`() {
        val invalid = invalidSizeBox(3, "bad!")
        val header = invalid + xmpUuidBox("""xmp:Rating="4"""")
        assertNull(ImageHeaderParser.cr3Rating(header))
    }

    @Test
    fun `cr3Rating handles zero-size box extending to end of buffer`() {
        val header = zeroSizeBox("uuid", hexToBytes(XMP_GUID_HEX) + """xmp:Rating="2"""".toByteArray(Charsets.ISO_8859_1))
        assertEquals(2, ImageHeaderParser.cr3Rating(header))
    }

    @Test
    fun `cr3Rating truncated box does not crash and returns null`() {
        val full = xmpUuidBox("""xmp:Rating="3"""")
        val truncated = full.copyOf(full.size - 10)
        assertNull(ImageHeaderParser.cr3Rating(truncated))
    }

    @Test
    fun `cr3Rating truncated largesize field does not crash and returns null`() {
        // size==1 header, but buffer ends before the 8-byte largesize is complete
        val partial = byteArrayOf(0, 0, 0, 1) + "uuid".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0, 0)
        assertNull(ImageHeaderParser.cr3Rating(partial))
    }

    @Test
    fun `cr3Rating empty buffer returns null`() {
        assertNull(ImageHeaderParser.cr3Rating(ByteArray(0)))
    }

    // ---- jpegRating ----

    @Test
    fun `jpegRating little-endian reads tag 0x4746`() {
        val header = SOI + exifApp1(littleEndian = true, entries = listOf(Triple(0x4746, 3, 2)))
        assertEquals(2, ImageHeaderParser.jpegRating(header))
    }

    @Test
    fun `jpegRating big-endian reads tag 0x4746`() {
        val header = SOI + exifApp1(littleEndian = false, entries = listOf(Triple(0x4746, 3, 2)))
        assertEquals(2, ImageHeaderParser.jpegRating(header))
    }

    @Test
    fun `jpegRating without rating tag returns null`() {
        val header = SOI + exifApp1(littleEndian = true, entries = listOf(Triple(0x0112, 3, 1)))
        assertNull(ImageHeaderParser.jpegRating(header))
    }

    @Test
    fun `jpegRating finds tag among multiple entries`() {
        val header = SOI + exifApp1(
            littleEndian = true,
            entries = listOf(Triple(0x0112, 3, 1), Triple(0x4746, 3, 4), Triple(0x0213, 3, 1)),
        )
        assertEquals(4, ImageHeaderParser.jpegRating(header))
    }

    @Test
    fun `jpegRating no APP1 segment returns null`() {
        val header = SOI + byteArrayOf(0xFF.toByte(), 0xDB.toByte(), 0x00, 0x03, 0x01)
        assertNull(ImageHeaderParser.jpegRating(header))
    }

    @Test
    fun `jpegRating truncated exif does not crash and returns null`() {
        val full = SOI + exifApp1(littleEndian = true, entries = listOf(Triple(0x4746, 3, 2)))
        val truncated = full.copyOf(full.size - 5)
        assertNull(ImageHeaderParser.jpegRating(truncated))
    }

    @Test
    fun `jpegRating ifd0 offset beyond buffer does not crash and returns null`() {
        val exifHeader = "Exif".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0)
        val tiff = byteArrayOf(0x49, 0x49) + byteArrayOf(42, 0) + byteArrayOf(100, 0, 0, 0) // IFD0 offset=100, way OOB
        val app1Payload = exifHeader + tiff
        val app1Len = app1Payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte()) + u16(app1Len, false) + app1Payload
        val header = SOI + app1
        assertNull(ImageHeaderParser.jpegRating(header))
    }

    // ---- embeddedJpeg ----

    @Test
    fun `embeddedJpeg returns exact slice between SOI and EOI`() {
        val garbageBefore = byteArrayOf(1, 2, 3, 4, 5)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x00, 0x10) +
            ByteArray(10) { it.toByte() } + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val garbageAfter = byteArrayOf(9, 9, 9)
        val buf = garbageBefore + jpeg + garbageAfter
        val result = ImageHeaderParser.embeddedJpeg(buf)
        assertArrayEquals(jpeg, result)
    }

    @Test
    fun `embeddedJpeg returns null when EOI missing`() {
        // valid SOI marker (FFD8 FFE0) but no EOI → still null
        val buf = byteArrayOf(1, 2) + byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + byteArrayOf(3, 4, 5)
        assertNull(ImageHeaderParser.embeddedJpeg(buf))
    }

    @Test
    fun `embeddedJpeg skips a false FFD8FF whose marker byte is invalid`() {
        // Canon metadata can contain FFD8FF followed by a non-marker byte (e.g. 0xBF) before the
        // real thumbnail; the parser must skip it and find the genuine JPEG (FFD8 FFDB).
        val fake = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xBF.toByte(), 0x11, 0x22)
        val real = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDB.toByte()) +
            byteArrayOf(1, 2, 3) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val result = ImageHeaderParser.embeddedJpeg(fake + real)
        assertArrayEquals(real, result)
    }

    @Test
    fun `embeddedJpeg returns null when SOI missing`() {
        val buf = byteArrayOf(1, 2, 3, 4, 5)
        assertNull(ImageHeaderParser.embeddedJpeg(buf))
    }

    @Test
    fun `embeddedJpeg respects minStart offset`() {
        val firstJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + byteArrayOf(1, 2) +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val secondJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + byteArrayOf(9, 9) +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val buf = firstJpeg + secondJpeg
        val result = ImageHeaderParser.embeddedJpeg(buf, minStart = firstJpeg.size)
        assertArrayEquals(secondJpeg, result)
    }

    // ---- largestEmbeddedJpeg ----

    @Test
    fun `largestEmbeddedJpeg picks the biggest of several JPEGs`() {
        val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) // valid marker
        val eoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val small = soi + ByteArray(4) + eoi
        val big = soi + ByteArray(40) { 7 } + eoi
        val mid = soi + ByteArray(15) + eoi
        val buf = byteArrayOf(1, 2) + small + byteArrayOf(3) + big + byteArrayOf(4) + mid
        assertArrayEquals(big, ImageHeaderParser.largestEmbeddedJpeg(buf))
    }

    @Test
    fun `largestEmbeddedJpeg ignores an incomplete trailing JPEG`() {
        val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val complete = soi + ByteArray(6) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val truncated = soi + ByteArray(20) // valid SOI, no EOI
        val buf = complete + truncated
        assertArrayEquals(complete, ImageHeaderParser.largestEmbeddedJpeg(buf))
    }

    @Test
    fun `largestEmbeddedJpeg returns null when no complete JPEG`() {
        assertNull(ImageHeaderParser.largestEmbeddedJpeg(byteArrayOf(1, 2, 3, 4, 5)))
    }

    // ---- rating dispatch ----

    @Test
    fun `rating dispatches CR3 to cr3Rating`() {
        val header = xmpUuidBox("""xmp:Rating="3"""")
        assertEquals(3, ImageHeaderParser.rating(FileKind.CR3, header))
    }

    @Test
    fun `rating dispatches JPEG to jpegRating`() {
        val header = SOI + exifApp1(littleEndian = true, entries = listOf(Triple(0x4746, 3, 2)))
        assertEquals(2, ImageHeaderParser.rating(FileKind.JPEG, header))
    }

    @Test
    fun `rating dispatches HEIF to cr3Rating as best effort`() {
        val header = xmpUuidBox("""xmp:Rating="1"""")
        assertEquals(1, ImageHeaderParser.rating(FileKind.HEIF, header))
    }

    @Test
    fun `rating returns null for OTHER regardless of content`() {
        val header = xmpUuidBox("""xmp:Rating="3"""")
        assertNull(ImageHeaderParser.rating(FileKind.OTHER, header))
    }
}
