package de.bestora.canonwirelessrawandroid.wifi

import android.content.Context
import android.net.*
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import de.bestora.canonwirelessrawandroid.data.WifiCredentials
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WifiJoinException(message: String) : Exception(message)

/** Fordert das Kamera-WLAN via WifiNetworkSpecifier an und bindet den Prozess bei Erfolg an das Netz,
 *  damit auch libpicts natives Socket (ohne JNI-Änderung) übers Kamera-WLAN läuft. */
class WifiConnector(private val context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Fordert das Kamera-WLAN an (System-Dialog), bindet den Prozess bei Erfolg an das Netz.
     *  Wirft WifiJoinException bei Timeout/Ablehnung/Fehler. */
    suspend fun connectToCamera(creds: WifiCredentials, bssid: String? = null, wpa3: Boolean = false, timeoutMs: Long = 30_000) {
        release() // a previous request would otherwise stay registered, holding its network
        val builder = WifiNetworkSpecifier.Builder().setSsid(creds.ssid)
        if (wpa3) builder.setWpa3Passphrase(creds.psk) else builder.setWpa2Passphrase(creds.psk)
        // ponytail: Canon's Camera Connect leaves the BSSID out on Samsung phones; same here until
        // a Samsung is shown to accept it.
        if (bssid != null && !Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            builder.setBssid(MacAddress.fromString(bssid))
        }
        val specifier = builder.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        var readyNetwork: Network? = null
        val ok = try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val cb = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            // Bind-Entscheidung erst NACH garantiertem Resume-Erfolg treffen (unten,
                            // gated auf ok == true) -- vermeidet die Race gegen den Timeout-Zweig, der
                            // sonst nach release() erneut binden könnte. Hier nur das Network merken;
                            // resume() auf einer bereits abgebrochenen CancellableContinuation ist laut
                            // Kotlin-Coroutines-Vertrag ein sicheres No-Op (kein Crash, kein Doppel-Resume).
                            readyNetwork = network
                            if (cont.isActive) cont.resume(true)
                        }
                        override fun onUnavailable() {
                            if (cont.isActive) cont.resumeWithException(
                                WifiJoinException("Camera Wi-Fi not available (dialog dismissed or wrong password?)"))
                        }
                    }
                    callback = cb
                    cm.requestNetwork(request, cb)
                    cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(cb) } }
                }
            }
        } catch (e: WifiJoinException) {
            // resumeWithException completet normal (kein Cancel), invokeOnCancellation feuert nicht ->
            // ohne dieses catch bliebe der NetworkCallback für immer registriert (Leak pro Fehlversuch).
            release()
            throw e
        }
        if (ok != true) { release(); throw WifiJoinException("Timed out connecting to the camera's Wi-Fi") }
        // ok == true ist nur erreichbar, wenn resume(true) die Coroutine tatsächlich fortgesetzt hat --
        // bei einer Race mit dem Timeout hätte die Cancellation gewonnen und ok wäre null (Zweig oben).
        // Binden also erst hier, mit dem Ergebnis bereits feststehend: kein Fenster mehr, in dem
        // onAvailable nach einem bereits gelaufenen release() erneut bindet.
        cm.bindProcessToNetwork(readyNetwork)   // Traffic (auch libpicts C-Socket) übers Kamera-Netz
        Log.i("WifiConnector", "camera network: ${cm.getLinkProperties(readyNetwork)}")
    }

    /** Prozess-Bindung lösen + Callback abmelden. Idempotent. Immer im finally/onDispose aufrufen. */
    fun release() {
        runCatching { cm.bindProcessToNetwork(null) }
        callback?.let { cb -> runCatching { cm.unregisterNetworkCallback(cb) } }
        callback = null
    }
}
