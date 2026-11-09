package de.bestora.canonwirelessrawandroid.ui

import de.bestora.canonwirelessrawandroid.ptp.PtpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectHintTest {

    @Test
    fun `camera refusal (-2) after the pairing window shows camera confirm hint`() {
        val hint = connectHint(PtpException("connect", -2))
        assertEquals(
            "The camera did not confirm the connection in time. Accept the prompt on the camera " +
                "and try again.",
            hint,
        )
    }

    @Test
    fun `silent camera (-6) points at the remote control mode`() {
        val hint = connectHint(PtpException("connect", -6))
        assertEquals(
            "The camera did not answer. Make sure it is in Remote control (EOS Utility) mode " +
                "and waiting for a connection.",
            hint,
        )
    }

    @Test
    fun `live status asks for confirmation while the camera refuses`() {
        assertEquals("Confirm the connection on the camera…", connectStatus(PtpException("connect", -2)))
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
