package dev.whoop4.bloop.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Query("SELECT * FROM device ORDER BY lastSeenUtcMs DESC")
    fun observeAll(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM device WHERE address = :address")
    suspend fun get(address: String): DeviceEntity?

    @Upsert
    suspend fun upsert(device: DeviceEntity)
}

/** Count of rows actually inserted by an IGNORE insert (Room returns -1 for skipped duplicates). */
internal fun List<Long>.inserted(): Int = count { it != -1L }

@Dao
interface SampleDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSamples(rows: List<HrSampleEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRr(rows: List<RrIntervalEntity>): List<Long>

    @Query("SELECT COUNT(*) FROM hr_sample WHERE deviceAddress = :device AND source = :source")
    suspend fun count(device: String, source: Int): Long

    @Query("SELECT MAX(tsUtcMs) FROM hr_sample WHERE deviceAddress = :device AND source = :source")
    fun observeLatest(device: String, source: Int): Flow<Long?>

    @Query(
        """SELECT * FROM hr_sample WHERE deviceAddress = :device AND tsUtcMs >= :fromUtcMs AND tsUtcMs < :toUtcMs
           ORDER BY tsUtcMs, subKey""",
    )
    suspend fun samplesBetween(device: String, fromUtcMs: Long, toUtcMs: Long): List<HrSampleEntity>

    @Query(
        """SELECT * FROM hr_sample WHERE deviceAddress = :device AND source = :source AND tsUtcMs >= :fromUtcMs AND tsUtcMs < :toUtcMs
           ORDER BY tsUtcMs, subKey""",
    )
    suspend fun samplesBetween(device: String, source: Int, fromUtcMs: Long, toUtcMs: Long): List<HrSampleEntity>

    /** Samples per UTC hour, to show how much of a night has been downloaded. */
    @Query("SELECT tsUtcMs / 3600000 AS hour, COUNT(*) AS n FROM hr_sample WHERE deviceAddress = :device AND source = :source AND tsUtcMs >= :fromUtcMs AND tsUtcMs < :toUtcMs GROUP BY hour")
    suspend fun hourlyCounts(device: String, source: Int, fromUtcMs: Long, toUtcMs: Long): List<HourCount>

    @Query("SELECT MIN(tsUtcMs) FROM hr_sample WHERE syncRunId = :runId")
    suspend fun earliestInRun(runId: Long): Long?

    @Query("SELECT MIN(tsUtcMs) FROM hr_sample WHERE deviceAddress = :device AND source = :source")
    suspend fun earliest(device: String, source: Int): Long?

    @Query(
        """SELECT * FROM rr_interval WHERE deviceAddress = :device AND sampleTsUtcMs >= :fromUtcMs AND sampleTsUtcMs < :toUtcMs
           ORDER BY sampleTsUtcMs, subKey, idx""",
    )
    suspend fun rrBetween(device: String, fromUtcMs: Long, toUtcMs: Long): List<RrIntervalEntity>

    /** Keyset paging for export: rows after (afterTs, afterSub) in time order. */
    @Query(
        """SELECT * FROM hr_sample WHERE deviceAddress = :device AND source = :source
             AND (tsUtcMs > :afterTs OR (tsUtcMs = :afterTs AND subKey > :afterSub))
           ORDER BY tsUtcMs, subKey LIMIT :limit""",
    )
    suspend fun page(device: String, source: Int, afterTs: Long, afterSub: Int, limit: Int): List<HrSampleEntity>

    @Query(
        """SELECT * FROM rr_interval WHERE deviceAddress = :device AND source = :source
             AND sampleTsUtcMs >= :fromTs AND sampleTsUtcMs <= :toTs ORDER BY sampleTsUtcMs, subKey, idx""",
    )
    suspend fun rrForRange(device: String, source: Int, fromTs: Long, toTs: Long): List<RrIntervalEntity>
}

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(rows: List<BandEventEntity>): List<Long>

    @Query("SELECT * FROM band_event WHERE deviceAddress = :device ORDER BY receivedAtUtcMs DESC LIMIT :limit")
    fun observeRecent(device: String, limit: Int): Flow<List<BandEventEntity>>

    @Query("SELECT * FROM band_event WHERE deviceAddress = :device ORDER BY id")
    suspend fun all(device: String): List<BandEventEntity>
}

@Dao
interface RawPacketDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(rows: List<RawPacketEntity>): List<Long>

    @Query("SELECT COUNT(*) FROM raw_packet WHERE deviceAddress = :device")
    suspend fun count(device: String): Long

    /** Retention: drop raw frames older than the cutoff (decoded rows are kept). */
    @Query("DELETE FROM raw_packet WHERE receivedAtUtcMs < :cutoffUtcMs")
    suspend fun deleteOlderThan(cutoffUtcMs: Long): Int
}

@Dao
interface SyncRunDao {
    @Insert
    suspend fun insert(run: SyncRunEntity): Long

    @Update
    suspend fun update(run: SyncRunEntity)

    @Query("SELECT * FROM sync_run WHERE id = :id")
    suspend fun get(id: Long): SyncRunEntity?

    @Query("SELECT * FROM sync_run WHERE deviceAddress = :device ORDER BY startedAtUtcMs DESC LIMIT :limit")
    fun observeRecent(device: String, limit: Int): Flow<List<SyncRunEntity>>

    @Query("SELECT * FROM sync_run WHERE deviceAddress = :device AND outcome = 'COMPLETE' ORDER BY endedAtUtcMs DESC LIMIT 1")
    fun observeLastComplete(device: String): Flow<SyncRunEntity?>

    /** Runs left RUNNING by a crash or kill; marked PARTIAL on the next start. */
    @Query("UPDATE sync_run SET outcome = 'PARTIAL', endedAtUtcMs = :nowUtcMs, error = 'interrupted' WHERE outcome = 'RUNNING'")
    suspend fun markInterrupted(nowUtcMs: Long): Int
}

@Dao
interface ActivityDao {
    @Insert
    suspend fun insert(session: ActivitySessionEntity): Long

    @Update
    suspend fun update(session: ActivitySessionEntity)

    @Query("SELECT * FROM activity_session ORDER BY startUtcMs DESC")
    fun observeAll(): Flow<List<ActivitySessionEntity>>

    @Query("SELECT * FROM activity_session WHERE id = :id")
    fun observe(id: Long): Flow<ActivitySessionEntity?>

    @Query("SELECT * FROM activity_session WHERE id = :id")
    suspend fun get(id: Long): ActivitySessionEntity?

    /** The session being recorded now (no end time yet), if any. */
    @Query("SELECT * FROM activity_session WHERE endUtcMs IS NULL ORDER BY startUtcMs DESC LIMIT 1")
    fun observeRunning(): Flow<ActivitySessionEntity?>

    @Query("DELETE FROM activity_session WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SleepDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: SleepSessionEntity): Long

    @Insert
    suspend fun insertStages(stages: List<SleepStageEntity>)

    /** Removes earlier results of [method] for a night window before it is recomputed (stages cascade). */
    @Query("DELETE FROM sleep_session WHERE deviceAddress = :device AND method = :method AND endUtcMs > :fromUtcMs AND startUtcMs < :toUtcMs")
    suspend fun deleteOverlapping(device: String, method: String, fromUtcMs: Long, toUtcMs: Long): Int

    @Query("SELECT * FROM sleep_session WHERE deviceAddress = :device ORDER BY startUtcMs DESC LIMIT :limit")
    fun observeRecent(device: String, limit: Int): Flow<List<SleepSessionEntity>>

    @Query("SELECT * FROM sleep_stage WHERE sleepSessionId = :sessionId ORDER BY startUtcMs")
    suspend fun stages(sessionId: Long): List<SleepStageEntity>
}

@Dao
interface MetricDao {
    @Upsert
    suspend fun upsertDaily(summary: DailySummaryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(values: List<MetricValueEntity>)

    @Query("SELECT * FROM metric_value WHERE deviceAddress = :device AND key = :key ORDER BY periodStartUtcMs DESC LIMIT :limit")
    fun observe(device: String, key: String, limit: Int): Flow<List<MetricValueEntity>>

    @Query("SELECT * FROM metric_value WHERE deviceAddress = :device AND periodStartUtcMs >= :fromUtcMs AND periodStartUtcMs < :toUtcMs ORDER BY periodStartUtcMs")
    suspend fun between(device: String, fromUtcMs: Long, toUtcMs: Long): List<MetricValueEntity>

    @Query("SELECT * FROM metric_value WHERE deviceAddress = :device AND periodStartUtcMs >= :fromUtcMs ORDER BY periodStartUtcMs")
    fun observeSince(device: String, fromUtcMs: Long): Flow<List<MetricValueEntity>>

    /** A recomputation that now yields "no data" must remove the old value, never leave a stale number. */
    @Query("DELETE FROM metric_value WHERE deviceAddress = :device AND periodStartUtcMs = :periodStartUtcMs AND key NOT IN (:keep)")
    suspend fun deleteOthers(device: String, periodStartUtcMs: Long, keep: List<String>): Int

    @Query("SELECT * FROM daily_summary WHERE deviceAddress = :device ORDER BY localDate DESC LIMIT :limit")
    fun observeDaily(device: String, limit: Int): Flow<List<DailySummaryEntity>>
}

data class HourCount(val hour: Long, val n: Int)
