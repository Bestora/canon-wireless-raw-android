package de.bestora.canonwirelessrawandroid.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanonBleProtocolTest {

    private val guid = ByteArray(16) { it.toByte() } // 00 01 02 … 0f

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `pairing request is 01 plus the ASCII name`() {
        assertArrayEquals(bytes(0x01, 'S'.code, 'M'.code), CanonBleProtocol.pairingRequest("SM"))
    }

    @Test
    fun `registration sends the GUID byte-reversed, then name, then phone type`() {
        val writes = CanonBleProtocol.registration("SM", guid)
        assertArrayEquals(byteArrayOf(0x03) + ByteArray(16) { (15 - it).toByte() }, writes[0])
        assertArrayEquals(bytes(0x04, 'S'.code, 'M'.code), writes[1])
        assertArrayEquals(bytes(0x05, 0x02), writes[2])
    }

    @Test
    fun `device name is printable ASCII and at most 16 characters`() {
        assertEquals("SM-S938B", CanonBleProtocol.deviceName("SM-S938B"))
        assertEquals("Pixel 9 Pro XL", CanonBleProtocol.deviceName("Pixel 9 Pro XL ü"))
        assertEquals("ABCDEFGHIJKLMNOP", CanonBleProtocol.deviceName("ABCDEFGHIJKLMNOPQRS"))
        assertEquals("Android", CanonBleProtocol.deviceName("ü"))
    }

    @Test
    fun `AP config parses little-endian IP and BSSID and the wait time`() {
        val value = bytes(
            0x10, 0x00,
            0x10, 0x04, 0x02, 0x01, 0xA8, 0xC0, // 192.168.1.2
            0x02, 0x06, 0x1E, 0x95, 0xC0, 0x83, 0x53, 0xD6, // D6:53:83:C0:95:1E
            0x30, 0x01, 0x14, // 20 × 100 ms
        )
        val ap = CanonBleProtocol.parseApConfig(value)!!
        assertEquals("192.168.1.2", ap.ip)
        assertEquals("D6:53:83:C0:95:1E", ap.bssid)
        assertEquals(2_000L, ap.waitMs)
    }

    @Test
    fun `AP config without an IP or with a truncated entry is rejected`() {
        assertNull(CanonBleProtocol.parseApConfig(bytes(0x10, 0x00, 0x30, 0x01, 0x05)))
        assertNull(CanonBleProtocol.parseApConfig(bytes(0x10, 0x00, 0x10, 0x04, 0x02, 0x01)))
    }

    @Test
    fun `SSID and password are trimmed of NUL padding`() {
        assertEquals("EOSR5-812_Canon0A", CanonBleProtocol.asciiField("EOSR5-812_Canon0A".toByteArray() + ByteArray(4)))
    }

    @Test
    fun `security list is little-endian int32`() {
        assertEquals(listOf(22, 9), CanonBleProtocol.parseSecurityTypes(bytes(22, 0, 0, 0, 9, 0, 0, 0)))
    }

    @Test
    fun `handover status codes`() {
        assertEquals(HandoverStatus.STARTED, CanonBleProtocol.parseHandoverStatus(bytes(0x01, 0x03)))
        assertEquals(HandoverStatus.STARTED, CanonBleProtocol.parseHandoverStatus(bytes(0x02, 0x03)))
        assertEquals(HandoverStatus.CAMERA_REQUEST, CanonBleProtocol.parseHandoverStatus(bytes(0x01, 0x02)))
        assertEquals(HandoverStatus.REJECTED, CanonBleProtocol.parseHandoverStatus(bytes(0x01, 0x07)))
        assertEquals(HandoverStatus.CANCELLED, CanonBleProtocol.parseHandoverStatus(bytes(0x01, 0x0A)))
        assertEquals(HandoverStatus.IGNORED, CanonBleProtocol.parseHandoverStatus(bytes(0x01, 0x01)))
    }

    @Test
    fun `advertisement tells whether the camera is registered to this phone`() {
        val head = bytes(0x01, 0x34, 0x12)
        assertEquals(Registration.UNREGISTERED, CanonBleProtocol.advertisedRegistration(head + ByteArray(16), guid))
        assertEquals(Registration.OURS, CanonBleProtocol.advertisedRegistration(head + guid.reversedArray(), guid))
        assertEquals(Registration.OTHER, CanonBleProtocol.advertisedRegistration(head + ByteArray(16) { 7 }, guid))
        assertEquals(Registration.OURS, CanonBleProtocol.advertisedRegistration(head + bytes(14, 15, 0x01), guid))
        assertEquals(Registration.UNREGISTERED, CanonBleProtocol.advertisedRegistration(head + bytes(0, 0, 0x01), guid))
        assertEquals(Registration.UNKNOWN, CanonBleProtocol.advertisedRegistration(null, guid))
    }
}
