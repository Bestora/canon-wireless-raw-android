package io.github.canonwirelessraw.data

import android.content.Context
import java.util.UUID

private const val PREFS_NAME = "prefs"
private const val KEY_PAIRING_GUID_HEX = "pairing_guid_hex"
private const val KEY_LAST_IP = "last_ip"

/** SharedPreferences-backed pairing GUID (persisted once, reused across connects) and last-used IP. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 16-byte pairing GUID; generated once via [UUID.randomUUID] and persisted as hex. */
    fun pairingGuid(): ByteArray {
        val existing = prefs.getString(KEY_PAIRING_GUID_HEX, null)
        if (existing != null) return hexToBytes(existing)

        val uuid = UUID.randomUUID()
        val bytes = java.nio.ByteBuffer.allocate(16)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()
        prefs.edit().putString(KEY_PAIRING_GUID_HEX, bytesToHex(bytes)).apply()
        return bytes
    }

    var lastIp: String?
        get() = prefs.getString(KEY_LAST_IP, null)
        set(value) = prefs.edit().putString(KEY_LAST_IP, value).apply()
}

private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

private fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { i -> ((Character.digit(hex[2 * i], 16) shl 4) + Character.digit(hex[2 * i + 1], 16)).toByte() }
