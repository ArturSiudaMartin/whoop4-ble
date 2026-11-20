package dev.whoop4.bloop.app

import dev.whoop4.bloop.ble.SyncProgress
import dev.whoop4.bloop.ble.SyncStore
import dev.whoop4.bloop.data.BloopDatabase
import dev.whoop4.bloop.data.DeviceEntity
import dev.whoop4.bloop.data.IngestRepository
import dev.whoop4.bloop.data.SyncRunEntity

/** Connects the sync engine (core:ble) to the database (core:data). */
class RoomSyncStore(private val db: BloopDatabase, private val ingest: IngestRepository, private val address: String) : SyncStore {

    override suspend fun startRun(readOnly: Boolean, trigger: String): Long =
        db.syncRuns().insert(SyncRunEntity(deviceAddress = address, startedAtUtcMs = System.currentTimeMillis(), trigger = trigger, readOnly = readOnly))

    /** One transaction per call (IngestRepository), so the engine's ACK only follows durable storage. */
    override suspend fun storeBatch(runId: Long, frames: List<ByteArray>): Int =
        ingest.ingestFrames(address, frames, System.currentTimeMillis(), runId.takeIf { it > 0 }).newSamples

    override suspend fun updateRun(runId: Long, p: SyncProgress) {
        val run = db.syncRuns().get(runId) ?: return
        db.syncRuns().update(run.copy(
            endedAtUtcMs = if (p.finished) System.currentTimeMillis() else null,
            passes = p.pass, batches = p.batches, packets = p.packets, newSamples = p.newSamples,
            lastTrim = p.lastTrim, hwRtc = p.hwRtc, rtcDriftS = p.rtcDriftS, rebootDetected = p.rebootDetected,
            outcome = p.outcome, error = p.error ?: if (p.rtcInvalid) "band RTC invalid" else null,
        ))
    }

    override suspend fun lastHwRtc(): Long? = db.devices().get(address)?.lastHwRtc

    override suspend fun saveHwRtc(hwRtc: Long, atUtcMs: Long) {
        val d = db.devices().get(address) ?: DeviceEntity(address, null, atUtcMs, firstSeenUtcMs = atUtcMs)
        db.devices().upsert(d.copy(lastHwRtc = hwRtc, lastHwRtcAtUtcMs = atUtcMs, lastSeenUtcMs = atUtcMs))
    }
}
