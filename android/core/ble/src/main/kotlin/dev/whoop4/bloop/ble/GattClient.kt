package dev.whoop4.bloop.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import dev.whoop4.bloop.protocol.WhoopUuids
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class GattException(message: String) : Exception(message)

/**
 * Coroutine wrapper around [BluetoothGatt]. Android allows one outstanding GATT operation at a time, so
 * every operation goes through [op], which serialises them with a mutex and waits for the matching callback
 * (with a timeout). Notifications are exposed as a flow of (characteristic UUID, bytes).
 */
@SuppressLint("MissingPermission")
class GattClient(private val context: Context, private val log: DebugLog) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    @Volatile private var gatt: BluetoothGatt? = null
    private val opLock = Mutex()
    @Volatile private var pending: CompletableDeferred<Int>? = null
    @Volatile private var connectWaiter: CompletableDeferred<Unit>? = null
    @Volatile private var disconnectWaiter: CompletableDeferred<Unit>? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected
    private val _notifications = MutableSharedFlow<Pair<UUID, ByteArray>>(extraBufferCapacity = 20_000)
    val notifications: SharedFlow<Pair<UUID, ByteArray>> = _notifications

    val services: List<BluetoothGattService> get() = gatt?.services.orEmpty()
    fun characteristic(service: UUID, ch: UUID): BluetoothGattCharacteristic? = gatt?.getService(service)?.getCharacteristic(ch)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log.i("GATT state=${stateName(newState)} status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _connected.value = true
                connectWaiter?.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                _connected.value = false
                connectWaiter?.completeExceptionally(GattException("connection failed (status $status)"))
                pending?.completeExceptionally(GattException("disconnected (status $status)"))
                disconnectWaiter?.complete(Unit)
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { pending?.complete(status) }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { log.i("MTU=$mtu status=$status"); pending?.complete(status) }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { pending?.complete(status) }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { pending?.complete(status) }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            _notifications.tryEmit(c.uuid to value.clone())
        }
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return   // the overload above is called instead
            @Suppress("DEPRECATION") val v = c.value ?: return
            _notifications.tryEmit(c.uuid to v.clone())
        }
    }

    /** Connects (direct, not autoConnect), negotiates MTU and discovers services. */
    suspend fun connect(address: String, timeoutMs: Long = 20_000) {
        close()
        val adapter = manager?.adapter ?: throw GattException("Bluetooth unavailable")
        if (!adapter.isEnabled) throw GattException("Bluetooth is off")
        val device: BluetoothDevice = adapter.getRemoteDevice(address)
        val waiter = CompletableDeferred<Unit>().also { connectWaiter = it }
        log.i("connecting to $address…")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        try {
            withTimeout(timeoutMs) { waiter.await() }
        } catch (e: Exception) {
            close(); throw GattException("connect: ${e.message}")
        } finally { connectWaiter = null }
        // Larger MTU so frames rarely need reassembly; failure is harmless (FrameAssembler handles 20-byte chunks).
        runCatching { op("requestMtu") { it.requestMtu(247) } }
        val st = op("discoverServices", 15_000) { it.discoverServices() }
        if (st != BluetoothGatt.GATT_SUCCESS) throw GattException("service discovery failed (status $st)")
        log.i("services: " + services.joinToString { it.uuid.toString().take(8) })
    }

    /** Re-runs service discovery (used when a service may only appear after a command). */
    suspend fun rediscover(): Boolean = op("discoverServices", 15_000) { it.discoverServices() } == BluetoothGatt.GATT_SUCCESS

    suspend fun enableNotifications(service: UUID, chUuid: UUID, enable: Boolean = true) {
        val ch = characteristic(service, chUuid) ?: throw GattException("characteristic $chUuid not found")
        val g = gatt ?: throw GattException("not connected")
        if (!g.setCharacteristicNotification(ch, enable)) throw GattException("setCharacteristicNotification failed")
        val d = ch.getDescriptor(WhoopUuids.CCCD) ?: throw GattException("no CCCD on $chUuid")
        val value = when {
            !enable -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        val st = op("writeDescriptor") {
            if (Build.VERSION.SDK_INT >= 33) it.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
            else @Suppress("DEPRECATION") run { d.value = value; it.writeDescriptor(d) }
        }
        if (st != BluetoothGatt.GATT_SUCCESS) throw GattException("enable notifications on $chUuid: status $st")
    }

    suspend fun write(service: UUID, chUuid: UUID, bytes: ByteArray) {
        val ch = characteristic(service, chUuid) ?: throw GattException("characteristic $chUuid not found")
        val type = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                   else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val st = op("write") {
            if (Build.VERSION.SDK_INT >= 33) it.writeCharacteristic(ch, bytes, type) == BluetoothStatusCodes.SUCCESS
            else @Suppress("DEPRECATION") run { ch.writeType = type; ch.value = bytes; it.writeCharacteristic(ch) }
        }
        if (st != BluetoothGatt.GATT_SUCCESS) throw GattException("write to $chUuid: status $st")
    }

    /** Runs one GATT operation: [start] must return true if it was queued; resolves with the callback's status. */
    private suspend fun op(name: String, timeoutMs: Long = 8_000, start: (BluetoothGatt) -> Boolean): Int = opLock.withLock {
        val g = gatt ?: throw GattException("$name: not connected")
        val d = CompletableDeferred<Int>().also { pending = it }
        try {
            if (!start(g)) throw GattException("$name: could not start")
            withTimeoutOrNull(timeoutMs) { d.await() } ?: throw GattException("$name: timed out")
        } finally { pending = null }
    }

    /** Disconnects gracefully (waits briefly for the callback) and releases the GATT client. */
    suspend fun disconnect() {
        val g = gatt ?: return
        if (_connected.value) {
            val w = CompletableDeferred<Unit>().also { disconnectWaiter = it }
            runCatching { g.disconnect() }
            withTimeoutOrNull(3_000) { w.await() }
            disconnectWaiter = null
        }
        close()
    }

    private fun close() {
        gatt?.let { runCatching { it.close() } }
        gatt = null
        _connected.value = false
    }

    private fun stateName(s: Int) = when (s) {
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"; BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"; else -> "DISCONNECTING"
    }
}
