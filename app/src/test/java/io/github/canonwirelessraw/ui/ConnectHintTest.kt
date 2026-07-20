package io.github.canonwirelessraw.ui

import io.github.canonwirelessraw.ptp.PtpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectHintTest {

    @Test
    fun `pairing ack failure (-2) shows camera confirm hint`() {
        val hint = connectHint(PtpException("connect", -2))
        assertEquals(
            "Confirm the connection on the camera and try again immediately " +
                "(the time window is only a few seconds).",
            hint,
        )
    }

    @Test
    fun `tcp connect failure (-1) shows network hint`() {
        val hint = connectHint(PtpException("connect", -1))
        assertEquals(
            "Camera not reachable — check the IP and make sure your phone is connected to " +
                "the camera's Wi-Fi.",
            hint,
        )
    }

    @Test
    fun `other exception has no extra hint`() {
        assertNull(connectHint(RuntimeException("boom")))
    }
}
