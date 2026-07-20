package io.github.canonwirelessraw.data

data class WifiCredentials(val ssid: String, val psk: String)

/** null = gültig; sonst deutscher Fehlertext. WPA2: PSK 8..63 Zeichen, SSID nicht leer/≤32. */
fun validateCredentials(ssid: String, psk: String): String? = when {
    ssid.isBlank() -> "SSID darf nicht leer sein"
    ssid.length > 32 -> "SSID zu lang (max. 32 Zeichen)"
    psk.length < 8 -> "Passwort zu kurz (mind. 8 Zeichen)"
    psk.length > 63 -> "Passwort zu lang (max. 63 Zeichen)"
    else -> null
}
