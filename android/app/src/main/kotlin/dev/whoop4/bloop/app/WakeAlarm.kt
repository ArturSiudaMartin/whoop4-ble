package dev.whoop4.bloop.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Daily wake alarm that only vibrates the band: Bloop schedules an exact, Doze-proof wake-up for itself
 * (setExactAndAllowWhileIdle, so no alarm icon and no phone sound); when it fires, Bloop connects to the band
 * and sends RUN_ALARM. The phone never rings: if the band can't be reached within [REACH_WINDOW_MS], the
 * attempt is only written to the debug log.
 *
 * The band's own on-strap alarm (SET_ALARM_TIME) is not used: its payload format isn't documented.
 */
object WakeAlarm {
    const val ACTION_FIRE = "dev.whoop4.bloop.WAKE_ALARM"
    const val REACH_WINDOW_MS = 3 * 60_000L

    /** Next occurrence of [time] after now, in the phone's zone. */
    fun nextTrigger(time: LocalTime, now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())): ZonedDateTime {
        val today = ZonedDateTime.of(LocalDate.from(now), time, now.zone)
        return if (today.isAfter(now.plusSeconds(5))) today else today.plusDays(1)
    }

    /** (Re)schedules or cancels according to Settings. */
    fun reschedule(c: Context) {
        val app = BloopApplication.from(c)
        val am = c.getSystemService(AlarmManager::class.java)
        val fire = PendingIntent.getBroadcast(c, 0, Intent(c, WakeAlarmReceiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val minutes = app.settings.alarmMinutes
        if (!app.settings.alarmEnabled || minutes == null) { am.cancel(fire); app.log.i("wake alarm: off"); return }
        val at = nextTrigger(LocalTime.of(minutes / 60, minutes % 60))
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toInstant().toEpochMilli(), fire)
            app.log.i("wake alarm: band buzz set for $at")
        } catch (e: SecurityException) {
            app.log.w("wake alarm: exact alarms not allowed: ${e.message}")
        }
    }

    /** Called when the alarm fires: buzz the band (retrying for a few minutes), then schedule tomorrow. */
    fun fire(c: Context) {
        val app = BloopApplication.from(c)
        app.log.i("wake alarm: fired")
        BandService.ensureRunning(c)
        app.scope.launch {
            val ok = app.band.buzz("wake alarm", System.currentTimeMillis() + REACH_WINDOW_MS)
            if (!ok) app.log.w("wake alarm: band not reachable within ${REACH_WINDOW_MS / 60_000} min, no buzz")
            reschedule(c)
        }
    }
}

/** Receives the alarm and boot/time-change broadcasts (alarms are cleared on reboot). */
class WakeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        when (intent.action) {
            WakeAlarm.ACTION_FIRE -> WakeAlarm.fire(c)
            else -> WakeAlarm.reschedule(c)   // BOOT_COMPLETED, TIME_SET, TIMEZONE_CHANGED, exact-alarm permission granted
        }
    }
}
