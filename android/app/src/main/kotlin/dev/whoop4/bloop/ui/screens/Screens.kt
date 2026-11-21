package dev.whoop4.bloop.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxHeight
import dev.whoop4.bloop.app.BandService
import dev.whoop4.bloop.app.WakeAlarm
import dev.whoop4.bloop.data.SampleSource
import kotlinx.coroutines.launch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.whoop4.bloop.analytics.Recovery as RecoveryModel
import dev.whoop4.bloop.app.BloopApplication
import dev.whoop4.bloop.app.MetricKeys
import dev.whoop4.bloop.data.MetricKind
import dev.whoop4.bloop.data.MetricValueEntity
import dev.whoop4.bloop.ui.theme.BloopColors
import kotlinx.coroutines.flow.flowOf
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/*
 * Phase 8/9 dashboards. Every number comes from metric_value rows written by AnalyticsRunner; a metric
 * without a row shows "—" and the reason. Computed values carry a small "estimate" or "derived" tag.
 */

/** All stored metrics for the last [days] days, grouped by local date (newest first). */
@Composable
private fun rememberDays(days: Long = 30): List<Pair<LocalDate, Map<String, MetricValueEntity>>> {
    val app = BloopApplication.from(LocalContext.current)
    val address = app.band.status.collectAsState().value.address
    val zone = ZoneId.systemDefault()
    val since = remember(days) { LocalDate.now(zone).minusDays(days).atStartOfDay(zone).toInstant().toEpochMilli() }
    val flow = remember(address) { address?.let { app.db.metrics().observeSince(it, since) } ?: flowOf(emptyList()) }
    val rows by flow.collectAsState(initial = emptyList())
    return rows.groupBy { Instant.ofEpochMilli(it.periodStartUtcMs).atZone(zone).toLocalDate() }
        .mapValues { (_, v) -> v.associateBy { it.key } }
        .toList().sortedByDescending { it.first }
}

private fun nightsWithBaseline(days: List<Pair<LocalDate, Map<String, MetricValueEntity>>>) =
    days.count { it.second.containsKey(MetricKeys.HRV_LN_RMSSD) && it.second.containsKey(MetricKeys.RHR) }

@Composable
fun HomeScreen() {
    val days = rememberDays()
    val today = days.firstOrNull()?.takeIf { it.first == LocalDate.now() }?.second.orEmpty()
    Page {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            RingTile("Recovery", BloopColors.Recovery, today[MetricKeys.RECOVERY]?.let { it.value / 100 }, today[MetricKeys.RECOVERY]?.let { "%.0f%%".format(it.value) }, Modifier.weight(1f))
            RingTile("Strain", BloopColors.Caution, today[MetricKeys.STRAIN]?.let { it.value / 21 }, today[MetricKeys.STRAIN]?.let { "%.1f".format(it.value) }, Modifier.weight(1f))
            RingTile("Sleep", BloopColors.Sleep, today[MetricKeys.SLEEP_PERFORMANCE]?.value, today[MetricKeys.SLEEP_PERFORMANCE]?.let { "%.0f%%".format(it.value * 100) }, Modifier.weight(1f))
        }
        if (days.isEmpty()) {
            InfoCard("No data yet", "Sync your band on the Device screen (sliders icon, top right). Sleep, HRV and resting heart rate appear after a synced night; recovery after ${RecoveryModel.MIN_NIGHTS} nights.")
        } else {
            CardBox(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Last night", style = MaterialTheme.typography.titleMedium)
                    MetricRow("Asleep", today[MetricKeys.SLEEP_ASLEEP_MS]) { fmtDuration(it) }
                    MetricRow("HRV (RMSSD)", today[MetricKeys.HRV_RMSSD]) { "%.0f ms".format(it) }
                    MetricRow("Resting heart rate", today[MetricKeys.RHR]) { "%.0f bpm".format(it) }
                    MetricRow("Average heart rate today", today[MetricKeys.HR_AVG]) { "%.0f bpm".format(it) }
                }
            }
            if (today[MetricKeys.RECOVERY] == null) CalibratingNote(nightsWithBaseline(days))
        }
        InfoCard("About these numbers", "Measured: heart rate and RR from the band. Derived: fixed formulas on that data (HRV, resting HR). " +
            "Estimate: inferred by Bloop's models (sleep, strain, recovery). Strain uses Bloop's own 0–21 scale.")
    }
}

@Composable
fun RecoveryScreen() {
    val days = rememberDays()
    val today = days.firstOrNull()?.takeIf { it.first == LocalDate.now() }?.second.orEmpty()
    val baseline = days.drop(1).take(RecoveryModel.BASELINE_DAYS)
    Page {
        BigRing("Recovery", BloopColors.Recovery, today[MetricKeys.RECOVERY]?.let { it.value / 100 }, today[MetricKeys.RECOVERY]?.let { "%.0f%%".format(it.value) })
        if (today[MetricKeys.RECOVERY] == null) CalibratingNote(nightsWithBaseline(days))
        CardBox(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MetricRow("HRV (RMSSD)", today[MetricKeys.HRV_RMSSD]) { "%.0f ms".format(it) }
                Baseline("28-day average", baseline.mapNotNull { it.second[MetricKeys.HRV_RMSSD]?.value }) { "%.0f ms".format(it) }
                MetricRow("Resting heart rate", today[MetricKeys.RHR]) { "%.0f bpm".format(it) }
                Baseline("28-day average", baseline.mapNotNull { it.second[MetricKeys.RHR]?.value }) { "%.0f bpm".format(it) }
                MetricRow("Sleep performance", today[MetricKeys.SLEEP_PERFORMANCE]) { "%.0f%%".format(it * 100) }
            }
        }
        History("Last 14 days", days.take(14), MetricKeys.RECOVERY, BloopColors.Recovery, 100.0) { "%.0f%%".format(it) }
        History("HRV", days.take(14), MetricKeys.HRV_RMSSD, BloopColors.Recovery, null) { "%.0f ms".format(it) }
        InfoCard("How recovery works", "Last night's HRV (ln RMSSD) and resting heart rate as z-scores against your previous 28 days, plus sleep performance, " +
            "mapped to 1–99 %. 50 % is a typical night for you. Shown once ${RecoveryModel.MIN_NIGHTS} nights have HRV and resting HR.")
    }
}

@Composable
fun SleepScreen() {
    val days = rememberDays()
    val today = days.firstOrNull()?.takeIf { it.first == LocalDate.now() }?.second.orEmpty()
    Page {
        BigRing("Sleep performance", BloopColors.Sleep, today[MetricKeys.SLEEP_PERFORMANCE]?.value, today[MetricKeys.SLEEP_PERFORMANCE]?.let { "%.0f%%".format(it.value * 100) })
        LastNightData(today[MetricKeys.SLEEP_END]?.value?.toLong())
        CardBox(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MetricRow("Asleep", today[MetricKeys.SLEEP_ASLEEP_MS]) { fmtDuration(it) }
                MetricRow("Awake during the night", today[MetricKeys.SLEEP_AWAKE_MS]) { fmtDuration(it) }
                MetricRow("Fell asleep", today[MetricKeys.SLEEP_START]) { fmtClock(it) }
                MetricRow("Woke up", today[MetricKeys.SLEEP_END]) { fmtClock(it) }
                val asleep = today[MetricKeys.SLEEP_ASLEEP_MS]?.value; val awake = today[MetricKeys.SLEEP_AWAKE_MS]?.value
                Row2("Efficiency", if (asleep != null && awake != null && asleep + awake > 0) "%.0f%%".format(100 * asleep / (asleep + awake)) else "—")
            }
        }
        WakeAlarmCard()
        History("Time asleep, last 14 nights", days.take(14), MetricKeys.SLEEP_ASLEEP_MS, BloopColors.Sleep, 10 * 3_600_000.0) { fmtDuration(it) }
        InfoCard("How sleep is detected", "From wrist motion (arm-angle method of van Hees et al.): the longest quiet period between 18:00 and 14:00, " +
            "with asleep vs awake from 5-minute stillness. Times with the band off your wrist are never counted as sleep. Sleep stages are not estimated: " +
            "a wrist band can't tell them apart reliably.")
    }
}

@Composable
fun StrainScreen() {
    val app = BloopApplication.from(LocalContext.current)
    val days = rememberDays()
    val today = days.firstOrNull()?.takeIf { it.first == LocalDate.now() }?.second.orEmpty()
    val maxBpm = today[MetricKeys.ZONE_MAX]?.value
    val restBpm = today[MetricKeys.ZONE_REST]?.value
    Page {
        BigRing("Strain", BloopColors.Caution, today[MetricKeys.STRAIN]?.let { it.value / 21 }, today[MetricKeys.STRAIN]?.let { "%.1f".format(it.value) })
        if (today[MetricKeys.STRAIN] == null) Text(
            "Strain needs a resting heart rate (one synced night) and either your age (Device screen → Profile) or 3 days of data.",
            color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium,
        )
        CardBox(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Time in heart-rate zones today", style = MaterialTheme.typography.titleMedium)
                val zoneColors = listOf(BloopColors.Muted, BloopColors.Sleep, BloopColors.Recovery, BloopColors.Caution, BloopColors.Strain)
                val mins = (1..5).map { today[MetricKeys.zoneMinutes(it)]?.value }
                val top = mins.filterNotNull().maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
                val custom = app.settings.zoneBounds
                for (z in 1..5) {
                    if (custom != null) {
                        ZoneBar("Zone $z", if (z < 5) "${custom[z - 1]}–${custom[z] - 1} bpm" else "${custom[4]}+ bpm", mins[z - 1], top, zoneColors[z - 1])
                        continue
                    }
                    val lo = maxBpm?.let { "%.0f".format(it * dev.whoop4.bloop.analytics.Zones.lowerFraction(z)) }
                    val hi = maxBpm?.let { if (z == 5) "max" else "%.0f".format(it * dev.whoop4.bloop.analytics.Zones.lowerFraction(z + 1) - 1) }
                    ZoneBar("Zone $z", if (lo != null) "$lo–$hi bpm" else "${40 + 10 * z}–${50 + 10 * z} % max", mins[z - 1], top, zoneColors[z - 1])
                }
                val inZones = mins.filterNotNull().sum()
                if (today[MetricKeys.STRAIN_DATA_MIN] != null) Row2("Total in zones 1–5", fmtMinutes(inZones))
            }
        }
        CardBox(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Today's data", style = MaterialTheme.typography.titleMedium)
                Row2("Heart rate recorded", today[MetricKeys.STRAIN_DATA_MIN]?.let { fmtMinutes(it.value) } ?: "—")
                today[MetricKeys.STRAIN_LIVE_MIN]?.takeIf { it.value >= 1 }?.let { Row2("   of which from live readings", fmtMinutes(it.value)) }
                Row2("Latest reading", today[MetricKeys.STRAIN_LAST_MS]?.let { fmtClock(it.value) } ?: "—")
                Row2("Max heart rate used", maxBpm?.let { "%.0f bpm".format(it) } ?: "—")
                today[MetricKeys.ZONE_MAX]?.method?.takeIf { it.isNotEmpty() }?.let { Row2("", it, BloopColors.Muted) }
                Row2("Resting heart rate used", restBpm?.let { "%.0f bpm".format(it) } ?: "—")
                MetricRow("Average heart rate", today[MetricKeys.HR_AVG]) { "%.0f bpm".format(it) }
                MetricRow("TRIMP (Edwards)", today[MetricKeys.TRIMP]) { "%.0f".format(it) }
                Text("Minutes only count once the band's data is on the phone: sync after a workout (or record it with Go live). " +
                    "If the zones look too low, set your age on the Device screen so the max heart rate isn't guessed.",
                    color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium)
                SecondaryButton("Recalculate today", Modifier.fillMaxWidth()) { app.recomputeToday() }
            }
        }
        History("Last 14 days", days.take(14), MetricKeys.STRAIN, BloopColors.Caution, 21.0) { "%.1f".format(it) }
        InfoCard("How strain works", if (app.settings.zoneBounds != null) "Zones are your custom bpm ranges (Device screen → Profile). " +
            "The strain score is Edwards TRIMP: minutes in each zone × the zone number, on Bloop's own 0–21 scale: 21 × (1 − e^(−TRIMP/250))." else "Zones are % of your maximum heart rate: zone 1 is 50–60 %, zone 2 60–70 %, zone 3 70–80 %, " +
            "zone 4 80–90 % and zone 5 above 90 %. The strain score is Edwards TRIMP (minutes in each heart-rate-reserve zone × the zone " +
            "number, which uses your resting HR too, so everyday walking adds little), on Bloop's own 0–21 scale: 21 × (1 − e^(−TRIMP/250)).")
    }
}

private fun fmtMinutes(m: Double): String {
    val t = m.toLong()
    return if (t < 60) "$t min" else "%dh %02dm".format(t / 60, t % 60)
}

// ------------------------------------------------------------------ last night's data + wake alarm

/**
 * How much of last night (18:00 yesterday to 14:00 today) is on the phone: one bar per hour, the newest
 * downloaded record, and a warning while the night is still being downloaded, so a short sleep caused by a
 * partial sync is never mistaken for a short night.
 */
@Composable
private fun LastNightData(sleepEndMs: Long?) {
    val app = BloopApplication.from(LocalContext.current)
    val address = app.band.status.collectAsState().value.address ?: return
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val from = today.minusDays(1).atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
    val to = today.atTime(14, 0).atZone(zone).toInstant().toEpochMilli()
    val latestFlow = remember(address) { app.db.samples().observeLatest(address, SampleSource.HISTORICAL) }
    val latest by latestFlow.collectAsState(initial = null)
    val counts by produceState(emptyMap<Long, Int>(), address, latest) {
        value = app.db.samples().hourlyCounts(address, SampleSource.HISTORICAL, from, to).associate { it.hour to it.n }
    }
    val reason = app.analytics.sleepReasons.collectAsState().value[today]

    CardBox(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Last night's band data", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
                var h = from / 3_600_000
                while (h < to / 3_600_000) {
                    val f = ((counts[h] ?: 0) / 3600f).coerceIn(0f, 1f)
                    Box(Modifier.weight(1f).height(36.dp).background(BloopColors.SurfaceHigh, RoundedCornerShape(2.dp)), contentAlignment = Alignment.BottomCenter) {
                        if (f > 0) Box(Modifier.fillMaxWidth().fillMaxHeight(f.coerceAtLeast(0.05f)).background(BloopColors.Sleep, RoundedCornerShape(2.dp)))
                    }
                    h++
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Text("18:00", color = BloopColors.Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                Text("14:00", color = BloopColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
            Text("Downloaded up to " + (latest?.let { fmtClockDay(it) } ?: "-") + ". Each bar is one hour of records.",
                color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium)
            val l = latest
            val incomplete = l == null || (l < to && (sleepEndMs == null || l - sleepEndMs < 30 * 60_000L))
            if (incomplete && (l ?: 0) < System.currentTimeMillis() - 30 * 60_000L) Text(
                "Last night is not fully downloaded yet, so sleep may be missing or too short. Run Sync now on the Device screen " +
                    "and let it finish: the band sends its oldest records first.",
                color = BloopColors.Caution, style = MaterialTheme.typography.bodyMedium,
            )
            reason?.let { Text("No sleep found: $it.", color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WakeAlarmCard() {
    val ctx = LocalContext.current
    val app = BloopApplication.from(ctx)
    val s = app.band.status.collectAsState().value
    var enabled by remember { mutableStateOf(app.settings.alarmEnabled) }
    var minutes by remember { mutableStateOf(app.settings.alarmMinutes ?: (7 * 60)) }
    var picking by remember { mutableStateOf(false) }
    fun save() {
        app.settings.alarmEnabled = enabled; app.settings.alarmMinutes = minutes
        WakeAlarm.reschedule(ctx)
    }

    CardBox(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Wake alarm", style = MaterialTheme.typography.titleMedium)
                    Text("The band vibrates at this time every day", color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium)
                }
                Switch(enabled, onCheckedChange = { enabled = it; save() }, enabled = s.address != null,
                    colors = SwitchDefaults.colors(checkedTrackColor = BloopColors.Sleep))
            }
            TextButton({ picking = true }) {
                Text("%02d:%02d".format(minutes / 60, minutes % 60), style = MaterialTheme.typography.displayLarge,
                    color = if (enabled) BloopColors.OnSurface else BloopColors.Muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(if (s.buzzing) "Buzzing…" else "Test buzz", Modifier.weight(1f), enabled = s.address != null && !s.buzzing) {
                    BandService.ensureRunning(ctx)
                    app.scope.launch { app.band.buzz("test") }
                }
                SecondaryButton("Stop buzz", Modifier.weight(1f), enabled = s.address != null) { BandService.ensureRunning(ctx); app.band.stopBuzz() }
            }
            Text("Try Test buzz first: the buzz command comes from community research and has not been confirmed on your band yet. " +
                "At alarm time Bloop connects to the band and makes it vibrate; the phone itself makes no sound. Keep Bluetooth on " +
                "and the phone near you.", color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (picking) {
        val state = rememberTimePickerState(minutes / 60, minutes % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = false },
            confirmButton = { TextButton({ minutes = state.hour * 60 + state.minute; enabled = true; picking = false; save() }) { Text("Set") } },
            dismissButton = { TextButton({ picking = false }) { Text("Cancel") } },
            text = { TimePicker(state) },
            containerColor = BloopColors.Surface,
        )
    }
}

private fun fmtClockDay(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))

// ------------------------------------------------------------------ pieces

@Composable
private fun MetricRow(label: String, m: MetricValueEntity?, fmt: (Double) -> String) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(label, color = BloopColors.Muted, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    if (m == null) Text("—", color = BloopColors.Muted)
    else {
        KindTag(m)
        Spacer(Modifier.width(8.dp))
        Text(fmt(m.value), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun KindTag(m: MetricValueEntity) {
    val (t, c) = when (m.kind) {
        MetricKind.ESTIMATED -> "estimate" to BloopColors.Caution
        MetricKind.DERIVED -> "derived" to BloopColors.Muted
        else -> "measured" to BloopColors.Recovery
    }
    val conf = m.confidence?.let { if (it < 0.5) " · low confidence" else "" }.orEmpty()
    Text(t + conf, color = c, style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun Baseline(label: String, values: List<Double>, fmt: (Double) -> String) =
    Row2("   $label", if (values.isEmpty()) "—" else fmt(values.average()) + " (${values.size} nights)", BloopColors.Muted)

@Composable
private fun CalibratingNote(nights: Int) = Text(
    "Recovery is calibrating: ${minOf(nights, RecoveryModel.MIN_NIGHTS)} of ${RecoveryModel.MIN_NIGHTS} nights with HRV and resting heart rate so far. Wear the band to sleep and sync in the morning.",
    color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium,
)

@Composable
private fun ZoneBar(label: String, range: String, minutes: Double?, top: Double, color: Color) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.width(96.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(range, color = BloopColors.Muted, style = MaterialTheme.typography.labelSmall)
    }
    Box(Modifier.weight(1f).height(10.dp).background(BloopColors.SurfaceHigh, RoundedCornerShape(5.dp))) {
        if (minutes != null && minutes > 0) Box(Modifier.fillMaxWidth((minutes / top).toFloat().coerceIn(0.02f, 1f)).height(10.dp).background(color, RoundedCornerShape(5.dp)))
    }
    Text(minutes?.let { fmtMinutes(it) } ?: "—", modifier = Modifier.width(72.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium)
}

/** One bar per day for [key]; days without a value are shown as an empty slot. */
@Composable
private fun History(title: String, days: List<Pair<LocalDate, Map<String, MetricValueEntity>>>, key: String, color: Color, max: Double?, fmt: (Double) -> String) {
    val vals = days.mapNotNull { it.second[key]?.value }
    if (vals.isEmpty()) return
    val top = max ?: (vals.max() * 1.1)
    CardBox(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            val d = DateTimeFormatter.ofPattern("EEE d")
            days.forEach { (date, m) ->
                val v = m[key]?.value
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(date.format(d), color = BloopColors.Muted, modifier = Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium)
                    Box(Modifier.weight(1f).height(8.dp).background(BloopColors.SurfaceHigh, RoundedCornerShape(4.dp))) {
                        if (v != null) Box(Modifier.fillMaxWidth((v / top).toFloat().coerceIn(0.02f, 1f)).height(8.dp).background(color, RoundedCornerShape(4.dp)))
                    }
                    Text(v?.let(fmt) ?: "—", modifier = Modifier.width(72.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun fmtDuration(ms: Double): String {
    val m = (ms / 60_000).toLong()
    return "%dh %02dm".format(m / 60, m % 60)
}

private fun fmtClock(epochMs: Double): String =
    Instant.ofEpochMilli(epochMs.toLong()).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

// ------------------------------------------------------------------ building blocks

@Composable
internal fun Page(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content(); Spacer(Modifier.height(8.dp)) }
}

@Composable
internal fun CardBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .background(BloopColors.Surface, RoundedCornerShape(20.dp))
            .border(1.dp, BloopColors.Outline, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) { content() }
}

@Composable
internal fun InfoCard(title: String, body: String) = CardBox(Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = BloopColors.Muted)
    }
}

@Composable
private fun RingTile(label: String, color: Color, fraction: Double?, text: String?, modifier: Modifier) = CardBox(modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Ring(color, 76.dp, fraction, text)
        Spacer(Modifier.height(8.dp))
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = BloopColors.Muted)
    }
}

@Composable
private fun BigRing(label: String, color: Color, fraction: Double?, text: String?) =
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Ring(color, 200.dp, fraction, text)
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = BloopColors.Muted,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 70.dp))
    }

/** Ring filled to [fraction]; with no value it stays empty and shows "—". */
@Composable
private fun Ring(color: Color, size: Dp, fraction: Double?, text: String?) = Box(contentAlignment = Alignment.Center) {
    Canvas(Modifier.size(size)) {
        val w = this.size.minDimension * 0.08f
        val tl = Offset(w / 2, w / 2)
        val sz = Size(this.size.width - w, this.size.height - w)
        drawArc(color.copy(alpha = 0.18f), 0f, 360f, false, style = Stroke(w, cap = StrokeCap.Round), topLeft = tl, size = sz)
        if (fraction != null) drawArc(color, -90f, 360f * fraction.toFloat().coerceIn(0f, 1f), false, style = Stroke(w, cap = StrokeCap.Round), topLeft = tl, size = sz)
    }
    Text(text ?: "—", style = if (size > 100.dp) MaterialTheme.typography.displayLarge else MaterialTheme.typography.headlineMedium,
        color = if (text == null) BloopColors.Muted else BloopColors.OnSurface)
}
