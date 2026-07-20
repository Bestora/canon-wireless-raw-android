package io.github.canonwirelessraw.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class CanonBlePairingTest {
    @Test
    fun `uuid builds canon 128bit from short`() {
        val expected = UUID.fromString("00010006-0000-1000-0000-d8492fffa821")
        val actual = CanonBlePairing.uuid("00010006")
        assertEquals(expected, actual)
    }

    @Test
    fun `pairing writes have correct prefixes and order`() {
        val guid = ByteArray(16) { it.toByte() }
        val w = CanonBlePairing.pairingWrites("S25", guid)

        assertEquals(4, w.size)

        // [0] = 0x01 + "S25"
        assertEquals(0x01.toByte(), w[0][0])
        assertEquals(4, w[0].size) // 1 byte prefix + 3 chars
        val name0 = String(w[0], 1, w[0].size - 1)
        assertEquals("S25", name0)

        // [1] = 0x03 + guid (16 bytes at positions 1-16)
        assertEquals(0x03.toByte(), w[1][0])
        assertEquals(17, w[1].size)
        val guidSlice = w[1].copyOfRange(1, 17)
        assertEquals(16, guidSlice.size)
        for (i in 0 until 16) {
            assertEquals("guid[$i] mismatch", guid[i], guidSlice[i])
        }

        // [2] = 0x04 + "S25"
        assertEquals(0x04.toByte(), w[2][0])
        assertEquals(4, w[2].size)
        val name2 = String(w[2], 1, w[2].size - 1)
        assertEquals("S25", name2)

        // [3] = 0x05 + {0x02}
        assertEquals(0x05.toByte(), w[3][0])
        assertEquals(2, w[3].size)
        assertEquals(0x02.toByte(), w[3][1])
    }

    @Test
    fun `pairing write 2 rejects wrong guid length`() {
        try {
            CanonBlePairing.pairingWrites("S25", ByteArray(8))
            fail("Expected IllegalArgumentException for non-16-byte guid")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
