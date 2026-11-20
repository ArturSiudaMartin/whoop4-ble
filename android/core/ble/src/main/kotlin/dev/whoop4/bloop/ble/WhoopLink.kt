package dev.whoop4.bloop.ble

import dev.whoop4.bloop.protocol.FrameAssembler
import dev.whoop4.bloop.protocol.HeartRateMeasurement
import dev.whoop4.bloop.protocol.WhoopUuids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * The Whoop band over GATT: reassembles frames from CMD_FROM / DATA_FROM into [frames] (for the sync
 * engine and diagnostics) and parses the standard Heart Rate service into [heartRate].
 */
class WhoopLink(private val gatt: GattClient, private val log: DebugLog, private val scope: CoroutineScope) : BandLink {
    var address: String? = null

    private val _frames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 20_000)
    override val frames: SharedFlow<ByteArray> = _frames
    private val _heartRate = MutableSharedFlow<HeartRateMeasurement>(extraBufferCapacity = 256)
    val heartRate: SharedFlow<HeartRateMeasurement> = _heartRate

    private val asmCmd = FrameAssembler { _frames.tryEmit(it) }
    private val asmData = FrameAssembler { _frames.tryEmit(it) }
    private var pump: Job? = null

    override val isConnected: Boolean get() = gatt.connected.value
    val hasHeartRateService: Boolean get() = gatt.characteristic(WhoopUuids.HEART_RATE_SERVICE, WhoopUuids.HEART_RATE_MEASUREMENT) != null

    override suspend fun connect() {
        val a = address ?: throw GattException("no band selected")
        gatt.connect(a)
        if (gatt.characteristic(WhoopUuids.SERVICE, WhoopUuids.CMD_TO) == null) {
            gatt.disconnect(); throw GattException("this device has no Whoop service")
        }
        asmCmd.reset(); asmData.reset()
        if (pump == null) pump = scope.launch {
            gatt.notifications.collect { (uuid, bytes) ->
                when (uuid) {
                    WhoopUuids.CMD_FROM -> asmCmd.feed(bytes)
                    WhoopUuids.DATA_FROM -> asmData.feed(bytes)
                    WhoopUuids.HEART_RATE_MEASUREMENT -> HeartRateMeasurement.parse(bytes)?.let { _heartRate.tryEmit(it) }
                        ?: log.w("unparseable HR measurement ${bytes.hex()}")
                }
            }
        }
        gatt.enableNotifications(WhoopUuids.SERVICE, WhoopUuids.CMD_FROM)
        gatt.enableNotifications(WhoopUuids.SERVICE, WhoopUuids.DATA_FROM)
        log.i("Whoop service ready (HR service ${if (hasHeartRateService) "present" else "not exposed"})")
    }

    override suspend fun disconnect() = gatt.disconnect()

    override suspend fun write(frame: ByteArray) = gatt.write(WhoopUuids.SERVICE, WhoopUuids.CMD_TO, frame)

    suspend fun setHeartRateNotifications(enable: Boolean) =
        gatt.enableNotifications(WhoopUuids.HEART_RATE_SERVICE, WhoopUuids.HEART_RATE_MEASUREMENT, enable)

    suspend fun rediscover() = gatt.rediscover()
}
