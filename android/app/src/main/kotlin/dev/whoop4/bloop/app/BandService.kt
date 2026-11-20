package dev.whoop4.bloop.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.whoop4.bloop.MainActivity
import dev.whoop4.bloop.R
import dev.whoop4.bloop.ble.BandStatus
import dev.whoop4.bloop.ble.ConnectionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * Connected-device foreground service: keeps the process alive while Bloop is connected, live or syncing,
 * so a sync or live session continues with the screen off or another app open. It stops itself as soon as
 * nothing is active. (Scheduled background syncs arrive in Phase 10.)
 */
class BandService : Service() {
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Band connection", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = BloopApplication.from(this)
        val n = build(app.band.status.value)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) else startForeground(ID, n)
        if (watcher == null) watcher = app.scope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            app.band.status.debounce(1_000).collect { s ->
                if (!s.active) { app.log.i("service: idle, stopping"); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return@collect }
                getSystemService(NotificationManager::class.java).notify(ID, build(s))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { watcher?.cancel(); super.onDestroy() }

    private fun build(s: BandStatus): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = when {
            s.syncing -> "Syncing band data"
            s.liveRequested -> "Live" + (s.lastHr?.let { " · $it bpm" } ?: "")
            s.connection == ConnectionState.CONNECTING -> "Connecting to band…"
            else -> "Connected to band"
        }
        val text = s.sync?.takeIf { s.syncing }?.let { "Pass ${it.pass} · ${it.batches} batches · ${it.newSamples} new samples" }
            ?: s.liveNote ?: s.name ?: "Bloop"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bloop).setContentTitle(title).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .build()
    }

    companion object {
        private const val CHANNEL = "band"
        private const val ID = 1

        /** Call from a user action (foreground), before starting a connection, live session or sync. */
        fun ensureRunning(c: Context) {
            runCatching { c.startForegroundService(Intent(c, BandService::class.java)) }
                .onFailure { BloopApplication.from(c).log.w("could not start foreground service: ${it.message}") }
        }
    }
}
