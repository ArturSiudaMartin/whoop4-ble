package dev.whoop4.bloop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class WakeAlarmTest {
    private val zone = ZoneId.of("Europe/London")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) = ZonedDateTime.of(y, m, d, h, min, 0, 0, zone)
    private val seven = LocalTime.of(7, 0)

    @Test fun weekdaysOnlySkipsTheWeekend() {
        val days = listOf(seven, seven, seven, seven, seven, null, null)
        // Friday 2026-11-27 08:00, after Friday's alarm: next is Monday 2026-11-30 07:00
        assertEquals(at(2026, 11, 30, 7, 0), WakeAlarm.nextTrigger(days, at(2026, 11, 27, 8, 0)))
        // Friday 06:00: today
        assertEquals(at(2026, 11, 27, 7, 0), WakeAlarm.nextTrigger(days, at(2026, 11, 27, 6, 0)))
    }

    @Test fun eachDayHasItsOwnTime() {
        val days = listOf(seven, null, null, null, null, LocalTime.of(9, 30), null)
        // Sunday 2026-11-22 10:00: next is Monday 07:00
        assertEquals(at(2026, 11, 23, 7, 0), WakeAlarm.nextTrigger(days, at(2026, 11, 22, 10, 0)))
        // Monday 08:00: next is Saturday 09:30
        assertEquals(at(2026, 11, 28, 9, 30), WakeAlarm.nextTrigger(days, at(2026, 11, 23, 8, 0)))
    }

    @Test fun onlyDayAlreadyPassedRepeatsNextWeek() {
        val days = listOf(seven, null, null, null, null, null, null)
        assertEquals(at(2026, 11, 30, 7, 0), WakeAlarm.nextTrigger(days, at(2026, 11, 23, 7, 30)))
    }

    @Test fun noDaysMeansNoAlarm() = assertNull(WakeAlarm.nextTrigger(List(7) { null }, at(2026, 11, 23, 7, 30)))
}
