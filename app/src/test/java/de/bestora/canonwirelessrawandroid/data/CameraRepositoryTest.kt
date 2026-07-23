package de.bestora.canonwirelessrawandroid.data

import de.bestora.canonwirelessrawandroid.ptp.CameraObject
import de.bestora.canonwirelessrawandroid.ptp.FileKind
import de.bestora.canonwirelessrawandroid.ptp.PtpPort
import java.io.OutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CameraRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ---- synthetic-fixture helpers (copied from ImageHeaderParserTest) ----

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

    private val XMP_GUID_HEX = "BE7ACFCB97A942E89C71999491E3AFAC"

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }

    /** uuid box whose payload is the XMP GUID + XML text. */
    private fun xmpUuidBox(xml: String): ByteArray =
        bmffBox("uuid", hexToBytes(XMP_GUID_HEX) + xml.toByteArray(Charsets.ISO_8859_1))

    private fun uuidBox(guidHex: String, payload: ByteArray): ByteArray =
        bmffBox("uuid", hexToBytes(guidHex) + payload)

    private fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    /** THMB box: version(4) w(2) h(2) jpegSize(4) pad(2) + jpeg */
    private fun thmbBox(jpeg: ByteArray): ByteArray =
        bmffBox("THMB", u32(0) + u16(160) + u16(120) + u32(jpeg.size) + u16(1) + jpeg)

    // ---- test fixtures ----

    private val previewJpeg = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDB.toByte(),
        0x01, 0x02, 0x03, 0x04,
        0xFF.toByte(), 0xD9.toByte(),
    )

    /** CR3-like BMFF: ftyp + moov[ canon-uuid[ THMB(thumbnail) ] ] + xmp uuid (rating 3).
     *  Thumbnail now comes from the THMB box (Cr3Container), rating from the XMP uuid. */
    private val cr3Header =
        bmffBox("ftyp", byteArrayOf(0, 0, 0, 0)) +
            bmffBox("moov", uuidBox("85C0B687820F11E08111F4CE462B6A48", thmbBox(previewJpeg))) +
            xmpUuidBox("""<x:xmpmeta xmp:Rating="3"></x:xmpmeta>""")

    /** JPEG SOI + APP0 only: no rating tag, no EOI → parser yields null rating and null thumb. */
    private val jpegHeader = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00, 0x04, 0xAA.toByte(), 0xBB.toByte(),
    )

    private val cr3Size = cr3Header.size.toLong()
    private val jpegSize = jpegHeader.size.toLong()

    private val cr3 = CameraObject(handle = 10, name = "IMG_0001.CR3", size = cr3Size, takenAtMillis = 1000, kind = FileKind.CR3, format = 0)
    private val jpeg = CameraObject(handle = 20, name = "IMG_0002.JPG", size = jpegSize, takenAtMillis = 2000, kind = FileKind.JPEG, format = 0)
    private val mp4 = CameraObject(handle = 30, name = "MVI_0003.MP4", size = 999, takenAtMillis = 500, kind = FileKind.OTHER, format = 0)

    private val allObjects = listOf(cr3, jpeg, mp4)
    private val headers = mapOf(10 to cr3Header, 20 to jpegHeader)

    private class FakePtpPort(
        private val objects: List<CameraObject>,
        private val headers: Map<Int, ByteArray>,
        private val failReadOn: Set<Int> = emptySet(),
    ) : PtpPort {
        val readCounts = mutableMapOf<Int, Int>()

        override suspend fun connect(ip: String, port: Int, name: String, guid: ByteArray, eosMode: Boolean) {}
        override suspend fun listObjects(): List<CameraObject> = objects
        override suspend fun listHandles(): List<Int> = objects.map { it.handle }
        override suspend fun objectInfo(handle: Int): CameraObject? = objects.firstOrNull { it.handle == handle }
        override suspend fun readPartial(handle: Int, offset: Long, len: Int): ByteArray {
            readCounts[handle] = (readCounts[handle] ?: 0) + 1
            if (handle in failReadOn) throw RuntimeException("simulated read failure for $handle")
            val h = headers[handle] ?: ByteArray(0)
            val from = offset.toInt().coerceIn(0, h.size)
            val to = (from + len).coerceAtMost(h.size)
            return h.copyOfRange(from, to)
        }
        override suspend fun downloadTo(obj: CameraObject, sink: OutputStream, chunk: Int, onProgress: (Long) -> Unit) {}
        override fun cancelIo() {}
        override suspend fun disconnect() {}
    }

    private class FakePrefs : PrefsPort {
        override var lastIp: String? = null
        override fun pairingGuid(): ByteArray = ByteArray(16)
    }

    private fun repo(fake: PtpPort, cache: MetaCache = MetaCache(tempFolder.root)) =
        CameraRepository(fake, cache, FakePrefs())

    @Test
    fun `refreshList filters OTHER and lists handles newest-first (reversed)`() = runTest {
        val r = repo(FakePtpPort(allObjects, headers))
        r.refreshList()

        val items = r.items.value
        assertEquals(2, items.size)
        assertTrue(items.none { it.obj.kind == FileKind.OTHER })
        // handles [10,20,30] reversed = [30,20,10]; mp4(30) filtered out → jpeg(20), cr3(10)
        assertEquals(20, items[0].obj.handle)
        assertEquals(10, items[1].obj.handle)
    }

    @Test
    fun `ensureMeta parses rating and thumb then caches without re-reading`() = runTest {
        val fake = FakePtpPort(allObjects, headers)
        val r = repo(fake)
        r.refreshList()

        r.ensureMeta(10)

        val item = r.items.value.first { it.obj.handle == 10 }
        assertEquals(3, item.rating)
        assertNotNull(item.thumbFile)
        assertArrayEquals(previewJpeg, item.thumbFile!!.readBytes())
        assertEquals(1, fake.readCounts[10])

        // second call: already has meta → fake not touched again
        r.ensureMeta(10)
        assertEquals(1, fake.readCounts[10])
    }

    @Test
    fun `scanAllMeta reports progress for each item without meta`() = runTest {
        val r = repo(FakePtpPort(allObjects, headers))
        r.refreshList()

        val progress = mutableListOf<Pair<Int, Int>>()
        r.scanAllMeta { done, total -> progress += done to total }

        assertEquals(listOf(1 to 2, 2 to 2), progress)
    }

    @Test
    fun `scanAllMeta continues past a failing header read`() = runTest {
        val fake = FakePtpPort(allObjects, headers, failReadOn = setOf(10))
        val r = repo(fake)
        r.refreshList()

        val progress = mutableListOf<Pair<Int, Int>>()
        r.scanAllMeta { done, total -> progress += done to total }

        // both steps still reported despite the failure on handle 10
        assertEquals(listOf(1 to 2, 2 to 2), progress)
        assertNull(r.items.value.first { it.obj.handle == 10 }.rating)
    }

    @Test
    fun `refreshList populates rating and thumb from cache hits immediately`() = runTest {
        val cache = MetaCache(tempFolder.root)
        cache.put(10, cr3Size, 4, 1, byteArrayOf(9, 9, 9))
        val fake = FakePtpPort(allObjects, headers)
        val r = repo(fake, cache)

        r.refreshList()

        val item = r.items.value.first { it.obj.handle == 10 }
        assertEquals(4, item.rating)
        assertNotNull(item.thumbFile)

        // meta already present → ensureMeta is a no-op, no partial read
        r.ensureMeta(10)
        assertNull(fake.readCounts[10])
    }
}
