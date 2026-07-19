package io.github.canonwirelessraw.data

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MetaCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `put and get roundtrip with rating and thumbnail`() {
        val cache = MetaCache(tempFolder.root)
        val handle = 1
        val size = 12345L
        val rating = 4
        val thumbBytes = byteArrayOf(1, 2, 3, 4, 5)

        val result = cache.put(handle, size, rating, thumbBytes)

        assertEquals(rating, result.rating)
        assertTrue(result.thumbFile != null)
        assertTrue(result.thumbFile!!.exists())

        val retrieved = cache.get(handle, size)
        assertEquals(rating, retrieved?.rating)
        assertTrue(retrieved?.thumbFile != null)
        assertTrue(retrieved!!.thumbFile!!.exists())
        assertArrayEquals(thumbBytes, retrieved.thumbFile!!.readBytes())
    }

    @Test
    fun `put with null rating writes dash and get returns null rating`() {
        val cache = MetaCache(tempFolder.root)
        val handle = 2
        val size = 54321L

        val result = cache.put(handle, size, null, null)

        assertNull(result.rating)
        assertNull(result.thumbFile)

        val retrieved = cache.get(handle, size)
        assertNull(retrieved?.rating)
        assertNull(retrieved?.thumbFile)
    }

    @Test
    fun `get unknown handle returns null`() {
        val cache = MetaCache(tempFolder.root)
        val retrieved = cache.get(999, 999999L)
        assertNull(retrieved)
    }

    @Test
    fun `clear empties directory`() {
        val cache = MetaCache(tempFolder.root)
        cache.put(1, 100L, 5, byteArrayOf(1, 2, 3))
        cache.put(2, 200L, 3, byteArrayOf(4, 5, 6))

        assertTrue(tempFolder.root.listFiles()?.isNotEmpty() == true)

        cache.clear()

        assertTrue(tempFolder.root.listFiles()?.isEmpty() == true)
    }

    @Test
    fun `lazy mkdirs creates non-existent subdirectories`() {
        val cacheDir = File(tempFolder.root, "sub/cache")
        val cache = MetaCache(cacheDir)

        assertTrue(!cacheDir.exists())

        val result = cache.put(1, 100L, 3, byteArrayOf(10, 20))

        assertTrue(cacheDir.exists())
        assertEquals(3, result.rating)

        val retrieved = cache.get(1, 100L)
        assertEquals(3, retrieved?.rating)
        assertArrayEquals(byteArrayOf(10, 20), retrieved?.thumbFile!!.readBytes())
    }
}
