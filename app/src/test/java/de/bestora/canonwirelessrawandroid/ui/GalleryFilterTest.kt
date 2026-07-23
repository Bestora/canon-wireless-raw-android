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
}
