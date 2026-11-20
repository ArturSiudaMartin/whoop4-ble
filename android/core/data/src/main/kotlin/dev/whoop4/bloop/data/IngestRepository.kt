package dev.whoop4.bloop.data

import androidx.room.withTransaction
import dev.whoop4.bloop.protocol.EventParser
import dev.whoop4.bloop.protocol.HeartRateMeasurement
import dev.whoop4.bloop.protocol.HistoricalParser
import dev.whoop4.bloop.protocol.Packet
import dev.whoop4.bloop.protocol.PacketType
import java.nio.ByteBuffer
import java.security.MessageDigest

/** What one [IngestRepository.ingestFrames] call stored. "New" counts exclude duplicates already present. */
data class IngestResult(
    val frames: Int,
    val newRawPackets: Int,
    val records: Int,          // HISTORICAL_DATA frames that decoded to a valid record
    val rejectedRecords: Int,  // HISTORICAL_DATA frames the reference decoder rejects (kept raw only)
    val newSamples: Int,
    val newRr: Int,
    val events: Int,
    val newEvents: Int,
)

/**
 * Turns decoded band data into rows. Each call is **one database transaction**: when it returns,
 * everything in the batch is durably stored. The sync engine (Phase 5) relies on this to send the
 * HISTORICAL_DATA_RESULT ACK (which lets the band discard the batch) only after this call has returned.
 */
class IngestRepository(private val db: BloopDatabase, private val keepRawPackets: () -> Boolean = { true }) {

    /**
     * Stores a batch of frames received from CMD_FROM / DATA_FROM during a sync: raw frames (optional),
     * historical samples + RR, and events. Non-data frames (metadata, responses, console logs) are only archived raw.
     */
    suspend fun ingestFrames(device: String, frames: List<ByteArray>, receivedAtUtcMs: Long, syncRunId: Long? = null): IngestResult {
        val raws = ArrayList<RawPacketEntity>(frames.size)
        val samples = ArrayList<HrSampleEntity>()
        val rrs = ArrayList<RrIntervalEntity>()
        val events = ArrayList<BandEventEntity>()
        var records = 0; var rejected = 0

        for (f in frames) {
            val pkt = Packet.parse(f) ?: continue
            if (keepRawPackets()) raws.add(RawPacketEntity(0, device, receivedAtUtcMs, pkt.type, f, hash64(f), syncRunId))
            when (pkt.type) {
                PacketType.HISTORICAL_DATA -> {
                    val r = HistoricalParser.parse(pkt.data)
                    if (r == null) { rejected++; continue }
                    records++
                    val ts = r.timestamp * 1000
                    samples.add(HrSampleEntity(
                        deviceAddress = device, source = SampleSource.HISTORICAL, tsUtcMs = ts, subKey = r.subsec,
                        hr = r.hr, bandSequence = r.sequence,
                        accelX = r.accel?.x, accelY = r.accel?.y, accelZ = r.accel?.z, syncRunId = syncRunId,
                    ))
                    r.rrMs.forEachIndexed { i, ms ->
                        rrs.add(RrIntervalEntity(0, device, SampleSource.HISTORICAL, ts, r.subsec, i, ms.toDouble()))
                    }
                }
                PacketType.EVENT -> EventParser.parse(pkt)?.let { e ->
                    events.add(BandEventEntity(
                        deviceAddress = device, receivedAtUtcMs = receivedAtUtcMs, eventType = e.eventType, name = e.name,
                        deviceClock = e.deviceClock, subClock = e.subClock, batteryPct = e.batteryPct, skinTempC = e.skinTempC,
                        payloadHex = e.payload.joinToString("") { "%02x".format(it.toInt() and 0xFF) }, rawHex = e.rawHex,
                        syncRunId = syncRunId,
                    ))
                }
            }
        }

        return db.withTransaction {
            val newRaw = if (raws.isEmpty()) 0 else db.rawPackets().insert(raws).inserted()
            val newSamples = if (samples.isEmpty()) 0 else db.samples().insertSamples(samples).inserted()
            val newRr = if (rrs.isEmpty()) 0 else db.samples().insertRr(rrs).inserted()
            val newEvents = if (events.isEmpty()) 0 else db.events().insert(events).inserted()
            events.lastOrNull { it.batteryPct != null }?.let { updateBattery(device, it.batteryPct!!, receivedAtUtcMs) }
            IngestResult(frames.size, newRaw, records, rejected, newSamples, newRr, events.size, newEvents)
        }
    }

    /** Stores one live 0x2A37 notification (HR + RR) received at [receivedAtUtcMs]. Returns true if it was new. */
    suspend fun ingestLive(device: String, m: HeartRateMeasurement, receivedAtUtcMs: Long): Boolean = db.withTransaction {
        if (m.hr <= 0) return@withTransaction false   // 0 = no skin contact; not a reading
        val added = db.samples().insertSamples(listOf(
            HrSampleEntity(deviceAddress = device, source = SampleSource.LIVE, tsUtcMs = receivedAtUtcMs, subKey = 0, hr = m.hr),
        )).inserted() > 0
        if (added && m.rrMs.isNotEmpty()) {
            db.samples().insertRr(m.rrMs.mapIndexed { i, ms ->
                RrIntervalEntity(0, device, SampleSource.LIVE, receivedAtUtcMs, 0, i, ms)
            })
        }
        added
    }

    private suspend fun updateBattery(device: String, pct: Double, atUtcMs: Long) {
        val d = db.devices().get(device) ?: DeviceEntity(device, null, atUtcMs, firstSeenUtcMs = atUtcMs)
        db.devices().upsert(d.copy(lastBatteryPct = pct, lastBatteryAtUtcMs = atUtcMs))
    }

    companion object {
        /** First 8 bytes of SHA-256: a collision-safe dedup key for raw frames at millions of rows. */
        fun hash64(bytes: ByteArray): Long =
            ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 8).long
    }
}
