package dev.whoop4.bloop.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import dev.whoop4.bloop.protocol.WhoopUuids
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.Locale

data class FoundDevice(
    val address: String,
    val name: String?,
    val rssi: Int?,
    val isWhoop: Boolean,
    /** "connected", "paired" or "nearby". A connected/paired band doesn't advertise, so a scan alone misses it. */
    val via: String,
)

/**
 * Finds bands: the ones already connected or paired at OS level (they stop advertising, e.g. while the
 * Whoop app or a browser tab holds them) plus a live scan. Identified by the Whoop service UUID or a name
 * containing "WHOOP", as in scan.py.
 */
@SuppressLint("MissingPermission")
class BandScanner(private val context: Context, private val log: DebugLog) {

    fun scan(): Flow<List<FoundDevice>> = callbackFlow {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) { log.w("scan: Bluetooth unavailable or off"); close(); return@callbackFlow }
        val found = LinkedHashMap<String, FoundDevice>()
        fun publish() { trySend(found.values.sortedWith(compareByDescending<FoundDevice> { it.isWhoop }.thenByDescending { it.rssi ?: -127 })) }

        runCatching {
            manager.getConnectedDevices(BluetoothProfile.GATT).filter { isWhoopName(it.name) }
                .forEach { found[it.address] = FoundDevice(it.address, it.name, null, true, "connected") }
            adapter.bondedDevices.filter { isWhoopName(it.name) && it.address !in found }
                .forEach { found[it.address] = FoundDevice(it.address, it.name, null, true, "paired") }
        }.onFailure { log.w("listing connected/paired devices failed: ${it.message}") }
        publish()

        var results = 0
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                results++
                val name = r.scanRecord?.deviceName ?: r.device.name
                val whoop = isWhoopName(name) || r.scanRecord?.serviceUuids?.contains(ParcelUuid(WhoopUuids.SERVICE)) == true
                val prev = found[r.device.address]
                if (prev == null || prev.via == "nearby") found[r.device.address] = FoundDevice(r.device.address, name ?: prev?.name, r.rssi, whoop, "nearby")
                publish()
            }
            override fun onScanFailed(errorCode: Int) { log.w("scan failed: error $errorCode"); close(GattException("scan failed ($errorCode)")) }
        }
        val scanner = adapter.bluetoothLeScanner
        log.i("scan started")
        scanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        awaitClose {
            runCatching { scanner?.stopScan(cb) }
            log.i("scan stopped: $results results, ${found.values.count { it.isWhoop }} Whoop band(s)")
        }
    }

    companion object {
        fun isWhoopName(n: String?) = n != null && n.uppercase(Locale.ROOT).contains("WHOOP")
    }
}
