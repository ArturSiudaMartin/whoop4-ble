package dev.whoop4.bloop.app

import androidx.room.withTransaction
import dev.whoop4.bloop.analytics.HeartRateMax
import dev.whoop4.bloop.analytics.HrMax
import dev.whoop4.bloop.analytics.HrPoint
import dev.whoop4.bloop.analytics.Metric
import dev.whoop4.bloop.analytics.NightAnalyzer
import dev.whoop4.bloop.analytics.NightValues
import dev.whoop4.bloop.analytics.Recovery
import dev.whoop4.bloop.analytics.RrPoint
import dev.whoop4.bloop.analytics.SleepDetector
import dev.whoop4.bloop.analytics.SleepState
import dev.whoop4.bloop.analytics.Strain
import dev.whoop4.bloop.analytics.valueOrNull
import dev.whoop4.bloop.ble.DebugLog
import dev.whoop4.bloop.data.BloopDatabase
import dev.whoop4.bloop.data.DailySummaryEntity
import dev.whoop4.bloop.data.MetricKind
import dev.whoop4.bloop.data.MetricValueEntity
import dev.whoop4.bloop.data.SampleSource
import dev.whoop4.bloop.data.SleepSessionEntity
import dev.whoop4.bloop.data.SleepStageEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** metric_value keys. All values for a day share periodStart = local midnight of that day. */
object MetricKeys {
    const val SLEEP_START = "sleep_start_ms"          // epoch ms, the sleep period start
    const val SLEEP_END = "sleep_end_ms"
    const val SLEEP_ASLEEP_MS = "sleep_asleep_ms"
    const val SLEEP_AWAKE_MS = "sleep_awake_ms"
    const val SLEEP_PERFORMANCE = "sleep_performance" // 0..1
    const val HRV_RMSSD = "hrv_rmssd_ms"
    const val HRV_LN_RMSSD = "hrv_ln_rmssd"
    const val RHR = "resting_hr"
    const val RECOVERY = "recovery"                   // 1..99
    const val STRAIN = "strain"                       // Bloop 0..21
    const val TRIMP = "trimp"
    const val HR_P999 = "hr_p999"                     // the day's 99.9th percentile HR, for the observed-max estimate
    const val HR_AVG = "hr_avg"
    const val STRAIN_DATA_MIN = "strain_data_min"     // minutes of the day with heart rate
    const val STRAIN_LAST_MS = "strain_last_ms"       // newest sample used, epoch ms
    const val STRAIN_LIVE_MIN = "strain_live_min"     // of which filled in from live readings
    const val ZONE_REST = "zone_rest_bpm"
    const val ZONE_MAX = "zone_max_bpm"
    fun zoneMinutes(z: Int) = "zone${z}_min"
}

/**
 * Turns synced band data into daily metrics (Phase 8). For each local day D:
 *  - the **night** ending on D is looked for between 18:00 on D−1 and 14:00 on D, then sleep, HRV and
 *    resting HR are computed (core:analytics) and stored with their method and confidence;
 *  - **recovery** compares that night with the previous 28 days' stored values;
 *  - **strain** covers D from midnight to midnight (so far, for today).
 * Sleep uses only band-stored (HISTORICAL) samples (live readings have no motion data). Strain also uses live
 * readings where the band-stored data has a gap (e.g. a workout recorded live that hasn't been synced yet).
 * A metric that cannot be computed is deleted for that day, so the UI never shows a stale number.
 */
class AnalyticsRunner(private val db: BloopDatabase, private val settings: Settings, private val log: DebugLog) {
    private val lock = Mutex()
    private val _sleepReasons = kotlinx.coroutines.flow.MutableStateFlow<Map<LocalDate, String>>(emptyMap())
    /** Why no sleep was found for a day (from the last computation), for the Sleep screen. */
    val sleepReasons: kotlinx.coroutines.flow.StateFlow<Map<LocalDate, String>> = _sleepReasons
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Recomputes from the day containing [fromUtcMs] through today (at most [MAX_DAYS] days). */
    suspend fun recompute(device: String, fromUtcMs: Long) = lock.withLock {
        val today = LocalDate.now(zone)
        var day = Instant.ofEpochMilli(fromUtcMs).atZone(zone).toLocalDate()
        if (day.isBefore(today.minusDays(MAX_DAYS))) day = today.minusDays(MAX_DAYS)
        val started = System.currentTimeMillis()
        var n = 0
        while (!day.isAfter(today)) {
            runCatching { computeDay(device, day) }.onFailure { log.w("analytics $day failed: ${it.javaClass.simpleName}: ${it.message}") }
            day = day.plusDays(1); n++
        }
        log.i("analytics: $n day(s) computed in ${System.currentTimeMillis() - started} ms")
    }

    private suspend fun computeDay(device: String, day: LocalDate) {
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val nightFrom = day.minusDays(1).atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
        val nightTo = day.atTime(14, 0).atZone(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()

        val samples = db.samples().samplesBetween(device, SampleSource.HISTORICAL, nightFrom, dayEnd)
        val hr = samples.map { HrPoint(it.tsUtcMs, it.hr, it.accelX, it.accelY, it.accelZ) }
        val rr = db.samples().rrForRange(device, SampleSource.HISTORICAL, nightFrom, nightTo - 1).map { RrPoint(it.sampleTsUtcMs, it.rrMs) }

        _sleepReasons.value = _sleepReasons.value - day
        val values = mutableListOf<MetricValueEntity>()
        fun put(key: String, value: Double, kind: String, method: String, confidence: Double?) {
            values += MetricValueEntity(
                deviceAddress = device, key = key, periodStartUtcMs = dayStart, periodEndUtcMs = dayEnd, value = value,
                kind = kind, method = method, methodVersion = 1, confidence = confidence, computedAtUtcMs = now,
            )
        }
        fun putMetric(key: String, m: Metric<Double>) = when (m) {
            is Metric.Measured -> put(key, m.value, MetricKind.MEASURED, "band", null)
            is Metric.Derived -> put(key, m.value, MetricKind.DERIVED, m.method, m.confidence)
            is Metric.Estimated -> put(key, m.value, MetricKind.ESTIMATED, m.method, m.confidence)
            is Metric.Missing -> Unit
        }

        // ---- night
        val night = NightAnalyzer.analyze(hr, rr, nightFrom, nightTo, (settings.sleepNeedHours * 3_600_000).toLong())
        val sleep = night.sleep
        val nightSamples = hr.count { it.tsMs < nightTo }
        val fmt = { ms: Long -> Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().withSecond(0).withNano(0).toString() }
        log.i("analytics $day: $nightSamples samples 18:00–14:00" +
            (hr.lastOrNull()?.let { " (last ${fmt(it.tsMs)})" } ?: "") + ", sleep " + when (sleep) {
                is Metric.Estimated -> "${fmt(sleep.value.startMs)}–${fmt(sleep.value.endMs)}, asleep ${sleep.value.asleepMs / 60_000} min, coverage ${"%.0f".format(sleep.value.coverage * 100)}%"
                is Metric.Missing -> "none: ${sleep.reason}"
                else -> "?"
            } + ", HRV ${night.hrv.valueOrNull?.rmssdMs?.let { "%.0f ms".format(it) } ?: (night.hrv as? Metric.Missing)?.reason}" +
            ", RHR ${night.restingHr.valueOrNull?.bpm?.let { "%.0f".format(it) } ?: (night.restingHr as? Metric.Missing)?.reason}")
        if (sleep is Metric.Missing) _sleepReasons.value = _sleepReasons.value + (day to sleep.reason)
        if (sleep is Metric.Estimated) {
            val s = sleep.value
            put(MetricKeys.SLEEP_START, s.startMs.toDouble(), MetricKind.ESTIMATED, sleep.method, sleep.confidence)
            put(MetricKeys.SLEEP_END, s.endMs.toDouble(), MetricKind.ESTIMATED, sleep.method, sleep.confidence)
            put(MetricKeys.SLEEP_ASLEEP_MS, s.asleepMs.toDouble(), MetricKind.ESTIMATED, sleep.method, sleep.confidence)
            put(MetricKeys.SLEEP_AWAKE_MS, s.awakeMs.toDouble(), MetricKind.ESTIMATED, sleep.method, sleep.confidence)
        }
        putMetric(MetricKeys.SLEEP_PERFORMANCE, night.performance)
        when (val h = night.hrv) {
            is Metric.Derived -> {
                put(MetricKeys.HRV_RMSSD, h.value.rmssdMs, MetricKind.DERIVED, h.method, h.confidence)
                put(MetricKeys.HRV_LN_RMSSD, h.value.lnRmssd, MetricKind.DERIVED, h.method, h.confidence)
            }
            else -> Unit
        }
        (night.restingHr as? Metric.Derived)?.let { put(MetricKeys.RHR, it.value.bpm, MetricKind.DERIVED, it.method, it.confidence) }

        // ---- recovery, against the stored previous 28 nights
        val history = db.metrics().between(device, day.minusDays(Recovery.BASELINE_DAYS.toLong()).atStartOfDay(zone).toInstant().toEpochMilli(), dayStart)
        val byDay = history.groupBy { Instant.ofEpochMilli(it.periodStartUtcMs).atZone(zone).toLocalDate().toEpochDay() }
        val nights = byDay.map { (d, vs) ->
            fun v(k: String) = vs.firstOrNull { it.key == k }?.value
            NightValues(d, v(MetricKeys.HRV_LN_RMSSD), v(MetricKeys.RHR), v(MetricKeys.SLEEP_PERFORMANCE))
        }
        val tonight = NightValues(day.toEpochDay(), night.hrv.valueOrNull?.lnRmssd, night.restingHr.valueOrNull?.bpm, night.performance.valueOrNull)
        val recovery = Recovery.of(tonight, nights, (night.hrv as? Metric.Derived)?.confidence ?: 0.0)
        (recovery as? Metric.Estimated)?.let { put(MetricKeys.RECOVERY, it.value.score, MetricKind.ESTIMATED, it.method, it.confidence) }

        // ---- day: strain, zones, HR summary
        val storedDayHr = hr.filter { it.tsMs in dayStart until dayEnd }
        val liveFill = liveGapFill(device, storedDayHr, dayStart, dayEnd)
        val dayHr = if (liveFill.isEmpty()) storedDayHr else (storedDayHr + liveFill).sortedBy { it.tsMs }
        if (dayHr.isNotEmpty()) {
            val sorted = dayHr.map { it.hr.toDouble() }.sorted()
            put(MetricKeys.HR_AVG, sorted.average(), MetricKind.DERIVED, "mean", null)
            if (dayHr.size >= 600) put(MetricKeys.HR_P999, sorted[((sorted.size - 1) * 0.999).toInt()], MetricKind.DERIVED, "p99.9", null)
        }
        val restingForStrain = restingBaseline(nights, night.restingHr.valueOrNull?.bpm)
        val maxForStrain = maxHr(history, dayHr)
        val strain = Strain.of(dayHr, dayStart, minOf(dayEnd, now), restingForStrain, maxForStrain, settings.zoneBounds)
        if (strain is Metric.Estimated) {
            put(MetricKeys.STRAIN, strain.value.score, MetricKind.ESTIMATED, strain.method, strain.confidence)
            put(MetricKeys.TRIMP, strain.value.trimp, MetricKind.ESTIMATED, strain.method, strain.confidence)
            for (z in 1..5) put(MetricKeys.zoneMinutes(z), strain.value.zoneMinutes[z], MetricKind.DERIVED, if (settings.zoneBounds != null) "custom-bpm" else "pct-hrmax", null)
            put(MetricKeys.STRAIN_DATA_MIN, strain.value.minutesWithData, MetricKind.DERIVED, "coverage", null)
            put(MetricKeys.STRAIN_LAST_MS, strain.value.lastSampleMs.toDouble(), MetricKind.MEASURED, "band", null)
            put(MetricKeys.STRAIN_LIVE_MIN, liveFill.size / 60.0, MetricKind.DERIVED, "live-gap-fill", null)
        }
        // Stored whenever known (also with custom zones, or when strain itself can't be computed yet), so the Strain
        // screen always shows the values in use and where they came from.
        restingForStrain.valueOrNull?.let { put(MetricKeys.ZONE_REST, it, MetricKind.DERIVED, restingForStrain.methodOrEmpty(), null) }
        maxForStrain?.let { put(MetricKeys.ZONE_MAX, it.bpm, MetricKind.DERIVED, it.source, null) }
        log.i("analytics $day strain: ${storedDayHr.size} stored + ${liveFill.size} live samples, " + when (strain) {
            is Metric.Estimated -> "%.1f, zones %s min, data %.0f min".format(strain.value.score,
                (1..5).joinToString("/") { "%.0f".format(strain.value.zoneMinutes[it]) }, strain.value.minutesWithData)
            is Metric.Missing -> "none: ${strain.reason}"
            else -> "?"
        })

        val coverage = dayHr.size / ((minOf(dayEnd, now) - dayStart).coerceAtLeast(1) / 1000.0)
        db.withTransaction {
            db.sleep().deleteOverlapping(device, SleepDetector.METHOD, nightFrom, nightTo)
            if (sleep is Metric.Estimated) {
                val s = sleep.value
                val id = db.sleep().upsertSession(SleepSessionEntity(
                    deviceAddress = device, startUtcMs = s.startMs, endUtcMs = s.endMs, method = sleep.method,
                    methodVersion = 1, confidence = sleep.confidence, computedAtUtcMs = now,
                ))
                db.sleep().insertStages(s.segments.map {
                    SleepStageEntity(sleepSessionId = id, startUtcMs = it.startMs, endUtcMs = it.endMs,
                        stage = if (it.state == SleepState.ASLEEP) "ASLEEP" else "WAKE", confidence = sleep.confidence)
                })
            }
            db.metrics().deleteOthers(device, dayStart, values.map { it.key }.ifEmpty { listOf("-") })
            if (values.isNotEmpty()) db.metrics().upsert(values)
            db.metrics().upsertDaily(DailySummaryEntity(device, day.toString(), zone.id, dayStart, dayEnd, coverage.coerceIn(0.0, 1.0), now))
        }
    }

    /**
     * Live readings (Heart Rate service, ~1/s) for the seconds the band-stored data doesn't cover, so a workout
     * done with "Go live" counts before the next sync brings the band's own copy.
     */
    private suspend fun liveGapFill(device: String, stored: List<HrPoint>, from: Long, to: Long): List<HrPoint> {
        val live = db.samples().samplesBetween(device, SampleSource.LIVE, from, to).filter { it.hr > 0 }
        if (live.isEmpty()) return emptyList()
        val ts = stored.map { it.tsMs }.toLongArray().also { it.sort() }
        fun covered(t: Long): Boolean {
            var i = java.util.Arrays.binarySearch(ts, t)
            if (i >= 0) return true
            i = -i - 1
            return (i < ts.size && ts[i] - t <= LIVE_MATCH_MS) || (i > 0 && t - ts[i - 1] <= LIVE_MATCH_MS)
        }
        val out = ArrayList<HrPoint>()
        var lastKept = Long.MIN_VALUE / 2
        for (s in live) {   // ordered by time; skip duplicates under a second apart
            if (covered(s.tsUtcMs) || s.tsUtcMs - lastKept < 900) continue
            out += HrPoint(s.tsUtcMs, s.hr); lastKept = s.tsUtcMs
        }
        return out
    }

    /** Heart rate for a time range (e.g. an activity session): band-stored samples, with live readings filling their gaps. */
    suspend fun heartRate(device: String, from: Long, to: Long): List<HrPoint> {
        val stored = db.samples().samplesBetween(device, SampleSource.HISTORICAL, from, to).filter { it.hr > 0 }.map { HrPoint(it.tsUtcMs, it.hr) }
        val fill = liveGapFill(device, stored, from, to)
        return if (fill.isEmpty()) stored else (stored + fill).sortedBy { it.tsMs }
    }

    private fun Metric<Double>.methodOrEmpty() = when (this) { is Metric.Derived -> method; is Metric.Estimated -> method; else -> "" }

    /** Resting HR for zones: median of the last 7 nights' values (including tonight's), so one odd night doesn't move the zones. */
    private fun restingBaseline(nights: List<NightValues>, tonight: Double?): Metric<Double> {
        settings.restingHr?.let { return Metric.Derived(it.toDouble(), "set in Profile", 1.0) }
        val recent = (nights.sortedByDescending { it.dayIndex }.mapNotNull { it.restingHr }.take(6) + listOfNotNull(tonight)).sorted()
        if (recent.isEmpty()) return Metric.Missing("no resting heart rate yet")
        return Metric.Derived(recent[recent.size / 2], "median-7-nights", minOf(1.0, recent.size / 3.0))
    }

    private fun maxHr(history: List<MetricValueEntity>, dayHr: List<HrPoint>): HrMax? {
        settings.maxHr?.let { return HrMax(it.toDouble(), "set in Profile", 1.0) }
        settings.age?.let { return HeartRateMax.tanaka(it) }
        // No age: the highest daily 99.9th percentile over the stored days (needs 3 days). It underestimates a true
        // maximum (so strain reads high), hence the lower confidence; setting an age is better.
        val p999 = history.filter { it.key == MetricKeys.HR_P999 }.map { it.value }
        if (p999.size < 3) return null
        val todayP = if (dayHr.size >= 600) dayHr.map { it.hr.toDouble() }.sorted().let { it[((it.size - 1) * 0.999).toInt()] } else 0.0
        return HrMax(maxOf(p999.max(), todayP), "highest observed", 0.5)
    }

    private companion object {
        const val MAX_DAYS = 60L
        const val LIVE_MATCH_MS = 2_500L
    }
}
