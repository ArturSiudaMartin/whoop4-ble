package dev.whoop4.bloop.ble

import android.content.Context
import dev.whoop4.bloop.protocol.ExperimentalCommands
import dev.whoop4.bloop.protocol.HeartRateMeasurement
import dev.whoop4.bloop.protocol.Packet
import dev.whoop4.bloop.protocol.PacketType
import dev.whoop4.bloop.protocol.RealtimeHr
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the band connection and implements the agreed model (plan §7, D2):
 *  - **Sync**: connect → download (SyncEngine) → disconnect, unless a live session is running.
 *  - **Live** ("Go live"): stay connected and stream HR/RR until stopped, reconnecting automatically.
 * Bluetooth stays out of the UI: screens only observe [status] and call these methods.
 */
class BandManager(
    context: Context,
    val log: DebugLog,
    private val scope: CoroutineScope,
    private val storeFor: (address: String) -> SyncStore,
) {
    private val gatt = GattClient(context, log)
    private val link = WhoopLink(gatt, log, scope)
    val scanner = BandScanner(context, log)

    private val _status = MutableStateFlow(BandStatus())
    val status: StateFlow<BandStatus> = _status
    private val _heartRate = MutableSharedFlow<HeartRateMeasurement>(extraBufferCapacity = 256)
    /**
     * Every live HR measurement (the app stores them): from the standard Heart Rate service, or from the
     * band's REALTIME_DATA stream when the service has sent nothing for [STREAM_FALLBACK_MS].
     */
    val heartRate: SharedFlow<HeartRateMeasurement> = _heartRate
    @Volatile private var lastServiceHrAt = 0L

    private val connectLock = Mutex()
    private var liveJob: Job? = null
    private var syncJob: Job? = null
    private var engine: SyncEngine? = null

    init {
        scope.launch {
            link.heartRate.collect { m ->
                lastServiceHrAt = System.currentTimeMillis()
                onLiveHr(m, "Heart Rate service")
            }
        }
        // Frames that arrive outside a sync (events, console logs) are logged and stored; REALTIME_DATA heart
        // rate is used at any time, including during a sync.
        scope.launch {
            var logged = 0
            var realtimeSeen = 0
            link.frames.collect { f ->
                val p = Packet.parse(f) ?: return@collect
                RealtimeHr.parse(p)?.let { r ->
                    if (realtimeSeen++ == 0) log.i("band realtime HR stream active (${r.hr} bpm)")
                    if (_status.value.liveRequested && System.currentTimeMillis() - lastServiceHrAt > STREAM_FALLBACK_MS) onLiveHr(HeartRateMeasurement(r.hr, emptyList()), "band realtime stream")
                    return@collect
                }
                if (_status.value.syncing) return@collect
                if (logged < 200 || _status.value.buzzing) { logged++; log.rx("unsolicited type=0x%02x cmd=0x%02x %s".format(p.type, p.cmd, f.hex())) }
                if (p.type == PacketType.EVENT) _status.value.address?.let { a -> runCatching { storeFor(a).storeBatch(0, listOf(f)) } }
            }
        }
        scope.launch { gatt.connected.collect { c -> if (!c) _status.update { if (it.connection == ConnectionState.CONNECTED) it.copy(connection = ConnectionState.DISCONNECTED) else it } } }
    }

    private fun onLiveHr(m: HeartRateMeasurement, source: String) {
        val now = System.currentTimeMillis()
        _heartRate.tryEmit(m)
        _status.update { s ->
            s.copy(
                lastHr = m.hr.takeIf { it > 0 } ?: s.lastHr, lastHrAtUtcMs = if (m.hr > 0) now else s.lastHrAtUtcMs,
                lastRrMs = m.rrMs.lastOrNull() ?: s.lastRrMs, liveSource = source,
                liveNote = if (m.hr == 0) "The band reports no skin contact. Wear it snugly with the sensor on your skin." else null,
            )
        }
    }

    fun selectBand(address: String, name: String?) {
        if (_status.value.address != address) disconnect()
        link.address = address
        _status.update { it.copy(address = address, name = name, connection = ConnectionState.DISCONNECTED, error = null) }
        log.i("band selected: ${name ?: "?"} $address")
    }

    private suspend fun ensureConnected() = connectLock.withLock {
        if (link.isConnected) return@withLock
        _status.update { it.copy(connection = ConnectionState.CONNECTING, error = null) }
        try {
            link.connect()
            _status.update { it.copy(connection = ConnectionState.CONNECTED) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _status.update { it.copy(connection = ConnectionState.ERROR, error = e.message) }
            throw e
        }
    }

    /** Connects without starting anything (to check the band is reachable). */
    fun connect() { scope.launch { runCatching { ensureConnected() }.onFailure { log.w("connect failed: ${it.message}") } } }

    fun disconnect() {
        liveJob?.cancel(); liveJob = null
        engine?.requestStop()
        _status.update { it.copy(liveRequested = false, liveSource = null, liveNote = null) }
        scope.launch {
            syncJob?.join()
            link.disconnect()
            _status.update { it.copy(connection = if (it.address == null) ConnectionState.NO_BAND else ConnectionState.DISCONNECTED) }
        }
    }

    // ------------------------------------------------------------------ live

    fun startLive() {
        if (liveJob?.isActive == true) return
        _status.update { it.copy(liveRequested = true, liveNote = "Connecting…") }
        log.i("live: requested")
        liveJob = scope.launch {
            while (isActive) {
                try {
                    _status.first { !it.syncing }           // a sync owns the connection while it runs
                    ensureConnected()
                    startHeartRate()
                    // Stay until the link drops or a sync takes over (it may reconnect between passes).
                    combine(gatt.connected, _status) { c, s -> !c || s.syncing }.first { it }
                    if (!_status.value.syncing) { log.w("live: connection lost, reconnecting"); _status.update { it.copy(liveNote = "Connection lost. Reconnecting…") } }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    log.w("live: ${e.message}")
                    _status.update { it.copy(liveNote = "Can't reach the band (${e.message}). Retrying…") }
                }
                delay(3_000)
            }
        }
    }

    fun stopLive() {
        liveJob?.cancel(); liveJob = null
        _status.update { it.copy(liveRequested = false, liveSource = null, liveNote = null) }
        log.i("live: stopped")
        scope.launch {
            if (link.isConnected) runCatching { link.setHeartRateNotifications(false) }
            if (!_status.value.syncing) link.disconnect()   // periodic model: don't hold the connection
        }
    }

    /** Subscribes to the standard HR service; falls back to the unverified enable-HR commands (logged). */
    private suspend fun startHeartRate() = kotlinx.coroutines.coroutineScope {
        if (link.hasHeartRateService) {
            val w = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(7_000) { heartRate.first() } }
            link.setHeartRateNotifications(true)
            log.i("live: subscribed to the Heart Rate service")
            _status.update { it.copy(liveNote = "Waiting for heart rate…") }
            if (w.await() != null) { _status.update { it.copy(liveNote = null) }; return@coroutineScope }
            log.w("live: no measurement within 7 s")
        } else log.w("live: the band does not expose the standard Heart Rate service")

        log.w("EXPERIMENTAL: sending the unverified enable-HR commands 0x0E and 0x03 (as the web and legacy apps do)")
        link.write(ExperimentalCommands.enableGenericHrProfile().also { log.tx("EXPERIMENTAL TOGGLE_GENERIC_HR_PROFILE", it) })
        link.write(ExperimentalCommands.enableRealtimeHr().also { log.tx("EXPERIMENTAL TOGGLE_REALTIME_HR", it) })
        delay(2_500)
        if (!link.hasHeartRateService) runCatching { link.rediscover() }
        val w = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(8_000) { heartRate.first() } }
        if (link.hasHeartRateService) runCatching { link.setHeartRateNotifications(true) }
        val ok = w.await() != null
        log.i("EXPERIMENTAL result: heart rate ${if (ok) "STARTED after the enable commands" else "still not received"}; HR service ${if (link.hasHeartRateService) "present" else "absent"}")
        _status.update {
            it.copy(liveNote = if (ok) null else "No heart rate from the band yet. Check it is snug on your wrist and that the official Whoop app is closed. Share the debug log from the Device screen if this persists.")
        }
    }

    // ------------------------------------------------------------------ sync

    fun sync(readOnly: Boolean, trigger: String = "manual") {
        val address = _status.value.address ?: return log.w("sync: no band selected")
        if (syncJob?.isActive == true) return
        val e = SyncEngine(link, storeFor(address), log).also { engine = it }
        _status.update { it.copy(syncing = true, sync = null) }
        syncJob = scope.launch {
            val progressJob = launch { e.progress.collect { p -> _status.update { it.copy(sync = p) } } }
            try {
                ensureConnected()
                e.run(readOnly, trigger)
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                log.w("sync could not start: ${ex.message}")
                _status.update { it.copy(sync = SyncProgress(finished = true, outcome = "FAILED", error = ex.message)) }
            } finally {
                progressJob.cancel()
                _status.update { it.copy(syncing = false) }
                if (!_status.value.liveRequested) runCatching { link.disconnect() }   // live loop re-subscribes itself
                engine = null
            }
        }
    }

    fun stopSync() { engine?.requestStop() }

    // ------------------------------------------------------------------ haptics (wake alarm)

    @Volatile private var stopBuzzRequested = false

    /**
     * Connects (retrying until [deadlineMs]) and sends RUN_ALARM so the band vibrates. Returns true once the
     * command was written. The link then stays open for up to [BUZZ_HOLD_MS] (until [stopBuzz]), so a stop is
     * sent at once instead of after a reconnect; afterwards it disconnects unless a live session or sync uses it.
     */
    suspend fun buzz(reason: String, deadlineMs: Long = System.currentTimeMillis() + 60_000): Boolean {
        if (_status.value.address == null) { log.w("buzz ($reason): no band selected"); return false }
        stopBuzzRequested = false
        _status.update { it.copy(buzzing = true) }
        var sent = false
        try {
            while (!stopBuzzRequested) {
                try {
                    ensureConnected()
                    link.write(ExperimentalCommands.runAlarm().also { log.tx("EXPERIMENTAL RUN_ALARM ($reason)", it) })
                    log.i("buzz ($reason): RUN_ALARM written")
                    sent = true
                    break
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    log.w("buzz ($reason): ${e.message}")
                    if (System.currentTimeMillis() > deadlineMs) return false
                    delay(5_000)
                }
            }
            if (sent) withTimeoutOrNull(BUZZ_HOLD_MS) { while (!stopBuzzRequested) delay(200) }
            return sent
        } finally {
            _status.update { it.copy(buzzing = false) }
            delay(2_000)   // let a stop command and any response through before disconnecting
            if (!_status.value.liveRequested && !_status.value.syncing && !_status.value.buzzing) runCatching { link.disconnect() }
        }
    }

    /** Stops the vibration: STOP_HAPTICS, then DISABLE_ALARM (both unconfirmed, both logged with any reply). */
    fun stopBuzz() {
        stopBuzzRequested = true
        log.i("stop buzz: requested")
        scope.launch {
            runCatching {
                ensureConnected()
                link.write(ExperimentalCommands.stopHaptics().also { log.tx("EXPERIMENTAL STOP_HAPTICS", it) })
                delay(300)
                link.write(ExperimentalCommands.disableAlarm().also { log.tx("EXPERIMENTAL DISABLE_ALARM", it) })
                delay(2_000)
            }.onFailure { log.w("stop buzz: ${it.message}") }
            if (!_status.value.liveRequested && !_status.value.syncing && !_status.value.buzzing) runCatching { link.disconnect() }
        }
    }

    private companion object {
        const val STREAM_FALLBACK_MS = 5_000L
        const val BUZZ_HOLD_MS = 90_000L
    }
}
