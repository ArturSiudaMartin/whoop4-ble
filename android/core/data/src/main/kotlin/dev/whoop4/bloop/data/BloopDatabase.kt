package dev.whoop4.bloop.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Phase 2 skeleton: one table, which proves the Room + KSP toolchain works end to end.
 * The full schema (samples, RR, events, raw packets, sync runs, sleep, summaries) arrives in Phase 7.
 * All timestamps are UTC epoch milliseconds.
 */
@Entity(tableName = "device")
data class DeviceEntity(
    @PrimaryKey val address: String,
    val name: String?,
    val lastSeenUtcMs: Long?,
)

@Dao
interface DeviceDao {
    @Query("SELECT * FROM device ORDER BY lastSeenUtcMs DESC")
    fun observeAll(): Flow<List<DeviceEntity>>

    @Upsert
    suspend fun upsert(device: DeviceEntity)
}

@Database(entities = [DeviceEntity::class], version = 1, exportSchema = true)
abstract class BloopDatabase : RoomDatabase() {
    abstract fun devices(): DeviceDao

    companion object {
        fun create(context: Context): BloopDatabase =
            Room.databaseBuilder(context, BloopDatabase::class.java, "bloop.db").build()
    }
}
