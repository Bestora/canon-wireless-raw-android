package de.bestora.canonwirelessrawandroid.ble

import java.util.UUID

object CanonBlePairing {
    // Canon base UUID pattern: <short>-0000-1000-0000-d8492fffa821
    private const val CANON_UUID_TEMPLATE = "%s-0000-1000-0000-d8492fffa821"

    // UUIDs
    val SERVICE_PHONE: UUID = uuid("00010006")
    val CHAR_NAME: UUID = uuid("00010006")
    val CHAR_PAIRING: UUID = uuid("0001000a")
    val SERVICE_MODE: UUID = uuid("00030010")
    val CHAR_MODE: UUID = uuid("00030010")

    // Constants for pairing modes
    const val MODE_WAKE: Byte = 0x03
    const val PAIR_ACCEPT: Byte = 0x02
    const val PAIR_REJECT: Byte = 0x03

    /**
     * Build a full Canon UUID from a short 8-character code.
     * Example: "00010006" -> "00010006-0000-1000-0000-d8492fffa821"
     */
    fun uuid(short: String): UUID {
        val fullUuidString = CANON_UUID_TEMPLATE.format(short)
        return UUID.fromString(fullUuidString)
    }

    /**
     * Build the four pairing writes for Canon BLE smart pairing.
     * Returns a list of 4 ByteArrays:
     * [0] = 0x01 + device name (UTF-8)
     * [1] = 0x03 + guid (16 bytes)
     * [2] = 0x04 + device name (UTF-8)
     * [3] = 0x05 + {0x02}
     *
     * @param deviceName The device name as UTF-8 string
     * @param guid The 16-byte GUID
     * @return List of 4 ByteArrays to write to CHAR_PAIRING in order
     * @throws IllegalArgumentException if guid is not exactly 16 bytes
     */
    fun pairingWrites(deviceName: String, guid: ByteArray): List<ByteArray> {
        require(guid.size == 16) { "GUID must be exactly 16 bytes, got ${guid.size}" }

        val nameBytes = deviceName.toByteArray(Charsets.UTF_8)

        return listOf(
            // [0] = 0x01 + name
            byteArrayOf(0x01) + nameBytes,
            // [1] = 0x03 + guid
            byteArrayOf(0x03) + guid,
            // [2] = 0x04 + name
            byteArrayOf(0x04) + nameBytes,
            // [3] = 0x05 + 0x02
            byteArrayOf(0x05, 0x02)
        )
    }
}
