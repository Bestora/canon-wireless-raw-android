package io.github.canonwirelessraw.data

data class WifiCredentials(val ssid: String, val psk: String)

/** null = gültig; sonst deutscher Fehlertext. WPA2: PSK 8..63 Zeichen, SSID nicht leer/≤32. */
fun validateCredentials(ssid: String, psk: String): String? = when {
    ssid.isBlank() -> "SSID must not be empty"
    ssid.length > 32 -> "SSID too long (max. 32 characters)"
    psk.length < 8 -> "Password too short (min. 8 characters)"
    psk.length > 63 -> "Password too long (max. 63 characters)"
    else -> null
}
