package de.bestora.canonwirelessrawandroid.ui

import de.bestora.canonwirelessrawandroid.data.GalleryItem
import de.bestora.canonwirelessrawandroid.ptp.CameraObject
import de.bestora.canonwirelessrawandroid.ptp.FileKind
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryFilterTest {
    private fun item(handle: Int, rating: Int?) = GalleryItem(
        obj = CameraObject(handle, "IMG_$handle.CR3", 0L, 0L, FileKind.CR3, 0),
        rating = rating,
        orientation = 0,
        thumbFile = null,
    )

    private val items = listOf(item(1, null), item(2, 0), item(3, 3), item(4, 4), item(5, 5))

    @Test
    fun `empty selection shows everything including unrated`() {
        assertEquals(items, filterByRating(items, emptySet()))
    }

    @Test
    fun `exact match, not at-least`() {
        assertEquals(listOf(3), filterByRating(items, setOf(3)).map { it.obj.handle })
    }

    @Test
    fun `multi-select combines ratings and hides unrated`() {
        assertEquals(listOf(3, 4, 5), filterByRating(items, setOf(3, 4, 5)).map { it.obj.handle })
    }

    private fun shape(cells: List<GalleryItem?>) = cells.map { it?.obj?.handle ?: 0 }

    @Test
    fun `live photos fill their own top row and leave older rows in place`() {
        val old = (1..6).map { item(it, null) }
        val n = (11..15).map { item(it, null) }.reversed() // newest first: 15, 14, …

        assertEquals(listOf(1, 2, 3, 4, 5, 6), shape(galleryCells(old, 0, 4)))
        assertEquals(listOf(12, 11, 0, 0, 1, 2, 3, 4, 5, 6), shape(galleryCells(n.takeLast(2) + old, 2, 4)))
        assertEquals(listOf(14, 13, 12, 11, 1, 2, 3, 4, 5, 6), shape(galleryCells(n.takeLast(4) + old, 4, 4)))
        assertEquals(listOf(15, 0, 0, 0, 14, 13, 12, 11, 1, 2, 3, 4, 5, 6), shape(galleryCells(n + old, 5, 4)))
    }

    @Test
    fun `day headers split the grid and live gaps stay inside the newest day`() {
        fun dated(handle: Int, day: Int) = item(handle, null).let { it.copy(obj = it.obj.copy(takenAtMillis = day.toLong())) }
        val items = listOf(dated(9, 2), dated(8, 2), dated(7, 1), dated(6, 1), dated(5, 1))
        val entries = galleryEntries(items, liveCount = 1, columns = 3) { "D$it" }
        val shape = entries.map {
            when (it) {
                is GridEntry.Day -> it.label
                is GridEntry.Photo -> it.item.obj.handle.toString()
                is GridEntry.Gap -> "-"
            }
        }
        assertEquals(listOf("D2", "9", "-", "-", "8", "D1", "7", "6", "5"), shape)
    }
}

