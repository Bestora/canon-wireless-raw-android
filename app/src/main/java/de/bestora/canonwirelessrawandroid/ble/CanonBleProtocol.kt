package de.bestora.canonwirelessrawandroid.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** What the camera hands over via BLE so the phone can join its Wi-Fi and open PTP/IP. */
data class CameraHandover(
    val ssid: String,
    val password: String,
    /** "AA:BB:CC:DD:EE:FF" */
    val bssid: String,
    val ip: String,
    /** Canon security codes in camera order (9 and 22 seen; 22 is likely WPA3-SAE). */
    val securityTypes: List<Int>,
    /** How long the camera asks the phone to wait before joining its Wi-Fi. */
    val waitMs: Long,
)

/** Whether the camera's advertisement says it is registered to this phone. */
enum class Registration { UNREGISTERED, OURS, OTHER, UNKNOWN }

enum class HandoverStatus { STARTED, CAMERA_REQUEST, REJECTED, CANCELLED, IGNORED }

/**
 * Canon's BLE protocol for registering a phone and handing over the camera's Wi-Fi, as Canon's
 * Camera Connect app speaks it. Pure byte layouts and parsers; [CanonBle] drives the GATT side.
 * Multi-byte values are little-endian on the wire.
 */
object CanonBleProtocol {
    fun uuid(short: String): UUID = UUID.fromString("$short-0000-1000-0000-d8492fffa821")

    val SERVICE_PAIRING: UUID = uuid("00010000")
    /** Write 01+name; indicates 02 once the user accepted on the camera, 03 if rejected. */
    val CHAR_PAIRING: UUID = uuid("00010006")
    /** Registration commands (GUID, name, phone type, done). */
    val CHAR_SESSION: UUID = uuid("0001000a")
    val CHAR_SESSION_CAPS: UUID = uuid("0001000b")
    val CHAR_HANDOVER_CAPS: UUID = uuid("00020001")
    /** Handover commands; notifies the Wi-Fi config as type/length/value list. */
    val CHAR_HANDOVER_CP: UUID = uuid("00020002")
    /** Indicates [code, subcode] while the camera starts its Wi-Fi. */
    val CHAR_HANDOVER_STATUS: UUID = uuid("00020003")
    val CHAR_SSID: UUID = uuid("00020004")
    val CHAR_SECURITY: UUID = uuid("00020005")
    val CHAR_PASSWORD: UUID = uuid("00020006")

    /** Bluetooth SIG company identifier of Canon in the advertisement's manufacturer data. */
    const val MANUFACTURER_CANON = 0x01A9

    const val PAIRING_ACCEPTED: Byte = 0x02

    val REGISTRATION_DONE = byteArrayOf(0x01)
    val REQUEST_AP_CONFIG = byteArrayOf(0x0A)
    val START_HANDOVER = byteArrayOf(0x01)

    /** The phone name the camera lists: printable ASCII, at most 16 characters like Camera Connect. */
    fun deviceName(model: String): String = model.filter { it in ' '..'~' }.trim().take(16).ifEmpty { "Android" }

    fun pairingRequest(name: String): ByteArray = byteArrayOf(0x01) + name.toByteArray(Charsets.US_ASCII)

    /** Written to [CHAR_SESSION] after the user accepted: GUID (byte-reversed), name, phone type 02. */
    fun registration(name: String, guid: ByteArray): List<ByteArray> {
        require(guid.size == 16) { "GUID must be 16 bytes, got ${guid.size}" }
        return listOf(
            byteArrayOf(0x03) + guid.reversedArray(),
            byteArrayOf(0x04) + name.toByteArray(Charsets.US_ASCII),
            byteArrayOf(0x05, 0x02),
        )
    }

    /**
     * Manufacturer data (company [MANUFACTURER_CANON], without the company id): byte 0 version,
     * 1..2 USB product id, then either the registered GUID byte-reversed (19 bytes) or just its
     * last two bytes plus flags (6 bytes). All zero means the camera is waiting for a new phone.
     */
    fun advertisedRegistration(manufacturerData: ByteArray?, guid: ByteArray): Registration = when (manufacturerData?.size) {
        19 -> {
            val adv = manufacturerData.copyOfRange(3, 19)
            when {
                adv.all { it == 0.toByte() } -> Registration.UNREGISTERED
                adv.contentEquals(guid.reversedArray()) -> Registration.OURS
                else -> Registration.OTHER
            }
        }
        6 -> when {
            manufacturerData[3] == 0.toByte() && manufacturerData[4] == 0.toByte() -> Registration.UNREGISTERED
            manufacturerData[3] == guid[14] && manufacturerData[4] == guid[15] -> Registration.OURS
            else -> Registration.OTHER
        }
        else -> Registration.UNKNOWN
    }

    data class ApConfig(val ip: String, val bssid: String, val waitMs: Long)

    /** [CHAR_HANDOVER_CP] notification after [REQUEST_AP_CONFIG]: two header bytes, then
     *  type/length/value entries: 0x10 IP, 0x02 BSSID, 0x30 wait time in 100 ms steps. */
    fun parseApConfig(value: ByteArray): ApConfig? {
        var ip: String? = null
        var bssid: String? = null
        var waitMs = 0L
        var i = 2
        while (i + 2 <= value.size) {
            val type = value[i].toInt() and 0xFF
            val len = value[i + 1].toInt() and 0xFF
            if (i + 2 + len > value.size) return null
            val v = value.copyOfRange(i + 2, i + 2 + len).reversedArray()
            when (type) {
                0x10 -> ip = v.joinToString(".") { (it.toInt() and 0xFF).toString() }
                0x02 -> bssid = v.joinToString(":") { "%02X".format(it) }
                0x30 -> waitMs = (v.firstOrNull()?.toInt()?.and(0xFF) ?: 0) * 100L
            }
            i += 2 + len
        }
        return if (ip != null && bssid != null) ApConfig(ip, bssid, waitMs) else null
    }

    /** SSID and password characteristics: ASCII, NUL-padded. */
    fun asciiField(bytes: ByteArray): String = String(bytes, Charsets.US_ASCII).trim { it <= ' ' }

    fun parseSecurityTypes(bytes: ByteArray): List<Int> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return List(bytes.size / 4) { buf.int }
    }

    fun parseHandoverStatus(value: ByteArray): HandoverStatus {
        if (value.size < 2) return HandoverStatus.IGNORED
        val code = value[0].toInt() and 0xFF
        val sub = value[1].toInt() and 0xFF
        return when {
            sub == 0x03 && (code == 0x01 || code == 0x02) -> HandoverStatus.STARTED
            code == 0x01 && sub == 0x02 -> HandoverStatus.CAMERA_REQUEST
            code == 0x01 && sub in 0x04..0x09 -> HandoverStatus.REJECTED
            code == 0x01 && sub == 0x0A -> HandoverStatus.CANCELLED
            else -> HandoverStatus.IGNORED
        }
    }

    fun hex(bytes: ByteArray?): String = bytes?.joinToString("") { "%02x".format(it) } ?: "null"
}
