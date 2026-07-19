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
            "Bestätige die Verbindung an der Kamera und versuche es sofort erneut " +
                "(Zeitfenster nur wenige Sekunden).",
            hint,
        )
    }

    @Test
    fun `tcp connect failure (-1) shows network hint`() {
        val hint = connectHint(PtpException("connect", -1))
        assertEquals(
            "Kamera nicht erreichbar — IP prüfen und sicherstellen, dass das Handy mit dem " +
                "WLAN der Kamera verbunden ist.",
            hint,
        )
    }

    @Test
    fun `other exception has no extra hint`() {
        assertNull(connectHint(RuntimeException("boom")))
    }
}
