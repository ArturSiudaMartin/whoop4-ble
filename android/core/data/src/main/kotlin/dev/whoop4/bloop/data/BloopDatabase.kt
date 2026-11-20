package dev.whoop4.bloop.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Schema history (exported to core/data/schemas/, committed):
 *  1 — Phase 2 preview: `device` only.
 *  2 — Phase 7: samples, RR, events, raw packets, sync runs, activities, sleep, daily summaries, metrics.
 * Every version bump needs a migration; destructive fallback is never enabled, so user data is never wiped.
 */
@Database(
    entities = [
        DeviceEntity::class, HrSampleEntity::class, RrIntervalEntity::class, BandEventEntity::class,
        RawPacketEntity::class, SyncRunEntity::class, ActivitySessionEntity::class, SleepSessionEntity::class,
        SleepStageEntity::class, DailySummaryEntity::class, MetricValueEntity::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class BloopDatabase : RoomDatabase() {
    abstract fun devices(): DeviceDao
    abstract fun samples(): SampleDao
    abstract fun events(): EventDao
    abstract fun rawPackets(): RawPacketDao
    abstract fun syncRuns(): SyncRunDao
    abstract fun activities(): ActivityDao
    abstract fun sleep(): SleepDao
    abstract fun metrics(): MetricDao

    companion object {
        const val NAME = "bloop.db"

        fun create(context: Context): BloopDatabase =
            Room.databaseBuilder(context, BloopDatabase::class.java, NAME).build()
    }
}
