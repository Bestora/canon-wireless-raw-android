package io.github.canonwirelessraw.ui

import io.github.canonwirelessraw.ptp.PtpException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadScreenTest {

    @Test
    fun `cancel sentinel stops the batch`() {
        assertTrue(isCancelDownload(PtpException("download", -99)))
    }

    @Test
    fun `other download failure keeps the batch going`() {
        assertFalse(isCancelDownload(PtpException("download", -98)))
        assertFalse(isCancelDownload(RuntimeException("boom")))
    }
}
