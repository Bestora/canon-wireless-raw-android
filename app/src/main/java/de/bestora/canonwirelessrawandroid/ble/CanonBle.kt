package de.bestora.canonwirelessrawandroid.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import de.bestora.canonwirelessrawandroid.ble.CanonBleProtocol.CHAR_HANDOVER_CAPS
import de.bestora.canonwirelessrawandroid.ble.CanonBleProtocol.CHAR_HANDOVER_CP
import de.bestora.canonwirelessrawandroid.ble.CanonBleProtocol.CHAR_HANDOVER_STATUS
import de.bestora.canonwirelessrawandroid.ble.CanonBleProtocol.CHAR_PAIRING
import de.bestora.canonwirelessrawandroid.ble.CanonBleProtocol.CHAR_PASSWORD
import de.bestora.canonwirelessrawandroid.ble.CanonBleProtocol.CHAR_SECURITY
import de.bestora.canonwirelessrawandroid.ble.CanonBleProtocol.CHAR_SESSION
import de.bestora.canonwirelessrawandroid.ble.CanonBleProtocol.CHAR_SESSION_CAPS
import de.bestora.canonwirelessrawandroid.ble.CanonBleProtocol.CHAR_SSID
import de.bestora.canonwirelessrawandroid.ble.CanonBleProtocol.hex
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "CanonBle"
private const val OP_TIMEOUT_MS = 10_000L
private const val CONNECT_TIMEOUT_MS = 20_000L
private const val BOND_TIMEOUT_MS = 30_000L
/** The user has to notice the prompt on the camera and press OK. */
private const val CONFIRM_TIMEOUT_MS = 90_000L
private const val START_TIMEOUT_MS = 30_000L
private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
private val EMPTY = ByteArray(0)

class BleException(message: String) : Exception(message)

data class FoundCamera(val name: String, val address: String, val manufacturerData: ByteArray?)

/**
 * GATT client for [CanonBleProtocol]. Exactly one GATT request is in flight at a time (Android
 * drops overlapping ones silently). Any failure aborts the sequence; the caller then [close]s.
 * Callers check BLUETOOTH_SCAN / BLUETOOTH_CONNECT before calling in.
 */
@SuppressLint("MissingPermission")
class CanonBle(private val context: Context) {
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @Volatile private var gatt: BluetoothGatt? = null
    /** The one in-flight request; resumed with its value, an empty array for writes, null on failure. */
    private val pending = AtomicReference<CancellableContinuation<ByteArray?>?>(null)
    private val notifications = MutableSharedFlow<Pair<UUID, ByteArray>>(extraBufferCapacity = 64)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "connection state=$newState status=$status")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> complete(status, EMPTY)
                BluetoothProfile.STATE_DISCONNECTED -> complete(status, null)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = complete(status, EMPTY)

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            complete(status, value)

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) =
            complete(status, ch.value ?: EMPTY)

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) =
            complete(status, EMPTY)

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) =
            complete(status, EMPTY)

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            notifications.tryEmit(ch.uuid to value)
        }

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            notifications.tryEmit(ch.uuid to (ch.value ?: EMPTY))
        }
    }

    private fun complete(status: Int, value: ByteArray?) {
        val cont = pending.getAndSet(null) ?: return
        if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "GATT status $status")
        if (cont.isActive) cont.resume(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
    }

    /** Finds a Canon camera advertising its BLE service that [accept] takes. */
    suspend fun scan(timeoutMs: Long = 15_000, accept: (FoundCamera) -> Boolean = { true }): FoundCamera? {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: throw BleException("Bluetooth is off")
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val cb = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        if (!cont.isActive) return
                        val record = result.scanRecord
                        val found = FoundCamera(
                            name = result.device.name ?: record?.deviceName ?: "Canon",
                            address = result.device.address,
                            manufacturerData = record?.getManufacturerSpecificData(CanonBleProtocol.MANUFACTURER_CANON),
                        )
                        Log.i(TAG, "scan: ${found.name} ${found.address} mfr=${hex(found.manufacturerData)}")
                        if (!accept(found)) return
                        runCatching { scanner.stopScan(this) }
                        cont.resume(found)
                    }

                    override fun onScanFailed(errorCode: Int) {
                        if (cont.isActive) cont.resumeWithException(BleException("Bluetooth scan failed ($errorCode)"))
                    }
                }
                val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(CanonBleProtocol.SERVICE_PAIRING)).build()
                val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
                scanner.startScan(listOf(filter), settings, cb)
                cont.invokeOnCancellation { runCatching { scanner.stopScan(cb) } }
            }
        }
    }

    /** GATT connect, service discovery, and an OS-level bond (the camera wants an encrypted link). */
    suspend fun connect(address: String) {
        close()
        val device = adapter?.getRemoteDevice(address) ?: throw BleException("No Bluetooth adapter")
        op("connect", CONNECT_TIMEOUT_MS) {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            gatt != null
        }
        op("discover services") { gatt().discoverServices() }
        bond(device)
    }

    /**
     * One-time registration with a camera in its pairing screen: the user confirms on the camera,
     * then the camera stores this phone's GUID and name. Leaves the camera's Wi-Fi off; it only
     * starts once the user has left the camera menu (see [startWifi]).
     */
    suspend fun register(name: String, guid: ByteArray, log: (String) -> Unit) {
        log("Press OK on the camera")
        val answer = awaitNotification(CHAR_PAIRING, CONFIRM_TIMEOUT_MS, trigger = {
            write(CHAR_PAIRING, CanonBleProtocol.pairingRequest(name))
            subscribe(CHAR_PAIRING)
        }) { it.firstOrNull() }
        if (answer != CanonBleProtocol.PAIRING_ACCEPTED) throw BleException("The camera rejected the pairing")
        subscribeHandover(log)
        CanonBleProtocol.registration(name, guid).forEach { write(CHAR_SESSION, it) }
        fetchWifi(log)
    }

    /** Asks a camera that knows this phone to start its Wi-Fi; returns how to join it. */
    suspend fun startWifi(log: (String) -> Unit): CameraHandover {
        subscribeHandover(log)
        val wifi = fetchWifi(log)
        val status = awaitNotification(CHAR_HANDOVER_STATUS, START_TIMEOUT_MS, trigger = {
            write(CHAR_HANDOVER_CP, CanonBleProtocol.START_HANDOVER)
        }) { value ->
            Log.i(TAG, "handover status ${hex(value)}")
            CanonBleProtocol.parseHandoverStatus(value).takeIf { it != HandoverStatus.IGNORED && it != HandoverStatus.CAMERA_REQUEST }
        }
        if (status != HandoverStatus.STARTED) throw BleException("The camera did not start its Wi-Fi ($status)")
        return wifi
    }

    private suspend fun subscribeHandover(log: (String) -> Unit) {
        Log.i(TAG, "caps session=${hex(read(CHAR_SESSION_CAPS))} handover=${hex(read(CHAR_HANDOVER_CAPS))}")
        subscribe(CHAR_HANDOVER_STATUS)
        subscribe(CHAR_HANDOVER_CP)
    }

    /** Reads the camera's Wi-Fi config, then confirms the registration as Canon's app does every time. */
    private suspend fun fetchWifi(log: (String) -> Unit): CameraHandover {
        val ap = awaitNotification(CHAR_HANDOVER_CP, OP_TIMEOUT_MS, trigger = {
            write(CHAR_HANDOVER_CP, CanonBleProtocol.REQUEST_AP_CONFIG)
        }) { value ->
            Log.i(TAG, "AP config ${hex(value)}")
            CanonBleProtocol.parseApConfig(value)
        }
        val ssid = CanonBleProtocol.asciiField(read(CHAR_SSID))
        val password = CanonBleProtocol.asciiField(read(CHAR_PASSWORD))
        val security = CanonBleProtocol.parseSecurityTypes(read(CHAR_SECURITY))
        write(CHAR_SESSION, CanonBleProtocol.REGISTRATION_DONE)
        Log.i(TAG, "Wi-Fi $ssid at ${ap.ip}, security $security, wait ${ap.waitMs} ms")
        return CameraHandover(ssid, password, ap.bssid, ap.ip, security, ap.waitMs)
    }

    /** Disconnects GATT and fails whatever request is in flight. Idempotent. */
    fun close() {
        val g = gatt
        gatt = null
        runCatching { g?.disconnect(); g?.close() }
        complete(BluetoothGatt.GATT_FAILURE, null)
    }

    private suspend fun read(uuid: UUID): ByteArray = op("read ${short(uuid)}") { gatt().readCharacteristic(char(uuid)) }

    @Suppress("DEPRECATION")
    private suspend fun write(uuid: UUID, value: ByteArray) {
        val ch = char(uuid)
        op("write ${short(uuid)}") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt().writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = value
                gatt().writeCharacteristic(ch)
            }
        }
    }

    /** Enables indications or notifications, whichever the characteristic offers. */
    @Suppress("DEPRECATION")
    private suspend fun subscribe(uuid: UUID) {
        val ch = char(uuid)
        val g = gatt()
        if (!g.setCharacteristicNotification(ch, true)) throw BleException("subscribe ${short(uuid)} failed")
        val cccd = ch.getDescriptor(CCCD) ?: throw BleException("${short(uuid)} has no CCCD")
        val value = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        op("subscribe ${short(uuid)}") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                cccd.value = value
                g.writeDescriptor(cccd)
            }
        }
    }

    /** Listens on [uuid] before [trigger] runs, so an immediate answer can't slip past; returns the
     *  first notification [accept] maps to non-null. */
    private suspend fun <T : Any> awaitNotification(
        uuid: UUID,
        timeoutMs: Long,
        trigger: suspend () -> Unit,
        accept: (ByteArray) -> T?,
    ): T = coroutineScope {
        val answer = async(start = CoroutineStart.UNDISPATCHED) {
            notifications.filter { it.first == uuid }.mapNotNull { accept(it.second) }.first()
        }
        trigger()
        withTimeoutOrNull(timeoutMs) { answer.await() } ?: run {
            answer.cancel()
            throw BleException("No answer from the camera on ${short(uuid)}")
        }
    }

    /** Starts one GATT request and suspends until its callback. */
    private suspend fun op(name: String, timeoutMs: Long = OP_TIMEOUT_MS, start: () -> Boolean): ByteArray {
        Log.i(TAG, name)
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                pending.set(cont)
                if (!start()) {
                    pending.compareAndSet(cont, null)
                    cont.resume(null)
                }
            }
        }
        return result ?: throw BleException("$name failed")
    }

    private suspend fun bond(device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return
        Log.i(TAG, "bonding")
        var receiver: BroadcastReceiver? = null
        try {
            val bonded = withTimeoutOrNull(BOND_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    receiver = object : BroadcastReceiver() {
                        override fun onReceive(c: Context, intent: Intent) {
                            if (intent.bondedAddress() != device.address || !cont.isActive) return
                            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                                BluetoothDevice.BOND_BONDED -> cont.resume(true)
                                BluetoothDevice.BOND_NONE -> cont.resume(false)
                            }
                        }
                    }
                    val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                    // EXPORTED: the broadcast comes from the Bluetooth app, not the system uid, so a
                    // NOT_EXPORTED receiver never sees it. Safe, it is a protected broadcast.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                    } else {
                        context.registerReceiver(receiver, filter)
                    }
                    when {
                        device.bondState == BluetoothDevice.BOND_BONDED -> cont.resume(true)
                        device.bondState != BluetoothDevice.BOND_BONDING && !device.createBond() -> cont.resume(false)
                    }
                }
            }
            if (bonded != true) throw BleException("Bluetooth pairing with the camera failed")
        } finally {
            receiver?.let { runCatching { context.unregisterReceiver(it) } }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.bondedAddress(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)?.address
        } else {
            getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)?.address
        }

    private fun gatt(): BluetoothGatt = gatt ?: throw BleException("Not connected to the camera")

    private fun char(uuid: UUID): BluetoothGattCharacteristic =
        gatt().services.firstNotNullOfOrNull { it.getCharacteristic(uuid) }
            ?: throw BleException("Camera has no characteristic ${short(uuid)}")

    private fun short(uuid: UUID) = uuid.toString().take(8)
}
