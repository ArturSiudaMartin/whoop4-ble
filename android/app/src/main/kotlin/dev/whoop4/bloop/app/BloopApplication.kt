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
        // A crash would otherwise leave no trace in the shareable log (a sync on 2026-11-20 just stopped).
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { log.w("CRASH in ${t.name}: ${e.stackTraceToString().take(4000)}") }
            previous?.uncaughtException(t, e)
        }
        logLastExit()
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

    /** Why the previous process ended (killed by the system, crash, user swipe…), from Android 11 on. */
    private fun logLastExit() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = getSystemService(android.app.ActivityManager::class.java)
            am.getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull()?.let { x ->
                log.i("previous exit: reason=${exitReason(x.reason)} importance=${x.importance} at=${java.time.Instant.ofEpochMilli(x.timestamp)} ${x.description ?: ""}")
            }
        }
    }

    private fun exitReason(r: Int) = when (r) {
        android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
        android.app.ApplicationExitInfo.REASON_CRASH -> "CRASH"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        android.app.ApplicationExitInfo.REASON_OTHER -> "OTHER"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED (force stop / swipe away)"
        android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        android.app.ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        else -> "code $r"
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
