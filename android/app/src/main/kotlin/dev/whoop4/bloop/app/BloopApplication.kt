package dev.whoop4.bloop.app

import android.app.Application
import android.content.Context
import dev.whoop4.bloop.ble.BandManager
import dev.whoop4.bloop.ble.DebugLog
import dev.whoop4.bloop.data.BloopDatabase
import dev.whoop4.bloop.data.DeviceEntity
import dev.whoop4.bloop.data.IngestRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** Manual dependency container (plan §3: no Hilt). One instance per process. */
class BloopApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var db: BloopDatabase; private set
    lateinit var ingest: IngestRepository; private set
    lateinit var log: DebugLog; private set
    lateinit var band: BandManager; private set
    lateinit var settings: Settings; private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        db = BloopDatabase.create(this)
        ingest = IngestRepository(db)
        log = DebugLog(File(filesDir, "logs/debug.log"))
        log.i("Bloop ${packageManager.getPackageInfo(packageName, 0).versionName} started")
        band = BandManager(this, log, scope) { address -> RoomSyncStore(db, ingest, address) }

        scope.launch { db.syncRuns().markInterrupted(System.currentTimeMillis()).takeIf { it > 0 }?.let { log.w("$it interrupted sync run(s) marked PARTIAL") } }
        // Live measurements are stored as they arrive.
        scope.launch {
            band.heartRate.collect { m ->
                val a = band.status.value.address ?: return@collect
                runCatching { ingest.ingestLive(a, m, System.currentTimeMillis()) }.onFailure { log.w("storing live HR failed: ${it.message}") }
            }
        }
        settings.bandAddress?.let { band.selectBand(it, settings.bandName) }
    }

    fun chooseBand(address: String, name: String?) {
        settings.bandAddress = address; settings.bandName = name
        band.selectBand(address, name)
        scope.launch {
            val now = System.currentTimeMillis()
            val d = db.devices().get(address)
            db.devices().upsert(d?.copy(name = name ?: d.name, lastSeenUtcMs = now) ?: DeviceEntity(address, name, now, firstSeenUtcMs = now))
        }
    }

    companion object {
        fun from(c: Context) = c.applicationContext as BloopApplication
    }
}

class Settings(c: Context) {
    private val p = c.getSharedPreferences("bloop", Context.MODE_PRIVATE)
    var bandAddress: String?
        get() = p.getString("bandAddress", null)
        set(v) = p.edit().putString("bandAddress", v).apply()
    var bandName: String?
        get() = p.getString("bandName", null)
        set(v) = p.edit().putString("bandName", v).apply()
    /** Read-only sync: download without ACKing, so nothing is freed on the band. Default off (plan D3). */
    var readOnlySync: Boolean
        get() = p.getBoolean("readOnlySync", false)
        set(v) = p.edit().putBoolean("readOnlySync", v).apply()
}
