package dev.whoop4.bloop.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Bloop's local schema. Conventions:
 *  - Every timestamp is UTC epoch milliseconds (`…UtcMs`). Local time is applied only for display.
 *  - Unique indices are the deduplication keys: re-syncing the same data inserts nothing new
 *    (rows are inserted with OnConflictStrategy.IGNORE).
 *  - Values Bloop computes (sleep, metrics) always carry a method, method version and confidence,
 *    so measured and estimated values can never be confused.
 */

/** Where a heart-rate sample came from. */
object SampleSource {
    /** Downloaded from the band's flash (HISTORICAL_DATA). */
    const val HISTORICAL = 0
    /** Streamed live from the standard Heart Rate service (0x2A37). */
    const val LIVE = 1
}

@Entity(tableName = "device")
data class DeviceEntity(
    @PrimaryKey val address: String,
    val name: String?,
    val lastSeenUtcMs: Long?,
    val firstSeenUtcMs: Long? = null,
    val lastBatteryPct: Double? = null,
    val lastBatteryAtUtcMs: Long? = null,
    /** Last GET_CLOCK hardware RTC (Unix seconds) and when it was read, for drift/reboot detection. */
    val lastHwRtc: Long? = null,
    val lastHwRtcAtUtcMs: Long? = null,
)

/**
 * One heart-rate sample.
 * Dedup key: (device, source, tsUtcMs, subKey). For HISTORICAL rows tsUtcMs = record timestamp × 1000 and
 * subKey = the record's sub-second tiebreaker; for LIVE rows tsUtcMs = phone receive time and subKey = 0.
 * [bandSequence] (record bytes 0-3) is kept so the key can be validated against real syncs.
 */
@Entity(
    tableName = "hr_sample",
    indices = [
        Index(value = ["deviceAddress", "source", "tsUtcMs", "subKey"], unique = true),
        Index(value = ["deviceAddress", "tsUtcMs"]),
    ],
)
data class HrSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceAddress: String,
    val source: Int,
    val tsUtcMs: Long,
    val subKey: Int,
    val hr: Int,
    val bandSequence: Long? = null,
    val accelX: Float? = null,
    val accelY: Float? = null,
    val accelZ: Float? = null,
    val syncRunId: Long? = null,
)

/** One RR (beat-to-beat) interval, attached to the sample it arrived with. [idx] is its position in that sample. */
@Entity(
    tableName = "rr_interval",
    indices = [
        Index(value = ["deviceAddress", "source", "sampleTsUtcMs", "subKey", "idx"], unique = true),
        Index(value = ["deviceAddress", "sampleTsUtcMs"]),
    ],
)
data class RrIntervalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceAddress: String,
    val source: Int,
    val sampleTsUtcMs: Long,
    val subKey: Int,
    val idx: Int,
    /** Milliseconds. Historical values are whole ms; live values come from 1/1024 s units. */
    val rrMs: Double,
)

/** A decoded EVENT packet. Dedup key: (device, rawHex). The raw bytes include the band's event clock. */
@Entity(
    tableName = "band_event",
    indices = [
        Index(value = ["deviceAddress", "rawHex"], unique = true),
        Index(value = ["deviceAddress", "receivedAtUtcMs"]),
    ],
)
data class BandEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceAddress: String,
    val receivedAtUtcMs: Long,
    val eventType: Int,
    /** Python-compatible event name (e.g. "battery", "wrist_on", "unknown_32"); null where the reference sets none. */
    val name: String?,
    val deviceClock: Long?,
    val subClock: Int?,
    val batteryPct: Double?,
    val skinTempC: Double?,
    val payloadHex: String,
    val rawHex: String,
    val syncRunId: Long? = null,
)

/**
 * Every frame received during a sync, stored verbatim so it can be re-decoded later when more of the
 * protocol is understood (as drain.py does with historical.bin). Dedup key: (device, hash64 of bytes).
 */
@Entity(
    tableName = "raw_packet",
    indices = [
        Index(value = ["deviceAddress", "hash64"], unique = true),
        Index(value = ["receivedAtUtcMs"]),
    ],
)
data class RawPacketEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceAddress: String,
    val receivedAtUtcMs: Long,
    val packetType: Int,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val bytes: ByteArray,
    val hash64: Long,
    val syncRunId: Long? = null,
)

object SyncOutcome {
    const val RUNNING = "RUNNING"
    const val COMPLETE = "COMPLETE"     // HISTORY_COMPLETE received
    const val PARTIAL = "PARTIAL"       // stopped early (timeouts, pass limit); resumable
    const val STOPPED = "STOPPED"       // user stopped it
    const val FAILED = "FAILED"
}

/** One synchronisation attempt (all passes), for sync history and interrupted-sync recovery. */
@Entity(tableName = "sync_run", indices = [Index(value = ["deviceAddress", "startedAtUtcMs"])])
data class SyncRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceAddress: String,
    val startedAtUtcMs: Long,
    val endedAtUtcMs: Long? = null,
    /** "manual", "scheduled" or "presence". */
    val trigger: String,
    /** True = batches were not ACKed (nothing freed on the band). */
    val readOnly: Boolean,
    val passes: Int = 0,
    val batches: Int = 0,
    val packets: Long = 0,
    val newSamples: Long = 0,
    val newEvents: Long = 0,
    val lastTrim: Long? = null,
    val hwRtc: Long? = null,
    val rtcDriftS: Long? = null,
    val rebootDetected: Boolean = false,
    val outcome: String = SyncOutcome.RUNNING,
    val error: String? = null,
)

/** A manual tracking session, detected workout, or (later) a Concept2 PM5 row. */
@Entity(tableName = "activity_session", indices = [Index(value = ["startUtcMs"])])
data class ActivitySessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceAddress: String?,
    /** e.g. "tracking", "workout", "rowing". */
    val type: String,
    /** "MANUAL", "AUTO" or "PM5". */
    val origin: String,
    val startUtcMs: Long,
    val endUtcMs: Long? = null,
    val name: String? = null,
    val notes: String? = null,
    val createdAtUtcMs: Long,
)

/** A detected sleep period. Always an estimate: produced by a named method with a confidence. */
@Entity(
    tableName = "sleep_session",
    indices = [Index(value = ["deviceAddress", "startUtcMs", "method"], unique = true)],
)
data class SleepSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceAddress: String,
    val startUtcMs: Long,
    val endUtcMs: Long,
    val method: String,
    val methodVersion: Int,
    val confidence: Double,
    val computedAtUtcMs: Long,
)

@Entity(
    tableName = "sleep_stage",
    foreignKeys = [ForeignKey(
        entity = SleepSessionEntity::class, parentColumns = ["id"], childColumns = ["sleepSessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["sleepSessionId", "startUtcMs"])],
)
data class SleepStageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sleepSessionId: Long,
    val startUtcMs: Long,
    val endUtcMs: Long,
    /** "WAKE", "ASLEEP", "LIGHT", "DEEP" or "REM". */
    val stage: String,
    val confidence: Double,
)

/** One local calendar day (in the zone the user was in), the frame for daily metrics. */
@Entity(tableName = "daily_summary", primaryKeys = ["deviceAddress", "localDate"])
data class DailySummaryEntity(
    val deviceAddress: String,
    /** ISO date, e.g. "2026-11-20". */
    val localDate: String,
    val zoneId: String,
    val dayStartUtcMs: Long,
    val dayEndUtcMs: Long,
    /** Fraction of the day with heart-rate data (0..1). */
    val coverage: Double,
    val computedAtUtcMs: Long,
)

object MetricKind {
    const val MEASURED = "MEASURED"
    const val ESTIMATED = "ESTIMATED"
    /** A fixed formula on measured data (e.g. RMSSD, lowest 5-minute HR). */
    const val DERIVED = "DERIVED"
}

/** A computed value (e.g. nightly RMSSD, resting HR, recovery). Missing data means no row, never a guessed value. */
@Entity(
    tableName = "metric_value",
    indices = [
        Index(value = ["deviceAddress", "key", "periodStartUtcMs", "method"], unique = true),
        Index(value = ["key", "periodStartUtcMs"]),
    ],
)
data class MetricValueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceAddress: String,
    val key: String,
    val periodStartUtcMs: Long,
    val periodEndUtcMs: Long,
    val value: Double,
    val kind: String,
    val method: String,
    val methodVersion: Int,
    /** 0..1; null for MEASURED values. */
    val confidence: Double?,
    val computedAtUtcMs: Long,
)
