package de.bestora.canonwirelessrawandroid.data

import de.bestora.canonwirelessrawandroid.ptp.CameraObject
import de.bestora.canonwirelessrawandroid.ptp.DeviceInfo
import de.bestora.canonwirelessrawandroid.ptp.FileKind
import de.bestora.canonwirelessrawandroid.ptp.PtpException
import de.bestora.canonwirelessrawandroid.ptp.PtpPort
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
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
        initialObjects: List<CameraObject>,
        private val headers: Map<Int, ByteArray>,
        private val failReadOn: Set<Int> = emptySet(),
        /** Codes thrown by successive connect() calls before one succeeds. */
        val connectFailures: ArrayDeque<Int> = ArrayDeque(),
        /** Codes thrown by successive reads (readPartial, or downloadTo past [failDownloadAt]). */
        val ioFailures: ArrayDeque<Int> = ArrayDeque(),
        private val failDownloadAt: Long = Long.MAX_VALUE,
    ) : PtpPort {
        val objects = initialObjects.toMutableList()
        val readCounts = mutableMapOf<Int, Int>()
        var connects = 0
        val downloadStarts = mutableListOf<Long>()
        private var killSwitch = false

        var pings = 0
        val pingFailures: ArrayDeque<Int> = ArrayDeque()

        override suspend fun connect(ip: String, port: Int, name: String, guid: ByteArray, remoteMode: Int): DeviceInfo {
            connects++
            killSwitch = false
            connectFailures.removeFirstOrNull()?.let { throw PtpException("connect", it) }
            return DeviceInfo("Canon EOS R5", "0123SERIAL")
        }
        override suspend fun ping() {
            pings++
            pingFailures.removeFirstOrNull()?.let { throw PtpException("ping", it) }
        }
        /** GetEvent payloads to hand out, one per poll; empty once drained. */
        val eventPayloads: ArrayDeque<ByteArray> = ArrayDeque()
        override suspend fun events(): ByteArray = eventPayloads.removeFirstOrNull() ?: ByteArray(0)
        override suspend fun listHandles(): List<Int> = objects.map { it.handle }
        var infoCalls = 0
        override suspend fun objectInfo(handle: Int): CameraObject? {
            infoCalls++
            return objects.firstOrNull { it.handle == handle }
        }
        override suspend fun readPartial(handle: Int, offset: Long, len: Int): ByteArray {
            readCounts[handle] = (readCounts[handle] ?: 0) + 1
            yield() // a real transfer suspends; lets concurrent callers interleave
            if (handle in failReadOn) throw RuntimeException("simulated read failure for $handle")
            ioFailures.removeFirstOrNull()?.let { throw PtpException("getPartialObject", it) }
            val h = headers[handle] ?: ByteArray(0)
            val from = offset.toInt().coerceIn(0, h.size)
            val to = (from + len).coerceAtMost(h.size)
            return h.copyOfRange(from, to)
        }
        override suspend fun downloadTo(obj: CameraObject, sink: OutputStream, from: Long, chunk: Int, onProgress: (Long) -> Unit) {
            killSwitch = false // as PtpClient: a cancel stops the running transfer only
            downloadStarts += from
            val data = headers[obj.handle] ?: ByteArray(0)
            var offset = from
            while (offset < data.size) {
                if (killSwitch) throw PtpException("download", -99)
                if (offset >= failDownloadAt) ioFailures.removeFirstOrNull()?.let { throw PtpException("getPartialObject", it) }
                val end = minOf(offset + 4, data.size.toLong()) // tiny chunks, so a mid-file failure is reachable
                sink.write(data, offset.toInt(), (end - offset).toInt())
                offset = end
                onProgress(offset)
            }
        }
        override fun cancelIo() { killSwitch = true }
        override suspend fun disconnect() {}
    }

    private class FakePrefs : PrefsPort {
        override var lastIp: String? = null
        override fun pairingGuid(): ByteArray = ByteArray(16)
    }

    private fun TestScope.repo(fake: PtpPort, cache: MetaCache = MetaCache(tempFolder.root), lists: ObjectListCache? = null) =
        CameraRepository(fake, cache, FakePrefs(), backgroundScope, lists)

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

    @Test
    fun `connect retries while the camera waits for confirmation`() = runTest {
        val fake = FakePtpPort(allObjects, headers, connectFailures = ArrayDeque(listOf(-2, -2, -6)))
        val r = repo(fake)
        val seen = mutableListOf<Int>()

        r.connect("192.168.1.2") { seen += it.code }

        assertEquals(listOf(-2, -2, -6), seen)
        assertEquals(4, fake.connects)
        assertEquals(Link.CONNECTED, r.link.value)
    }

    @Test
    fun `connect gives up after the window with the last error`() = runTest {
        val fake = FakePtpPort(allObjects, headers, connectFailures = ArrayDeque(List(100) { -2 }))
        val r = repo(fake)

        val e = runCatching { r.connect("192.168.1.2", windowMs = 5_000) }.exceptionOrNull()

        assertEquals(-2, (e as PtpException).code)
        assertEquals(Link.DISCONNECTED, r.link.value)
    }

    @Test
    fun `dead session reconnects silently and retries the call`() = runTest {
        val fake = FakePtpPort(allObjects, headers)
        val r = repo(fake)
        r.connect("192.168.1.2")
        r.refreshList()
        fake.ioFailures += -5

        r.ensureMeta(10)

        assertEquals(3, r.items.value.first { it.obj.handle == 10 }.rating)
        assertEquals(2, fake.connects)
        assertEquals(Link.CONNECTED, r.link.value)
    }

    @Test
    fun `refused command keeps the session and is not retried`() = runTest {
        val fake = FakePtpPort(allObjects, headers)
        val r = repo(fake)
        r.connect("192.168.1.2")
        r.refreshList()
        fake.ioFailures += -8

        val e = runCatching { r.ensureMeta(10) }.exceptionOrNull()

        assertEquals(-8, (e as PtpException).code)
        assertEquals(1, fake.connects)
        assertEquals(Link.CONNECTED, r.link.value)
    }

    @Test
    fun `failed reconnect marks the link lost and later calls fail fast`() = runTest {
        val fake = FakePtpPort(allObjects, headers)
        val r = repo(fake)
        r.connect("192.168.1.2")
        r.refreshList()
        fake.ioFailures += -5
        fake.connectFailures += List(100) { -1 }

        val first = runCatching { r.ensureMeta(10) }.exceptionOrNull()
        val second = runCatching { r.ensureMeta(20) }.exceptionOrNull()

        assertTrue(first is ConnectionLostException)
        assertTrue(second is ConnectionLostException)
        assertEquals(Link.LOST, r.link.value)
        assertNull("camera must not be touched while lost", fake.readCounts[20])
    }

    @Test
    fun `concurrent failures share one reconnect`() = runTest {
        val fake = FakePtpPort(allObjects, headers)
        val r = repo(fake)
        r.connect("192.168.1.2")
        r.refreshList()
        fake.ioFailures += listOf(-5, -5)

        val a = async { r.ensureMeta(10) }
        val b = async { r.ensureMeta(20) }
        a.await()
        b.await()

        assertEquals(2, fake.connects)
        assertEquals(3, r.items.value.first { it.obj.handle == 10 }.rating)
    }

    @Test
    fun `download resumes at the reached offset after a reconnect`() = runTest {
        val fake = FakePtpPort(allObjects, headers, failDownloadAt = 8)
        val r = repo(fake)
        r.connect("192.168.1.2")
        fake.ioFailures += -5
        val out = ByteArrayOutputStream()

        r.transfer(cr3, out) {}

        assertArrayEquals(cr3Header, out.toByteArray())
        assertEquals(listOf(0L, 8L), fake.downloadStarts)
        assertEquals(2, fake.connects)
    }

    @Test
    fun `a cancelled download stops without touching the session`() = runTest {
        val fake = FakePtpPort(allObjects, headers)
        val r = repo(fake)
        r.connect("192.168.1.2")

        val e = runCatching { r.transfer(cr3, ByteArrayOutputStream()) { if (it == 4L) r.cancel() } }.exceptionOrNull()
        assertTrue((e as PtpException).cancelled)

        val out = ByteArrayOutputStream()
        r.transfer(cr3, out) {}
        assertArrayEquals(cr3Header, out.toByteArray())
        assertEquals("no reconnect needed after a cancel", 1, fake.connects)
        assertEquals(Link.CONNECTED, r.link.value)
    }

    @Test
    fun `keep-alive pings every 5 seconds while connected and stops on disconnect`() = runTest {
        val fake = FakePtpPort(allObjects, headers)
        val r = repo(fake)
        r.connect("192.168.1.2")

        advanceTimeBy(15_001)
        assertEquals(3, fake.pings)
        assertEquals("Canon EOS R5", r.model)

        r.disconnect()
        advanceTimeBy(15_000)
        assertEquals(3, fake.pings)
    }

    @Test
    fun `failed keep-alive ping reconnects silently`() = runTest {
        val fake = FakePtpPort(allObjects, headers)
        val r = repo(fake)
        r.connect("192.168.1.2")
        fake.pingFailures += -5

        advanceTimeBy(5_001)
        runCurrent()

        assertEquals(2, fake.connects)
        assertEquals(2, fake.pings)
        assertEquals(Link.CONNECTED, r.link.value)
    }

    private fun objectAddedEvent(handle: Int): ByteArray =
        java.nio.ByteBuffer.allocate(20).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(12).putInt(0xC181).putInt(handle) // object added
            .putInt(8).putInt(0) // terminator
            .array()

    @Test
    fun `photo shot while connected lands on top and counts as live`() = runTest {
        val fresh = CameraObject(handle = 40, name = "IMG_0004.CR3", size = cr3Size, takenAtMillis = 5000, kind = FileKind.CR3, format = 0)
        val fake = FakePtpPort(allObjects, headers)
        val r = repo(fake)
        r.connect("192.168.1.2")
        r.refreshList()

        fake.objects += fresh
        fake.eventPayloads += objectAddedEvent(40)
        advanceTimeBy(1_001)

        assertEquals(40, r.items.value.first().obj.handle)
        assertEquals(1, r.liveCount.value)

        r.refreshList()
        assertEquals(0, r.liveCount.value)
    }

    @Test
    fun `cancelling the rating scan stops it instead of skipping through the rest`() = runTest {
        val many = (100 until 140).map { CameraObject(it, "IMG_$it.CR3", cr3Size, it.toLong(), FileKind.CR3, 0) }
        val fake = FakePtpPort(many, many.associate { it.handle to cr3Header })
        val r = repo(fake)
        r.refreshList()

        var done = 0
        val scan = launch { r.scanAllMeta { d, _ -> done = d } }
        while (done < 3) yield()
        scan.cancel()
        scan.join()

        assertTrue("scan should stop near where it was cancelled, got $done", done < 10)
    }

    @Test
    fun `a photo recorded to both cards shows once, in the list and live`() = runTest {
        val card1 = cr3.copy(handle = 0x51000001)
        val card2 = cr3.copy(handle = 0x91000001.toInt())
        val fake = FakePtpPort(listOf(card1, card2, jpeg), mapOf(card1.handle to cr3Header, card2.handle to cr3Header))
        val r = repo(fake)
        r.connect("192.168.1.2")
        r.refreshList()
        assertEquals(1, r.items.value.count { it.obj.name == cr3.name })

        val shot1 = cr3.copy(handle = 0x51000002, name = "IMG_0005.CR3", takenAtMillis = 9000)
        val shot2 = shot1.copy(handle = 0x91000002.toInt())
        fake.objects += listOf(shot1, shot2)
        fake.eventPayloads += objectAddedEvent(shot1.handle)
        fake.eventPayloads += objectAddedEvent(shot2.handle)
        advanceTimeBy(2_001)

        assertEquals(1, r.items.value.count { it.obj.name == "IMG_0005.CR3" })
        assertEquals(1, r.liveCount.value)
    }

    private fun card(n: Int) = (1..n).map { CameraObject(it, "IMG_$it.CR3", 1000L + it, it * 1000L, FileKind.CR3, 0) }

    @Test
    fun `reconnect shows the cached list and only asks for what is new`() = runTest {
        val lists = ObjectListCache(tempFolder.newFolder("lists"))
        val first = FakePtpPort(card(60), emptyMap())
        repo(first, lists = lists).run { connect("192.168.1.2"); refreshList() }
        assertEquals(60, first.infoCalls)

        val again = FakePtpPort(card(62), emptyMap())
        val r = repo(again, lists = lists)
        r.connect("192.168.1.2")
        r.refreshList()

        assertEquals(62, r.items.value.size)
        assertEquals(62, r.items.value.first().obj.handle)
        assertTrue("3 spot checks + 2 new, got ${again.infoCalls}", again.infoCalls <= 5)
    }

    @Test
    fun `a cached list that no longer matches the card is thrown away`() = runTest {
        val lists = ObjectListCache(tempFolder.newFolder("lists"))
        repo(FakePtpPort(card(30), emptyMap()), lists = lists).run { connect("192.168.1.2"); refreshList() }

        val reformatted = FakePtpPort(card(30).map { it.copy(name = "NEW_${it.handle}.CR3") }, emptyMap())
        val r = repo(reformatted, lists = lists)
        r.connect("192.168.1.2")
        r.refreshList()

        assertTrue(r.items.value.all { it.obj.name.startsWith("NEW_") })
    }

    @Test
    fun `photos deleted on the camera drop out of the cached list`() = runTest {
        val lists = ObjectListCache(tempFolder.newFolder("lists"))
        repo(FakePtpPort(card(10), emptyMap()), lists = lists).run { connect("192.168.1.2"); refreshList() }

        val r = repo(FakePtpPort(card(10).filter { it.handle != 5 }, emptyMap()), lists = lists)
        r.connect("192.168.1.2")
        r.refreshList()

        assertEquals(9, r.items.value.size)
        assertTrue(r.items.value.none { it.obj.handle == 5 })
    }
}

