package de.bestora.canonwirelessrawandroid.ptp

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

class PtpDateParserTest {

    private fun localMillis(year: Int, month: Int, day: Int, hour: Int, min: Int, sec: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, min, sec)
        }.timeInMillis

    @Test
    fun `parses plain yyyyMMdd'T'HHmmss into local epoch millis`() {
        val expected = localMillis(2026, Calendar.JULY, 19, 14, 35, 2)
        assertEquals(expected, parsePtpDate("20260719T143502"))
    }

    @Test
    fun `parses date with dot suffix`() {
        val expected = localMillis(2026, Calendar.JULY, 19, 14, 35, 2)
        assertEquals(expected, parsePtpDate("20260719T143502.5"))
    }

    @Test
    fun `parses date with Z suffix`() {
        val expected = localMillis(2026, Calendar.JULY, 19, 14, 35, 2)
        assertEquals(expected, parsePtpDate("20260719T143502Z"))
    }

    @Test
    fun `invalid date string returns zero`() {
        assertEquals(0L, parsePtpDate("not-a-ptp-date"))
    }

    @Test
    fun `kindFromName is case-insensitive for cr3`() {
        assertEquals(FileKind.CR3, kindFromName("IMG_0001.CR3"))
    }

    @Test
    fun `kindFromName recognizes jpg`() {
        assertEquals(FileKind.JPEG, kindFromName("x.JPG"))
    }

    @Test
    fun `kindFromName recognizes jpeg`() {
        assertEquals(FileKind.JPEG, kindFromName("photo.jpeg"))
    }

    @Test
    fun `kindFromName recognizes heif variants`() {
        assertEquals(FileKind.HEIF, kindFromName("a.hif"))
        assertEquals(FileKind.HEIF, kindFromName("b.HEIF"))
    }

    @Test
    fun `kindFromName falls back to other`() {
        assertEquals(FileKind.OTHER, kindFromName("mov.MP4"))
    }
}
