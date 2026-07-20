package io.github.canonwirelessraw.cr3

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Cr3ContainerTest {

    private fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun hex(s: String) = ByteArray(s.length / 2) { ((Character.digit(s[it * 2], 16) shl 4) + Character.digit(s[it * 2 + 1], 16)).toByte() }

    /** [size:4][type:4][payload] */
    private fun box(type: String, payload: ByteArray): ByteArray =
        u32(8 + payload.size) + type.toByteArray(Charsets.US_ASCII) + payload

    /** uuid box: [size:4]["uuid"][guid:16][payload] */
    private fun uuidBox(guidHex: String, payload: ByteArray): ByteArray =
        u32(8 + 16 + payload.size) + "uuid".toByteArray(Charsets.US_ASCII) + hex(guidHex) + payload

    private val CANON = "85c0b687820f11e08111f4ce462b6a48"
    private val PREVIEW = "eaf42b5e1c984b88b9fbb7dc406e4d16"

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDB.toByte()) +
        byteArrayOf(1, 2, 3, 4, 5) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    /** THMB payload: version(4) w(2) h(2) jpegSize(4) pad(2) + jpeg */
    private fun thmbBox(jpeg: ByteArray): ByteArray =
        box("THMB", u32(0) + u16(160) + u16(120) + u32(jpeg.size) + u16(1) + jpeg)

    /** CMT1 EXIF (little-endian TIFF) with IFD0 orientation tag 0x0112 = [value]. */
    private fun cmt1(orientation: Int): ByteArray {
        // II, magic 42, IFD0 at offset 8; one entry: tag 0x0112, type SHORT(3), count 1, value.
        val le16 = { v: Int -> byteArrayOf(v.toByte(), (v ushr 8).toByte()) }
        val le32 = { v: Int -> byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte()) }
        val tiff = byteArrayOf(0x49, 0x49) + le16(42) + le32(8) +
            le16(1) + // entry count
            le16(0x0112) + le16(3) + le32(1) + le16(orientation) + le16(0) // tag,type,count,value(4)
        return box("CMT1", tiff)
    }

    private fun cr3(canonChildren: ByteArray, extraTopLevel: ByteArray = ByteArray(0)): ByteArray {
        val canonUuid = uuidBox(CANON, canonChildren)
        val moov = box("moov", canonUuid)
        val ftyp = box("ftyp", ByteArray(8))
        return ftyp + moov + extraTopLevel
    }

    @Test
    fun `thumbnailJpeg extracts the THMB jpeg`() {
        val buf = cr3(cmt1(1) + thmbBox(jpeg))
        assertArrayEquals(jpeg, Cr3Container.thumbnailJpeg(buf))
    }

    @Test
    fun `thumbnailJpeg is not fooled by a false FFD8FF in preceding metadata`() {
        // stray marker-like bytes in CMT1 must not be picked up (the whole point of box parsing)
        val fakeMeta = box("CMT3", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xBF.toByte(), 1, 2, 3))
        val buf = cr3(fakeMeta + thmbBox(jpeg))
        assertArrayEquals(jpeg, Cr3Container.thumbnailJpeg(buf))
    }

    @Test
    fun `thumbnailJpeg returns null without a THMB box`() {
        assertNull(Cr3Container.thumbnailJpeg(cr3(cmt1(1))))
    }

    @Test
    fun `previewJpeg extracts jpeg from the preview uuid box`() {
        val big = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDB.toByte()) + ByteArray(30) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val buf = cr3(thmbBox(jpeg), extraTopLevel = uuidBox(PREVIEW, ByteArray(8) + big))
        assertArrayEquals(big, Cr3Container.previewJpeg(buf))
    }

    @Test
    fun `orientation reads tag 0x0112 from CMT1`() {
        val buf = cr3(cmt1(8) + thmbBox(jpeg))
        assertEquals(8, Cr3Container.orientation(buf))
    }

    @Test
    fun `orientation defaults to 1 when absent`() {
        assertEquals(1, Cr3Container.orientation(cr3(thmbBox(jpeg))))
    }
}
