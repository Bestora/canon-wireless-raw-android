package io.github.canonwirelessraw.data

import android.content.Context
import java.util.UUID

private const val PREFS_NAME = "prefs"
private const val KEY_PAIRING_GUID_HEX = "pairing_guid_hex"
private const val KEY_LAST_IP = "last_ip"
private const val KEY_CAMERA_SSID = "camera_ssid"
private const val KEY_CAMERA_PSK = "camera_psk"

/**
 * Port implemented by [Prefs]; lets [CameraRepository] depend on an interface instead of the
 * SharedPreferences-backed class (which needs a Context), so tests can inject a fake.
 */
interface PrefsPort {
    fun pairingGuid(): ByteArray
    var lastIp: String?
    var cameraSsid: String?
    var cameraPsk: String?
    fun cameraCredentials(): WifiCredentials?
}

/** SharedPreferences-backed pairing GUID (persisted once, reused across connects) and last-used IP. */
class Prefs(context: Context) : PrefsPort {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 16-byte pairing GUID; generated once via [UUID.randomUUID] and persisted as hex. */
    @Synchronized
    override fun pairingGuid(): ByteArray {
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

    override var lastIp: String?
        get() = prefs.getString(KEY_LAST_IP, null)
        set(value) = prefs.edit().putString(KEY_LAST_IP, value).apply()

    override var cameraSsid: String?
        get() = prefs.getString(KEY_CAMERA_SSID, null)
        set(v) = prefs.edit().putString(KEY_CAMERA_SSID, v).apply()

    override var cameraPsk: String?
        get() = prefs.getString(KEY_CAMERA_PSK, null)
        set(v) = prefs.edit().putString(KEY_CAMERA_PSK, v).apply()

    override fun cameraCredentials(): WifiCredentials? {
        val s = cameraSsid; val p = cameraPsk
        return if (!s.isNullOrBlank() && !p.isNullOrBlank()) WifiCredentials(s, p) else null
    }
}

private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

private fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { i -> ((Character.digit(hex[2 * i], 16) shl 4) + Character.digit(hex[2 * i + 1], 16)).toByte() }
