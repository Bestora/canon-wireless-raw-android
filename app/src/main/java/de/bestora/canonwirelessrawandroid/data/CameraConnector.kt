package de.bestora.canonwirelessrawandroid.data

import android.os.Build
import de.bestora.canonwirelessrawandroid.ble.BleException
import de.bestora.canonwirelessrawandroid.ble.CameraHandover
import de.bestora.canonwirelessrawandroid.ble.CanonBle
import de.bestora.canonwirelessrawandroid.ble.CanonBleProtocol
import de.bestora.canonwirelessrawandroid.ble.Registration
import de.bestora.canonwirelessrawandroid.ptp.PtpException
import de.bestora.canonwirelessrawandroid.ptp.REMOTE_MODE_SMARTPHONE
import de.bestora.canonwirelessrawandroid.wifi.WifiConnector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Slack on top of the camera's own wait time: joining right away raced the camera's Wi-Fi start. */
private const val WIFI_SETTLE_MS = 3_000L

/** Rejoining the camera's still-running Wi-Fi either works within seconds or not at all. */
private const val REJOIN_WINDOW_MS = 10_000L

/** Canon security code for WPA3-SAE in the Wi-Fi config the camera sends over Bluetooth. */
private const val SECURITY_WPA3 = 22

/**
 * Bluetooth-first connection, the way Canon's Camera Connect does it: pair a camera once, then each
 * connect wakes it over Bluetooth, gets its Wi-Fi config, joins the Wi-Fi and opens PTP/IP.
 */
class CameraConnector(
    private val ble: CanonBle,
    private val wifi: WifiConnector,
    private val repo: CameraRepository,
    private val prefs: Prefs,
) {
    /** Name the camera lists this phone under; also the PTP/IP name, which must match. */
    private val name = CanonBleProtocol.deviceName(Build.MODEL)

    /** Camera of the running Bluetooth session; null for a manual (IP) session. */
    @Volatile var current: CameraProfile? = null
        private set

    /** Wi-Fi config from the last handover: the camera keeps its Wi-Fi up for a while after a drop. */
    @Volatile private var lastWifi: CameraHandover? = null

    /** One connection attempt at a time: parallel ones share the GATT link and close each other's. */
    private val attempt = Mutex()

    /** Registers this phone with a camera showing its Bluetooth pairing screen. */
    suspend fun pair(status: (String) -> Unit): CameraProfile {
        val guid = prefs.pairingGuid()
        status("Looking for a camera in pairing mode…")
        val found = ble.scan(timeoutMs = 30_000) {
            CanonBleProtocol.advertisedRegistration(it.manufacturerData, guid) != Registration.OTHER
        } ?: throw BleException("No camera found. Is it showing its Bluetooth pairing screen?")
        try {
            status("Found ${found.name}")
            ble.connect(found.address)
            if (CanonBleProtocol.advertisedRegistration(found.manufacturerData, guid) != Registration.OURS) {
                ble.register(name, guid, status)
            }
        } finally {
            ble.close()
        }
        val known = prefs.cameras().firstOrNull { it.address == found.address }
        return (known ?: CameraProfile(found.address, found.name)).also(prefs::saveCamera)
    }

    suspend fun connect(camera: CameraProfile, status: (String) -> Unit, onRetry: (PtpException) -> Unit) =
        attempt.withLock { connectLocked(camera, status, onRetry) }

    private suspend fun connectLocked(camera: CameraProfile, status: (String) -> Unit, onRetry: (PtpException) -> Unit) {
        current = camera
        try {
            status("Waking ${camera.displayName} over Bluetooth…")
            ble.connect(camera.address)
            val w = ble.startWifi(status)
            lastWifi = w
            status("Camera Wi-Fi is starting…")
            delay(w.waitMs + WIFI_SETTLE_MS)
            joinAndOpen(w, status, onRetry, windowMs = PAIRING_WINDOW_MS)
            prefs.saveCamera(camera.copy(model = repo.model ?: camera.model))
        } catch (e: Throwable) {
            ble.close()
            wifi.release()
            throw e
        }
    }

    /**
     * Reconnect after the link was lost. A camera keeps its Wi-Fi up for a while, and a fresh
     * Bluetooth handover is refused while it does, so first rejoin with the last Wi-Fi config; only
     * then go through Bluetooth again. A manual (IP) session just reopens PTP.
     */
    suspend fun reconnect(status: (String) -> Unit, onRetry: (PtpException) -> Unit) = attempt.withLock {
        val camera = current ?: return@withLock repo.reconnect(onRetry)
        lastWifi?.let { w ->
            try {
                return@withLock joinAndOpen(w, status, onRetry, windowMs = REJOIN_WINDOW_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status("Camera Wi-Fi is gone, waking it over Bluetooth…")
            }
        }
        connectLocked(camera, status, onRetry)
    }

    private suspend fun joinAndOpen(w: CameraHandover, status: (String) -> Unit, onRetry: (PtpException) -> Unit, windowMs: Long) {
        status("Joining the camera's Wi-Fi…")
        wifi.connectToCamera(WifiCredentials(w.ssid, w.password), w.bssid, wpa3 = w.securityTypes.firstOrNull() == SECURITY_WPA3)
        status("Opening the camera…")
        repo.connect(w.ip, name = name, remoteMode = REMOTE_MODE_SMARTPHONE, windowMs = windowMs, onRetry = onRetry)
    }

    suspend fun disconnect() {
        current = null
        lastWifi = null
        runCatching { repo.disconnect() }
        ble.close()
        wifi.release()
    }
}
