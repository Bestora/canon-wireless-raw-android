package io.github.canonwirelessraw.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import io.github.canonwirelessraw.data.Prefs
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

private const val TAG = "BleWaker"
private const val CONNECT_TIMEOUT_MS = 20_000L
private const val OP_TIMEOUT_MS = 15_000L
private const val PAIR_CONFIRM_TIMEOUT_MS = 60_000L

/** Standard Client Characteristic Configuration Descriptor (0x2902) — enables notifications. */
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

data class BleDevice(val name: String, val address: String)

enum class WakeResult {
    /** GATT-Writes erfolgreich abgesetzt (Pairing + MODE_WAKE). Sagt NICHT, dass das WLAN hochkam —
     *  das lässt sich per BLE nicht bestätigen. Best-Effort/experimentell. */
    PAIRED_WAKE_SENT,

    /** Pairing-Writes raus, aber innerhalb 60 s kein Accept/Reject-Notify — vermutlich Bestätigung an der Kamera nötig. */
    NEEDS_CONFIRMATION,

    /** Kamera hat das Pairing abgelehnt. */
    REJECTED,

    /** Bluetooth aus, keine Permission, Verbindungsabbruch oder fehlgeschlagener GATT-Schritt. */
    FAILED,
}

/**
 * Sucht die (vorab gekoppelte) Canon-Kamera per BLE und versucht, ihr WLAN zu wecken.
 *
 * Best-Effort: Ein erfolgreicher [pairAndWake] ([WakeResult.PAIRED_WAKE_SENT]) heißt nur, dass die
 * GATT-Writes durchgingen — ob die Kamera daraufhin ihr WLAN hochfährt, ist über BLE nicht prüfbar.
 * Am Gerät verifiziert (Debug-Screen, Task 6).
 *
 * GATT-Operationen sind asynchron und Android serialisiert sie: jeder Write wartet auf sein
 * onCharacteristicWrite, bevor der nächste abgesetzt wird — sonst gehen sie still verloren.
 */
class BleWaker(private val context: Context) {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val prefs = Prefs(context)

    // @Volatile / AtomicReference: geschrieben auf dem Coroutine-Dispatcher, gelesen/geschrieben auf dem
    // Binder-Callback-Thread — ohne das keine happens-before-Garantie (stale reads -> Hangs).
    @Volatile private var gatt: BluetoothGatt? = null

    // Genau eine sequentielle GATT-Op ist zu jeder Zeit "in flight" (connect / discover / write / descriptor-write).
    // AtomicReference macht resumeOp single-shot: getAndSet(null) garantiert, dass genau ein Callback resumt
    // (zwei gleichzeitige Callbacks würden sonst beide resumen -> IllegalStateException auf dem Binder-Thread).
    private val opCont = AtomicReference<CancellableContinuation<Boolean>?>(null)
    @Volatile private var notify = CompletableDeferred<Byte>()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> resumeOp(status == BluetoothGatt.GATT_SUCCESS)
                BluetoothGatt.STATE_DISCONNECTED -> {
                    // Mitten in einer Op abgebrochen: laufende Op mit false resumen, Notify-Wait
                    // mit Fehler beenden — nichts leakt, der Aufrufer bekommt FAILED.
                    resumeOp(false)
                    if (!notify.isCompleted) notify.completeExceptionally(DisconnectedException())
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) =
            resumeOp(status == BluetoothGatt.GATT_SUCCESS)

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) =
            resumeOp(status == BluetoothGatt.GATT_SUCCESS)

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) =
            resumeOp(status == BluetoothGatt.GATT_SUCCESS)

        // Notify-Overloads: API 33+ liefert value direkt, davor via ch.value.
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (ch.uuid == CanonBlePairing.CHAR_NAME) handleNotify(value)
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid == CanonBlePairing.CHAR_NAME) handleNotify(ch.value ?: ByteArray(0))
        }
    }

    // Single-shot: getAndSet(null) zieht die Continuation atomar heraus — ein zweiter (Race-)Callback bekommt null.
    private fun resumeOp(success: Boolean) {
        opCont.getAndSet(null)?.let { if (it.isActive) it.resume(success) }
    }

    // ponytail: Accept/Reject aus dem ersten Notify-Byte. Genaues Canon-Frame-Layout am Gerät (Task 6)
    // zu verifizieren; reicht für den experimentellen Wake-Pfad.
    private fun handleNotify(value: ByteArray) {
        if (value.isEmpty() || notify.isCompleted) return
        when (value.first()) {
            CanonBlePairing.PAIR_ACCEPT -> notify.complete(CanonBlePairing.PAIR_ACCEPT)
            CanonBlePairing.PAIR_REJECT -> notify.complete(CanonBlePairing.PAIR_REJECT)
        }
    }

    /**
     * Scannt nach der Canon-Kamera: erst per BLE-Scan (ScanFilter auf [CanonBlePairing.SERVICE_PHONE]),
     * bei leerem Ergebnis Fallback auf gebondete Geräte mit Namen enthält "EOS"/"Canon".
     * @return gefundenes Gerät oder null (Timeout, Bluetooth aus, fehlende Permission).
     */
    suspend fun scanForCamera(timeoutMs: Long = 10_000): BleDevice? {
        val adapter = adapter ?: run { Log.w(TAG, "Kein Bluetooth-Adapter"); return null }
        if (!adapter.isEnabled) { Log.w(TAG, "Bluetooth ist aus"); return null }
        val scanner = adapter.bluetoothLeScanner ?: return null

        val fromScan = try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<BleDevice> { cont ->
                    val cb = object : ScanCallback() {
                        override fun onScanResult(callbackType: Int, result: ScanResult) {
                            val dev = result.device
                            val name = try { dev.name } catch (e: SecurityException) { null }
                            if (cont.isActive) {
                                // Scan auch auf dem Erfolgspfad stoppen — sonst läuft SCAN_MODE_LOW_LATENCY
                                // ewig weiter (Radio-Drain + Androids "scanning too frequently"-Throttle).
                                runCatching { scanner.stopScan(this) }
                                cont.resume(BleDevice(name ?: "Canon", dev.address))
                            }
                        }
                    }
                    val filter = ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid(CanonBlePairing.SERVICE_PHONE))
                        .build()
                    val settings = ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build()
                    scanner.startScan(listOf(filter), settings, cb)
                    cont.invokeOnCancellation { runCatching { scanner.stopScan(cb) } }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Scan-Permission fehlt", e); null
        }
        if (fromScan != null) return fromScan

        // Fallback: gebondete Kamera (advertised nicht immer, ist aber gekoppelt).
        return try {
            adapter.bondedDevices
                ?.firstOrNull { d ->
                    val n = try { d.name } catch (e: SecurityException) { null }
                    n != null && (n.contains("EOS", true) || n.contains("Canon", true))
                }
                ?.let { BleDevice((try { it.name } catch (e: SecurityException) { null }) ?: "Canon", it.address) }
        } catch (e: SecurityException) {
            Log.w(TAG, "Connect-Permission fehlt (bondedDevices)", e); null
        }
    }

    /**
     * Verbindet, führt die Canon-Pairing-Writes aus und schreibt bei Accept [CanonBlePairing.MODE_WAKE].
     * [WakeResult.PAIRED_WAKE_SENT] bedeutet NUR, dass die GATT-Writes erfolgreich waren — NICHT, dass das WLAN hochkam.
     */
    suspend fun pairAndWake(device: BleDevice, deviceName: String = "Canon Wireless RAW"): WakeResult {
        val adapter = adapter ?: run { Log.w(TAG, "Kein Bluetooth-Adapter"); return WakeResult.FAILED }
        if (!adapter.isEnabled) { Log.w(TAG, "Bluetooth ist aus"); return WakeResult.FAILED }

        close() // etwaige Alt-Verbindung schließen und State resetten
        notify = CompletableDeferred()

        return try {
            val remote = adapter.getRemoteDevice(device.address)

            if (!connect(remote)) { Log.w(TAG, "Connect fehlgeschlagen"); return WakeResult.FAILED }
            if (!awaitOp { gatt?.discoverServices() ?: false }) { Log.w(TAG, "Service-Discovery fehlgeschlagen"); return WakeResult.FAILED }

            val g = gatt ?: return WakeResult.FAILED
            val phone = g.getService(CanonBlePairing.SERVICE_PHONE)
            val charName = phone?.getCharacteristic(CanonBlePairing.CHAR_NAME)
            val charPairing = phone?.getCharacteristic(CanonBlePairing.CHAR_PAIRING)
            val charMode = g.getService(CanonBlePairing.SERVICE_MODE)?.getCharacteristic(CanonBlePairing.CHAR_MODE)
            if (charName == null || charPairing == null || charMode == null) {
                Log.w(TAG, "Erwartete Characteristics nicht gefunden"); return WakeResult.FAILED
            }

            if (!enableNotifications(g, charName)) { Log.w(TAG, "Notify aktivieren fehlgeschlagen"); return WakeResult.FAILED }

            // Vier Pairing-Writes streng sequentiell — jeder wartet auf sein onCharacteristicWrite.
            for (payload in CanonBlePairing.pairingWrites(deviceName, prefs.pairingGuid())) {
                if (!awaitOp { writeChar(g, charPairing, payload) }) {
                    Log.w(TAG, "Pairing-Write fehlgeschlagen"); return WakeResult.FAILED
                }
            }

            // Auf Accept/Reject warten. Timeout -> vermutlich Bestätigung an der Kamera offen.
            val response = try {
                withTimeoutOrNull(PAIR_CONFIRM_TIMEOUT_MS) { notify.await() }
            } catch (e: DisconnectedException) {
                Log.w(TAG, "Verbindung während Pairing-Bestätigung abgebrochen"); return WakeResult.FAILED
            }

            when (response) {
                null -> WakeResult.NEEDS_CONFIRMATION
                CanonBlePairing.PAIR_REJECT -> WakeResult.REJECTED
                CanonBlePairing.PAIR_ACCEPT -> {
                    // Finalisieren + Wake absetzen. Beide Writes wieder sequentiell.
                    if (!awaitOp { writeChar(g, charPairing, byteArrayOf(0x01)) }) return WakeResult.FAILED
                    if (!awaitOp { writeChar(g, charMode, byteArrayOf(CanonBlePairing.MODE_WAKE)) }) return WakeResult.FAILED
                    WakeResult.PAIRED_WAKE_SENT
                }
                else -> WakeResult.FAILED
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "BLE-Permission fehlt", e); WakeResult.FAILED
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Ungültige Geräteadresse: ${device.address}", e); WakeResult.FAILED
        }
    }

    /** Verbindet und wartet auf STATE_CONNECTED (mit Timeout). */
    private suspend fun connect(device: BluetoothDevice): Boolean =
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { cont ->
                opCont.set(cont)
                gatt = try {
                    device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                } catch (e: SecurityException) {
                    Log.w(TAG, "connectGatt-Permission fehlt", e); null
                }
                if (gatt == null) { opCont.compareAndSet(cont, null); if (cont.isActive) cont.resume(false) }
                cont.invokeOnCancellation { close() }
            }
        } ?: false

    /** Setzt die Continuation, ruft [start] (die eigentliche GATT-Anforderung) und wartet auf den Callback.
     *  Timeout-Backstop: bleibt der Callback aus (bekannter Android-BLE-Stall) ohne Disconnect -> false. */
    private suspend fun awaitOp(start: () -> Boolean): Boolean =
        withTimeoutOrNull(OP_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { cont ->
                opCont.set(cont)
                val started = try { start() } catch (e: SecurityException) {
                    Log.w(TAG, "GATT-Op-Permission fehlt", e); false
                }
                if (!started) { opCont.compareAndSet(cont, null); if (cont.isActive) cont.resume(false) }
            }
        } ?: false

    // setCharacteristicNotification (synchron) + CCCD-Write in einer awaitOp-Continuation; wartet auf onDescriptorWrite.
    private suspend fun enableNotifications(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean =
        awaitOp {
            if (!g.setCharacteristicNotification(ch, true)) return@awaitOp false
            val cccd = ch.getDescriptor(CCCD_UUID) ?: return@awaitOp false
            writeDescriptor(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }

    @Suppress("DEPRECATION")
    private fun writeChar(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            ch.value = value
            g.writeCharacteristic(ch)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            d.value = value
            g.writeDescriptor(d)
        }
    }

    /** Schließt die GATT-Verbindung und resumt hängende Ops. Idempotent. */
    fun close() {
        val g = gatt
        gatt = null
        try { g?.disconnect(); g?.close() } catch (e: SecurityException) { /* Permission weg — nichts zu schließen */ }
        resumeOp(false)
        if (!notify.isCompleted) notify.completeExceptionally(DisconnectedException())
    }

    private class DisconnectedException : Exception("BLE disconnected")
}
