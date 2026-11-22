package dev.whoop4.bloop.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.whoop4.bloop.analytics.HeartRateMax
import dev.whoop4.bloop.analytics.HrPoint
import dev.whoop4.bloop.analytics.Zones
import dev.whoop4.bloop.app.BandService
import dev.whoop4.bloop.app.BloopApplication
import dev.whoop4.bloop.app.MetricKeys
import dev.whoop4.bloop.data.ActivitySessionEntity
import dev.whoop4.bloop.ui.theme.BloopColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.ceil

/*
 * Activity sessions: a named time range (recorded now, or added afterwards) whose heart rate is read from the
 * band-stored samples plus live readings for any gaps. Nothing is copied: the numbers update as syncs bring data in.
 */

private const val GAP_MS = 60_000L   // a longer gap in the data breaks the chart line
private const val MAX_DT_MS = 5_000L // a sample counts for the time until the next one, at most this

data class SessionStats(val avg: Double, val max: Int, val min: Int, val minutesWithData: Double, val zoneMinutes: List<Double>?)

/** Simple stats over the samples (≈1 per second). Zones only when bpm bounds or a max HR are known. */
internal fun sessionStats(hr: List<HrPoint>, zoneOf: ((Int) -> Int)?): SessionStats? {
    if (hr.isEmpty()) return null
    val zones = DoubleArray(6)
    var covered = 0L
    for (i in hr.indices) {
        val next = if (i + 1 < hr.size) hr[i + 1].tsMs else hr[i].tsMs + 1000
        val dt = (next - hr[i].tsMs).coerceIn(0, MAX_DT_MS)
        covered += dt
        zoneOf?.let { zones[it(hr[i].hr)] += dt / 60_000.0 }
    }
    return SessionStats(hr.map { it.hr }.average(), hr.maxOf { it.hr }, hr.minOf { it.hr }, covered / 60_000.0, zoneOf?.let { zones.toList() })
}

// ------------------------------------------------------------------ on the Live screen

/** Start / end a session, add a past one, and the list of sessions. */
@Composable
internal fun SessionsSection(granted: Boolean, onOpen: (Long) -> Unit) {
    val ctx = LocalContext.current
    val app = remember { BloopApplication.from(ctx) }
    val s by app.band.status.collectAsState()
    val running by app.db.activities().observeRunning().collectAsState(initial = null)
    val all by app.db.activities().observeAll().collectAsState(initial = emptyList())
    var naming by remember { mutableStateOf(false) }
    var addingPast by remember { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(1_000); value = System.currentTimeMillis() } }

    CardBox(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Session", style = MaterialTheme.typography.titleMedium)
            val r = running
            if (r != null) {
                Row2(r.name ?: "Session", "since ${clock(r.startUtcMs)} · ${elapsed(now - r.startUtcMs)}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("View", Modifier.weight(1f)) { onOpen(r.id) }
                    SecondaryButton("End session", Modifier.weight(1f)) {
                        app.scope.launch {
                            app.db.activities().update(r.copy(endUtcMs = System.currentTimeMillis()))
                            if (app.settings.sessionStartedLive) { app.settings.sessionStartedLive = false; app.band.stopLive() }
                            app.recomputeToday()
                        }
                    }
                }
            } else {
                Text("Mark a workout or any activity to see its average and maximum heart rate and a chart. " +
                    "Starting a session also starts Live, so the numbers fill in right away; the band's own copy arrives with the next sync.",
                    color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Start session", Modifier.weight(1f), enabled = s.address != null) { naming = true }
                    SecondaryButton("Add past session", Modifier.weight(1f), enabled = s.address != null) { addingPast = true }
                }
            }
        }
    }

    val done = all.filter { it.endUtcMs != null }
    if (done.isNotEmpty()) CardBox(Modifier.fillMaxWidth()) {
        Column {
            Text("Sessions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            done.take(50).forEach { a ->
                val endMs = a.endUtcMs ?: return@forEach
                Row(Modifier.fillMaxWidth().clickable { onOpen(a.id) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name ?: "Session", style = MaterialTheme.typography.bodyLarge)
                        Text(day(a.startUtcMs) + " · " + clock(a.startUtcMs) + "–" + clock(endMs),
                            color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(elapsed(endMs - a.startUtcMs), color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium)
                    Text("  ›", color = BloopColors.Muted)
                }
            }
        }
    }

    if (naming) NameDialog("Start a session", "Start", initial = "", onDismiss = { naming = false }) { name ->
        naming = false
        app.scope.launch {
            val t = System.currentTimeMillis()
            val id = app.db.activities().insert(ActivitySessionEntity(
                deviceAddress = s.address, type = "workout", origin = "MANUAL", startUtcMs = t, name = name, createdAtUtcMs = t,
            ))
            if (granted && !app.band.status.value.liveRequested && !app.band.status.value.syncing) {
                BandService.ensureRunning(ctx)
                app.band.startLive()
                app.settings.sessionStartedLive = true
            }
            onOpen(id)
        }
    }
    if (addingPast) PastSessionDialog(onDismiss = { addingPast = false }) { name, from, to ->
        addingPast = false
        app.scope.launch {
            val id = app.db.activities().insert(ActivitySessionEntity(
                deviceAddress = s.address, type = "workout", origin = "MANUAL", startUtcMs = from, endUtcMs = to,
                name = name, createdAtUtcMs = System.currentTimeMillis(),
            ))
            onOpen(id)
        }
    }
}

// ------------------------------------------------------------------ session detail

@Composable
fun SessionScreen(id: Long, onClosed: () -> Unit) {
    val app = BloopApplication.from(LocalContext.current)
    val session by remember(id) { app.db.activities().observe(id) }.collectAsState(initial = null)
    val address = app.band.status.collectAsState().value.address
    val maxFlow = remember(address) { address?.let { app.db.metrics().observe(it, MetricKeys.ZONE_MAX, 1) } ?: flowOf(emptyList()) }
    val storedMax by maxFlow.collectAsState(initial = emptyList())
    val latestSample by remember(address) {
        address?.let { app.db.samples().observeLatest(it, dev.whoop4.bloop.data.SampleSource.HISTORICAL) } ?: flowOf(null)
    }.collectAsState(initial = null)
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    val a = session ?: return Page { Text("Loading…", color = BloopColors.Muted) }
    val device = a.deviceAddress ?: address
    val running = a.endUtcMs == null
    // Reload when the session changes, new band data arrives, and every 5 s while it is running.
    val hr by produceState(emptyList<HrPoint>(), a, device, latestSample) {
        if (device == null) return@produceState
        do {
            value = app.analytics.heartRate(device, a.startUtcMs, a.endUtcMs ?: System.currentTimeMillis())
            if (running) delay(5_000)
        } while (running)
    }
    val custom = app.settings.zoneBounds
    val maxHr = app.settings.maxHr?.toDouble() ?: app.settings.age?.let { HeartRateMax.tanaka(it).bpm } ?: storedMax.firstOrNull()?.value
    val zoneOf: ((Int) -> Int)? = when {
        custom != null -> { h -> Zones.ofBounds(h, custom) }
        maxHr != null -> { h -> Zones.ofMax(h, maxHr) }
        else -> null
    }
    val stats = remember(hr, custom, maxHr) { sessionStats(hr, zoneOf) }
    val end = a.endUtcMs ?: System.currentTimeMillis()

    Page {
        Column {
            Text(a.name ?: "Session", style = MaterialTheme.typography.headlineMedium)
            Text(day(a.startUtcMs) + " · " + clock(a.startUtcMs) + "–" + (a.endUtcMs?.let(::clock) ?: "now") + " · " + elapsed(end - a.startUtcMs),
                color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile("Average", stats?.let { "%.0f".format(it.avg) }, Modifier.weight(1f))
            StatTile("Max", stats?.max?.toString(), Modifier.weight(1f))
            StatTile("Min", stats?.min?.toString(), Modifier.weight(1f))
        }
        CardBox(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Heart rate", style = MaterialTheme.typography.titleMedium)
                if (stats == null) Text(
                    if (device == null) "Choose your band on the Device screen first."
                    else "No heart rate for this time yet. Run Sync now on the Device screen to download it from the band.",
                    color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium,
                )
                else {
                    HrChart(hr, a.startUtcMs, end, stats.avg)
                    Text("Dashed line: average. Gaps are times with no data.", color = BloopColors.Muted, style = MaterialTheme.typography.labelSmall)
                    Row2("Heart rate recorded", fmtMinutes(stats.minutesWithData) + " of " + fmtMinutes((end - a.startUtcMs) / 60_000.0))
                }
            }
        }
        stats?.zoneMinutes?.let { mins ->
            CardBox(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Time in heart-rate zones", style = MaterialTheme.typography.titleMedium)
                    val colors = listOf(BloopColors.Muted, BloopColors.Sleep, BloopColors.Recovery, BloopColors.Caution, BloopColors.Strain)
                    val top = mins.drop(1).maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
                    for (z in 1..5) {
                        val range = if (custom != null) (if (z < 5) "${custom[z - 1]}–${custom[z] - 1} bpm" else "${custom[4]}+ bpm")
                            else "%.0f–%s bpm".format(maxHr!! * Zones.lowerFraction(z), if (z == 5) "max" else "%.0f".format(maxHr * Zones.lowerFraction(z + 1) - 1))
                        ZoneBar("Zone $z", range, mins[z], top, colors[z - 1])
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Rename", Modifier.weight(1f)) { renaming = true }
            SecondaryButton("Delete", Modifier.weight(1f)) { deleting = true }
        }
        Text("Only the session's times are saved; its heart rate comes from your synced band data, so it fills in after a sync.",
            color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium)
    }

    if (renaming) NameDialog("Rename session", "Save", initial = a.name.orEmpty(), onDismiss = { renaming = false }) { name ->
        renaming = false
        app.scope.launch { app.db.activities().update(a.copy(name = name)) }
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Delete this session?") },
        text = { Text("Only the session is removed. Its heart-rate data stays on the phone.") },
        confirmButton = { TextButton({ deleting = false; app.scope.launch { app.db.activities().delete(a.id) }; onClosed() }) { Text("Delete", color = BloopColors.Strain) } },
        dismissButton = { TextButton({ deleting = false }) { Text("Cancel") } },
        containerColor = BloopColors.Surface,
    )
}

@Composable
private fun StatTile(label: String, value: String?, modifier: Modifier) = CardBox(modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(value ?: "—", style = MaterialTheme.typography.headlineMedium, color = if (value == null) BloopColors.Muted else BloopColors.OnSurface)
        Text(label.uppercase() + " BPM", style = MaterialTheme.typography.labelSmall, color = BloopColors.Muted)
    }
}

/** Heart rate over the session, averaged into ~240 time buckets; gaps over a minute break the line. */
@Composable
private fun HrChart(hr: List<HrPoint>, from: Long, to: Long, avg: Double) {
    val tm = rememberTextMeasurer()
    val label = MaterialTheme.typography.labelSmall.copy(color = BloopColors.Muted)
    val n = 240
    val span = (to - from).coerceAtLeast(1)
    val buckets = remember(hr, from, to) {
        val sum = DoubleArray(n); val cnt = IntArray(n)
        for (p in hr) {
            val i = (((p.tsMs - from).toDouble() / span) * n).toInt().coerceIn(0, n - 1)
            sum[i] += p.hr; cnt[i]++
        }
        List(n) { if (cnt[it] > 0) (sum[it] / cnt[it]).toFloat() else null }
    }
    val lo = (hr.minOf { it.hr } - 5).coerceAtLeast(0).toFloat()
    val hi = (hr.maxOf { it.hr } + 5).toFloat()
    Canvas(Modifier.fillMaxWidth().height(200.dp)) {
        val left = 30.dp.toPx(); val top = 6.dp.toPx(); val bottom = 18.dp.toPx()
        val w = size.width - left; val h = size.height - top - bottom
        fun y(v: Float) = top + h - (v - lo) / (hi - lo) * h
        fun x(t: Long) = left + (t - from).toFloat() / span * w
        // horizontal grid with bpm labels
        val step = listOf(5f, 10f, 20f, 25f, 50f).first { (hi - lo) / it <= 5 }
        var g = ceil(lo / step) * step
        while (g <= hi) {
            drawLine(BloopColors.Outline, Offset(left, y(g)), Offset(size.width, y(g)), 1f)
            drawText(tm, "%.0f".format(g), Offset(0f, y(g) - 7.dp.toPx()), label)
            g += step
        }
        // time labels: start, middle, end
        for ((t, anchor) in listOf(from to 0f, (from + span / 2) to 0.5f, to to 1f)) {
            val m = tm.measure(clock(t), label)
            drawText(m, topLeft = Offset((x(t) - m.size.width * anchor).coerceIn(left, size.width - m.size.width), size.height - m.size.height))
        }
        // the heart-rate line
        val bucketMs = span.toDouble() / n
        val path = Path()
        var last = -1
        buckets.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val px = left + (i + 0.5f) / n * w
            if (last < 0 || (i - last) * bucketMs > GAP_MS) path.moveTo(px, y(v)) else path.lineTo(px, y(v))
            if (last < 0 || (i - last) * bucketMs > GAP_MS) drawCircle(BloopColors.Live, 1.5.dp.toPx(), Offset(px, y(v)))
            last = i
        }
        drawPath(path, BloopColors.Live, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
        // average
        drawLine(BloopColors.OnSurface.copy(alpha = 0.6f), Offset(left, y(avg.toFloat())), Offset(size.width, y(avg.toFloat())), 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
    }
}

// ------------------------------------------------------------------ dialogs

@Composable
private fun NameDialog(title: String, confirm: String, initial: String, onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(text, { text = it.take(40) }, label = { Text("Name (optional), e.g. Run") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton({ onConfirm(text.trim().ifEmpty { null }) }) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
        containerColor = BloopColors.Surface,
    )
}

/** A session that already happened: a day, a start and an end time (an end before the start means the next day). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PastSessionDialog(onDismiss: () -> Unit, onConfirm: (String?, Long, Long) -> Unit) {
    val zone = ZoneId.systemDefault()
    val nowT = LocalTime.now().withSecond(0).withNano(0)
    var date by remember { mutableStateOf(LocalDate.now()) }
    var start by remember { mutableStateOf(nowT.minusHours(1)) }
    var end by remember { mutableStateOf(nowT) }
    var name by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<Int?>(null) }   // 0 = start, 1 = end
    val from = date.atTime(start).atZone(zone).toInstant().toEpochMilli()
    val to = (if (end > start) date else date.plusDays(1)).atTime(end).atZone(zone).toInstant().toEpochMilli()
    val valid = to > from && from < System.currentTimeMillis()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a past session") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Day", modifier = Modifier.weight(1f))
                    TextButton({ date = date.minusDays(1) }) { Text("‹") }
                    Text(when (date) { LocalDate.now() -> "Today"; LocalDate.now().minusDays(1) -> "Yesterday"; else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM")) })
                    TextButton({ date = date.plusDays(1) }, enabled = date < LocalDate.now()) { Text("›") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Start", modifier = Modifier.weight(1f)); TextButton({ picking = 0 }) { Text(start.toString()) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("End" + if (end <= start) " (next day)" else "", modifier = Modifier.weight(1f)); TextButton({ picking = 1 }) { Text(end.toString()) }
                }
                if (!valid) Text("The session must start in the past.", color = BloopColors.Strain, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton({ onConfirm(name.trim().ifEmpty { null }, from, to) }, enabled = valid) { Text("Add") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
        containerColor = BloopColors.Surface,
    )

    picking?.let { which ->
        val t = if (which == 0) start else end
        val state = rememberTimePickerState(t.hour, t.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = null },
            confirmButton = { TextButton({ LocalTime.of(state.hour, state.minute).let { if (which == 0) start = it else end = it }; picking = null }) { Text("Set") } },
            dismissButton = { TextButton({ picking = null }) { Text("Cancel") } },
            text = { TimePicker(state) },
            containerColor = BloopColors.Surface,
        )
    }
}

// ------------------------------------------------------------------ formatting

private fun clock(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

private fun day(ms: Long): String {
    val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    return when (d) {
        LocalDate.now() -> "Today"
        LocalDate.now().minusDays(1) -> "Yesterday"
        else -> d.format(DateTimeFormatter.ofPattern("EEE d MMM"))
    }
}

private fun elapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s < 3600) "%d:%02d".format(s / 60, s % 60) else "%dh %02dm".format(s / 3600, (s % 3600) / 60)
}
