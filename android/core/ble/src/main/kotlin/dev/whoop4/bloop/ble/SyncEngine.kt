package dev.whoop4.bloop.ble

import dev.whoop4.bloop.protocol.ClockParser
import dev.whoop4.bloop.protocol.Commands
import dev.whoop4.bloop.protocol.ConsoleParser
import dev.whoop4.bloop.protocol.HistoricalParser
import dev.whoop4.bloop.protocol.MetadataParser
import dev.whoop4.bloop.protocol.MetadataType
import dev.whoop4.bloop.protocol.Packet
import dev.whoop4.bloop.protocol.PacketType
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The band connection as the sync engine sees it (implemented over GATT by [WhoopLink], faked in tests). */
interface BandLink {
    val isConnected: Boolean
    /** Complete frames from CMD_FROM and DATA_FROM, already reassembled. */
    val frames: SharedFlow<ByteArray>
    /** Connects and enables notifications on the Whoop service. */
    suspend fun connect()
    suspend fun disconnect()
    suspend fun write(frame: ByteArray)
}

/** Durable storage for a sync. [storeBatch] must only return once the frames are committed. */
interface SyncStore {
    suspend fun startRun(readOnly: Boolean, trigger: String): Long
    /** Stores frames durably; returns how many *new* heart-rate samples they contained. */
    suspend fun storeBatch(runId: Long, frames: List<ByteArray>): Int
    suspend fun updateRun(runId: Long, p: SyncProgress)
    suspend fun lastHwRtc(): Long?
    suspend fun saveHwRtc(hwRtc: Long, atUtcMs: Long)
}

data class SyncProgress(
    val runId: Long = 0,
    val readOnly: Boolean = false,
    val pass: Int = 0,
    val batches: Int = 0,
    val packets: Long = 0,
    val newSamples: Long = 0,
    val lastTrim: Long? = null,
    val latestRecordUtcS: Long? = null,
    val hwRtc: Long? = null,
    val rtcDriftS: Long? = null,
    val rtcInvalid: Boolean = false,
    val rebootDetected: Boolean = false,
    val complete: Boolean = false,
    val finished: Boolean = false,
    /** One of SyncOutcome-style strings: RUNNING, COMPLETE, PARTIAL, STOPPED, FAILED. */
    val outcome: String = "RUNNING",
    val error: String? = null,
)

/** Timing parameters; defaults are drain.py's (field-tested). */
data class SyncConfig(
    val maxPasses: Int = 30,
    val idleTimeoutMs: Long = 20_000,
    val passCapMs: Long = 600_000,
    val emptyPassesToStop: Int = 3,
    val commitEveryFrames: Int = 2_000,
    val reconnectDelayMs: Long = 2_000,
    val connectAttempts: Int = 3,
)

/**
 * Historical download: a port of src/whoop4/drain.py (and the web app's equivalent).
 *
 * Per pass: SET_CLOCK (5-byte) → SEND_HISTORICAL_DATA → GET_CLOCK, then receive HISTORICAL_DATA/EVENT frames.
 * On each METADATA HISTORY_END the pending frames are **committed first** and only then ACKed with
 * HISTORICAL_DATA_RESULT (unless read-only). A pass ends on HISTORY_COMPLETE, after [SyncConfig.idleTimeoutMs]
 * with no frames, after [SyncConfig.passCapMs], on disconnect, or when stopped. Between passes the link is
 * reconnected. The sync stops after HISTORY_COMPLETE, after N consecutive passes with no new samples, after
 * the pass limit, or after a single pass in read-only mode (without ACKs the band would resend the same batch).
 */
class SyncEngine(
    private val link: BandLink,
    private val store: SyncStore,
    private val log: DebugLog,
    private val config: SyncConfig = SyncConfig(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val _progress = MutableStateFlow<SyncProgress?>(null)
    val progress: StateFlow<SyncProgress?> = _progress
    @Volatile private var stopRequested = false

    fun requestStop() { stopRequested = true }

    suspend fun run(readOnly: Boolean, trigger: String): SyncProgress {
        stopRequested = false
        val runId = store.startRun(readOnly, trigger)
        var p = SyncProgress(runId = runId, readOnly = readOnly)
        _progress.value = p
        log.i("sync #$runId start (trigger=$trigger, ${if (readOnly) "READ-ONLY, no ACKs" else "ACK batches"})")
        var emptyPasses = 0
        try {
            while (p.pass < config.maxPasses && !stopRequested) {
                p = p.copy(pass = p.pass + 1)
                if (p.pass > 1 || !link.isConnected) {
                    if (p.pass > 1) { runCatching { link.disconnect() }; delay(config.reconnectDelayMs) }
                    connectWithRetry()
                }
                val before = p.newSamples
                p = onePass(p, readOnly)
                val added = p.newSamples - before
                log.i("sync #$runId pass ${p.pass}: +$added new samples, ${p.batches} batches total${if (p.complete) ", HISTORY_COMPLETE" else ""}")
                store.updateRun(runId, p)
                if (p.complete || readOnly || stopRequested) break
                if (!link.isConnected) log.w("connection lost during pass ${p.pass}; will reconnect")
                if (added == 0L) { if (++emptyPasses >= config.emptyPassesToStop) { log.i("$emptyPasses empty passes: done"); break } }
                else emptyPasses = 0
            }
            p = p.copy(finished = true, outcome = when {
                stopRequested -> "STOPPED"
                p.complete -> "COMPLETE"
                else -> "PARTIAL"
            })
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                p = p.copy(finished = true, outcome = "STOPPED", error = "cancelled")
                store.updateRun(runId, p); _progress.value = p
                throw e
            }
            log.w("sync #$runId failed: ${e.javaClass.simpleName}: ${e.message}")
            p = p.copy(finished = true, outcome = "FAILED", error = e.message ?: e.javaClass.simpleName)
        }
        store.updateRun(runId, p)
        _progress.value = p
        log.i("sync #$runId finished: ${p.outcome}, ${p.newSamples} new samples, ${p.batches} batches, ${p.pass} passes")
        return p
    }

    private suspend fun connectWithRetry() {
        var last: Exception? = null
        repeat(config.connectAttempts) { i ->
            try { link.connect(); return } catch (e: Exception) {
                last = e
                log.w("connect attempt ${i + 1} failed: ${e.message}")
                delay(config.reconnectDelayMs)
            }
        }
        throw last ?: IllegalStateException("connect failed")
    }

    private suspend fun onePass(start: SyncProgress, readOnly: Boolean): SyncProgress = coroutineScope {
        var p = start
        val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        // UNDISPATCHED: the collector subscribes before any command is written, so no frame can be missed.
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { link.frames.collect { inbox.send(it) } }
        val pending = ArrayList<ByteArray>()
        var historicalLogged = 0

        suspend fun commit() {
            if (pending.isEmpty()) return
            val added = store.storeBatch(p.runId, ArrayList(pending))
            pending.clear()
            p = p.copy(newSamples = p.newSamples + added)
            _progress.value = p
        }

        /** Returns false if the link dropped (the pass then ends normally); other write errors propagate. */
        suspend fun send(what: String, frame: ByteArray): Boolean {
            log.tx(what, frame)
            return try { link.write(frame); true } catch (e: Exception) {
                if (link.isConnected) throw e
                log.w("write $what failed, link lost: ${e.message}"); false
            }
        }

        val nowS = nowMs() / 1000
        if (send("SET_CLOCK($nowS)", Commands.setClock(nowS))) {
            delay(300)
            if (send("SEND_HISTORICAL_DATA", Commands.sendHistoricalData())) {
                delay(800)
                send("GET_CLOCK", Commands.getClock())
            }
        }

        val passStart = nowMs()
        var lastActivity = nowMs()
        var consoleBuf = ""
        // Frames already received are always processed (and committed), even after the link drops.
        while (!stopRequested) {
            val f = withTimeoutOrNull(250) { inbox.receive() }
            if (f == null) {
                if (!link.isConnected) break
                if (nowMs() - lastActivity > config.idleTimeoutMs) { log.i("pass ${p.pass}: ${config.idleTimeoutMs / 1000}s without data"); break }
                if (nowMs() - passStart > config.passCapMs) { log.i("pass ${p.pass}: time cap reached"); break }
                continue
            }
            val pkt = Packet.parse(f) ?: continue
            lastActivity = nowMs()
            when (pkt.type) {
                PacketType.HISTORICAL_DATA -> {
                    pending.add(f)
                    val r = HistoricalParser.parse(pkt.data)
                    if (historicalLogged < 3) { historicalLogged++; log.rx("HISTORICAL ${f.hex()} -> ${r ?: "rejected"}") }
                    p = p.copy(packets = p.packets + 1, latestRecordUtcS = maxOf(p.latestRecordUtcS ?: 0, r?.timestamp ?: 0).takeIf { it > 0 })
                    if (pending.size >= config.commitEveryFrames) commit()   // bounded memory; no ACK without HISTORY_END
                }
                PacketType.EVENT -> { pending.add(f); log.rx("EVENT ${f.hex()}") }
                PacketType.METADATA -> {
                    pending.add(f)
                    val m = MetadataParser.parse(pkt)!!
                    log.rx("METADATA kind=${m.kind} start=${m.startOffset} end=${m.endOffset} trim=${m.trim}")
                    when (m.kind) {
                        MetadataType.HISTORY_END -> {
                            commit()   // durable BEFORE the ACK lets the band discard the batch
                            p = p.copy(batches = p.batches + 1, lastTrim = m.trim ?: p.lastTrim)
                            if (!readOnly && m.trim != null) send("HISTORICAL_DATA_RESULT(trim=${m.trim})", Commands.historicalDataResult(m.trim!!))
                        }
                        MetadataType.HISTORY_COMPLETE -> { commit(); p = p.copy(complete = true) }
                    }
                    _progress.value = p
                    if (p.complete) break
                }
                PacketType.COMMAND_RESPONSE -> {
                    log.rx("RESPONSE cmd=0x%02x %s".format(pkt.cmd, pkt.data.hex()))
                    ClockParser.parse(pkt)?.hwRtc?.let { rtc -> p = onClock(p, rtc) }
                }
                PacketType.CONSOLE_LOGS -> {
                    consoleBuf += ConsoleParser.parse(pkt).orEmpty()
                    val parts = consoleBuf.split('\n')
                    consoleBuf = parts.last()
                    for (line in parts.dropLast(1)) if (line.isNotBlank()) {
                        log.log("band", line.trim())
                        ConsoleParser.invalidRtcValue(line)?.let {
                            log.w("band reports an INVALID RTC ($it): flash writes are failing until SET_CLOCK takes effect")
                            p = p.copy(rtcInvalid = true)
                        }
                    }
                }
                else -> log.rx("type=0x%02x cmd=0x%02x %s".format(pkt.type, pkt.cmd, pkt.data.hex()))
            }
        }
        if (stopRequested && link.isConnected) runCatching { send("ABORT_HISTORICAL_TRANSMITS", Commands.abortHistoricalTransmits()) }
        commit()   // anything after the last HISTORY_END is stored but not ACKed: the band will send it again
        collector.cancel()
        inbox.close()
        _progress.value = p
        p
    }

    private suspend fun onClock(p: SyncProgress, rtc: Long): SyncProgress {
        val now = nowMs()
        val valid = rtc > HistoricalParser.TS_MIN
        val drift = rtc - now / 1000
        val prev = store.lastHwRtc()
        val reboot = prev != null && rtc < prev - 60
        log.i("GET_CLOCK hw_rtc=$rtc ${if (valid) "valid, drift ${drift}s" else "INVALID"}${if (reboot) ", REBOOT DETECTED (was $prev)" else ""}")
        if (valid) store.saveHwRtc(rtc, now)
        return p.copy(hwRtc = rtc, rtcDriftS = if (valid) drift else null, rtcInvalid = p.rtcInvalid || !valid,
            rebootDetected = p.rebootDetected || reboot)
    }
}
