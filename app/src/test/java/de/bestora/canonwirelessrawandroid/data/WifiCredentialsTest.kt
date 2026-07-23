package de.bestora.canonwirelessrawandroid.data

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WifiCredentialsTest {
    @Test fun `valid credentials return null`() {
        assertNull(validateCredentials("EOS-R5-abc", "12345678"))
    }
    @Test fun `empty ssid rejected`() {
        assertNotNull(validateCredentials("", "12345678"))
    }
    @Test fun `ssid over 32 chars rejected`() {
        assertNotNull(validateCredentials("x".repeat(33), "12345678"))
    }
    @Test fun `psk under 8 chars rejected`() {
        assertNotNull(validateCredentials("EOS", "1234567"))
    }
    @Test fun `psk over 63 chars rejected`() {
        assertNotNull(validateCredentials("EOS", "x".repeat(64)))
    }
}
