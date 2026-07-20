package io.github.canonwirelessraw.ble

import org.junit.Test
import java.util.UUID

class CanonBlePairingTest {
    @Test
    fun `uuid builds canon 128bit from short`() {
        val expected = UUID.fromString("00010006-0000-1000-0000-d8492fffa821")
        val actual = CanonBlePairing.uuid("00010006")
        assert(expected == actual)
    }

    @Test
    fun `pairing writes have correct prefixes and order`() {
        val guid = ByteArray(16) { it.toByte() }
        val w = CanonBlePairing.pairingWrites("S25", guid)

        assert(w.size == 4)

        // [0] = 0x01 + "S25"
        assert(w[0][0] == 0x01.toByte())
        assert(w[0].size == 4) // 1 byte prefix + 3 chars
        val name0 = String(w[0], 1, w[0].size - 1)
        assert(name0 == "S25")

        // [1] = 0x03 + guid (16 bytes at positions 1-16)
        assert(w[1][0] == 0x03.toByte())
        assert(w[1].size == 17)
        for (i in 0 until 16) {
            assert(w[1][i + 1] == guid[i])
        }

        // [2] = 0x04 + "S25"
        assert(w[2][0] == 0x04.toByte())
        assert(w[2].size == 4)
        val name2 = String(w[2], 1, w[2].size - 1)
        assert(name2 == "S25")

        // [3] = 0x05 + {0x02}
        assert(w[3][0] == 0x05.toByte())
        assert(w[3].size == 2)
        assert(w[3][1] == 0x02.toByte())
    }

    @Test
    fun `pairing write 2 rejects wrong guid length`() {
        try {
            CanonBlePairing.pairingWrites("S25", ByteArray(8))
            throw AssertionError("Should have thrown IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
