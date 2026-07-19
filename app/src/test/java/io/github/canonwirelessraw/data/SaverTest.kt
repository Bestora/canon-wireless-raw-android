package io.github.canonwirelessraw.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SaverTest {

    @Test
    fun `path traversal is stripped to the final segment`() {
        assertEquals("x.cr3", Saver.sanitizeDisplayName("../../x.cr3"))
        assertEquals("x.cr3", Saver.sanitizeDisplayName("..\\..\\x.cr3"))
    }

    @Test
    fun `blank name falls back to image`() {
        assertEquals("image", Saver.sanitizeDisplayName(""))
        assertEquals("image", Saver.sanitizeDisplayName("foo/"))
    }

    @Test
    fun `plain name is unchanged`() {
        assertEquals("IMG_1234.CR3", Saver.sanitizeDisplayName("IMG_1234.CR3"))
    }
}
