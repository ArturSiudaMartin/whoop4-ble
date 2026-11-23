package dev.whoop4.bloop.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.whoop4.bloop.analytics.HeartRateMax
import dev.whoop4.bloop.analytics.HrPoint
import dev.whoop4.bloop.analytics.Zones
import dev.whoop4.bloop.app.Activities
import dev.whoop4.bloop.app.ActivityType
import dev.whoop4.bloop.app.BandService
import dev.whoop4.bloop.app.BloopApplication
import dev.whoop4.bloop.app.MetricKeys
import dev.whoop4.bloop.app.Tcx
import dev.whoop4.bloop.data.ActivitySessionEntity
import dev.whoop4.bloop.data.SampleSource
import dev.whoop4.bloop.ui.theme.BloopColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs
import kotlin.math.ceil

/*
 * Activity sessions: a time range with an activity type (recorded now, or added afterwards) whose heart rate is read
 * from the band-stored samples plus live readings for any gaps. Nothing is copied: the numbers update as syncs bring
 * data in.
 */

private const val GAP_MS = 60_000L   // a longer gap in the data breaks the chart line
private const val MAX_DT_MS = 5_000L // a sample counts for the time until the next one, at most this
private val ZONE_COLORS = listOf(BloopColors.Muted, BloopColors.Sleep, BloopColors.Recovery, BloopColors.Caution, BloopColors.Strain)

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

private fun ActivitySessionEntity.title() = name ?: Activities.of(type).label

// ------------------------------------------------------------------ on the Live screen

/** Start / end a session, add a past one, and the list of sessions. */
@Composable
internal fun SessionsSection(granted: Boolean, onOpen: (Long) -> Unit) {
    val ctx = LocalContext.current
    val app = remember { BloopApplication.from(ctx) }
    val s by app.band.status.collectAsState()
    val running by app.db.activities().observeRunning().collectAsState(initial = null)
    val all by app.db.activities().observeAll().collectAsState(initial = emptyList())
    var picking by remember { mutableStateOf(false) }
    var addingPast by remember { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(1_000); value = System.currentTimeMillis() } }
    val recent = all.map { it.type }.distinct().filter { it != "workout" }.take(4).map { Activities.of(it) }

    CardBox(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Activity", style = MaterialTheme.typography.titleMedium)
            val r = running
            if (r != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Activities.of(r.type).emoji, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.title(), style = MaterialTheme.typography.bodyLarge)
                        Text("since ${clock(r.startUtcMs)}", color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(elapsed(now - r.startUtcMs), style = MaterialTheme.typography.headlineMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("View", Modifier.weight(1f)) { onOpen(r.id) }
                    SecondaryButton("End", Modifier.weight(1f)) {
                        app.scope.launch {
                            app.db.activities().update(r.copy(endUtcMs = System.currentTimeMillis()))
                            if (app.settings.sessionStartedLive) { app.settings.sessionStartedLive = false; app.band.stopLive() }
                            app.recomputeToday()
                        }
                    }
                }
            } else {
                Text("Track a workout to see its average and maximum heart rate, a chart and time in zones. " +
                    "Starting one also starts Live, so the numbers fill in right away; the band's own copy arrives with the next sync.",
                    color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium)
                PrimaryButton("Start activity", enabled = s.address != null) { picking = true }
                SecondaryButton("Add a past activity", Modifier.fillMaxWidth(), enabled = s.address != null) { addingPast = true }
            }
        }
    }

    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    var shown by remember { mutableStateOf(today) }
    if (shown > today) shown = today
    val done = all.filter { it.endUtcMs != null }
    val ofDay = done.filter { Instant.ofEpochMilli(it.startUtcMs).atZone(zone).toLocalDate() == shown }
    val oldest = done.minOfOrNull { Instant.ofEpochMilli(it.startUtcMs).atZone(zone).toLocalDate() }
    if (done.isNotEmpty()) CardBox(Modifier.fillMaxWidth().pointerInput(Unit) {
        // Swipe right for the day before, left for the day after (never past today).
        var drag = 0f
        detectHorizontalDragGestures(
            onDragStart = { drag = 0f },
            onDragEnd = {
                val step = 60.dp.toPx()
                if (drag > step) shown = shown.minusDays(1)
                else if (drag < -step && shown < LocalDate.now(zone)) shown = shown.plusDays(1)
            },
        ) { change, dx -> change.consume(); drag += dx }
    }) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Activities", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton({ shown = shown.minusDays(1) }) { Text("‹") }
                Text(dayLabel(shown), modifier = Modifier.clickable(enabled = shown != today) { shown = today })
                TextButton({ shown = shown.plusDays(1) }, enabled = shown < today) { Text("›") }
            }
            if (ofDay.isEmpty()) {
                Text(
                    if (oldest != null && shown < oldest) "No activities before ${dayLabel(oldest)}." else "No activities on this day.",
                    color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 10.dp),
                )
                if (shown != today) TextButton({ shown = today }) { Text("Back to today") }
            } else {
                val total = ofDay.sumOf { (it.endUtcMs ?: it.startUtcMs) - it.startUtcMs }
                Text("${ofDay.size} ${if (ofDay.size == 1) "activity" else "activities"} · ${elapsed(total)}",
                    color = BloopColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
            ofDay.forEach { a ->
                val endMs = a.endUtcMs ?: return@forEach
                Row(Modifier.fillMaxWidth().clickable { onOpen(a.id) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Activities.of(a.type).emoji, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.title(), style = MaterialTheme.typography.bodyLarge)
                        Text(clock(a.startUtcMs) + "–" + clock(endMs),
                            color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(elapsed(endMs - a.startUtcMs), color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium)
                    Text("  ›", color = BloopColors.Muted)
                }
            }
        }
    }

    if (picking) ActivityPicker(recent, onDismiss = { picking = false }) { type ->
        picking = false
        app.scope.launch {
            val t = System.currentTimeMillis()
            val id = app.db.activities().insert(ActivitySessionEntity(
                deviceAddress = s.address, type = type.key, origin = "MANUAL", startUtcMs = t, createdAtUtcMs = t,
            ))
            if (granted && !app.band.status.value.liveRequested && !app.band.status.value.syncing) {
                BandService.ensureRunning(ctx)
                app.band.startLive()
                app.settings.sessionStartedLive = true
            }
            onOpen(id)
        }
    }
    if (addingPast) PastSessionDialog(recent, onDismiss = { addingPast = false }) { type, name, from, to ->
        addingPast = false
        shown = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        app.scope.launch {
            val id = app.db.activities().insert(ActivitySessionEntity(
                deviceAddress = s.address, type = type.key, origin = "MANUAL", startUtcMs = from, endUtcMs = to,
                name = name, createdAtUtcMs = System.currentTimeMillis(),
            ))
            onOpen(id)
        }
    }
}

// ------------------------------------------------------------------ session detail

@Composable
fun SessionScreen(id: Long, onClosed: () -> Unit) {
    val ctx = LocalContext.current
    val app = BloopApplication.from(ctx)
    val session by remember(id) { app.db.activities().observe(id) }.collectAsState(initial = null)
    val address = app.band.status.collectAsState().value.address
    val maxFlow = remember(address) { address?.let { app.db.metrics().observe(it, MetricKeys.ZONE_MAX, 1) } ?: flowOf(emptyList()) }
    val storedMax by maxFlow.collectAsState(initial = emptyList())
    val latestSample by remember(address) {
        address?.let { app.db.samples().observeLatest(it, SampleSource.HISTORICAL) } ?: flowOf(null)
    }.collectAsState(initial = null)
    val stravaAthlete by app.strava.athlete.collectAsState()
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var changingType by remember { mutableStateOf(false) }
    var selectedZone by remember { mutableStateOf<Int?>(null) }
    var stravaState by remember { mutableStateOf<String?>(null) }
    var stravaBusy by remember { mutableStateOf(false) }
    var stravaId by remember(id) { mutableStateOf(app.settings.stravaActivity(id)) }

    val a = session ?: return Page { Text("Loading…", color = BloopColors.Muted) }
    val type = Activities.of(a.type)
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
        Row(Modifier.fillMaxWidth().clickable { changingType = true }, verticalAlignment = Alignment.CenterVertically) {
            Text(type.emoji, style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(a.title(), style = MaterialTheme.typography.headlineMedium)
                Text((if (a.name != null) type.label + " · " else "") + dayLabel(Instant.ofEpochMilli(a.startUtcMs).atZone(ZoneId.systemDefault()).toLocalDate()) + " · " + clock(a.startUtcMs) + "–" +
                    (a.endUtcMs?.let(::clock) ?: "now") + " · " + elapsed(end - a.startUtcMs),
                    color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium)
            }
            Text("Change", color = BloopColors.Muted, style = MaterialTheme.typography.labelSmall)
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
                    HrChart(hr, a.startUtcMs, end, stats.avg, zoneOf, selectedZone)
                    Text("Touch and hold the chart (or tap it) to see the time and heart rate. Dashed line: average. Gaps: no data.",
                        color = BloopColors.Muted, style = MaterialTheme.typography.labelSmall)
                    Row2("Heart rate recorded", fmtMinutes(stats.minutesWithData) + " of " + fmtMinutes((end - a.startUtcMs) / 60_000.0))
                }
            }
        }
        stats?.zoneMinutes?.let { mins ->
            CardBox(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Time in heart-rate zones", style = MaterialTheme.typography.titleMedium)
                    Text("Tap a zone to highlight it on the chart.", color = BloopColors.Muted, style = MaterialTheme.typography.labelSmall)
                    val top = mins.drop(1).maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
                    for (z in 1..5) {
                        val range = if (custom != null) (if (z < 5) "${custom[z - 1]}–${custom[z] - 1} bpm" else "${custom[4]}+ bpm")
                            else "%.0f–%s bpm".format(maxHr!! * Zones.lowerFraction(z), if (z == 5) "max" else "%.0f".format(maxHr * Zones.lowerFraction(z + 1) - 1))
                        val sel = selectedZone == z
                        ZoneBar("Zone $z", range, mins[z], top, ZONE_COLORS[z - 1],
                            Modifier.background(if (sel) BloopColors.SurfaceHigh else Color.Transparent, RoundedCornerShape(10.dp))
                                .clickable { selectedZone = if (sel) null else z }.padding(horizontal = 6.dp, vertical = 6.dp))
                    }
                }
            }
        }

        // ---- export
        CardBox(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Export", style = MaterialTheme.typography.titleMedium)
                when {
                    running -> Text("End the activity first.", color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium)
                    stats == null -> Text("Nothing to export until there is heart-rate data.", color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium)
                    stravaAthlete == null -> Text("To upload straight to Strava, connect it on the Device screen (Strava card). " +
                        "You can also share a TCX file and upload it at strava.com → Upload activity.", color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium)
                    stravaId != null -> {
                        Text("On Strava ✓", color = BloopColors.Recovery)
                        SecondaryButton("Open in Strava", Modifier.fillMaxWidth()) {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.strava.com/activities/$stravaId")))
                        }
                    }
                    else -> PrimaryButton(if (stravaBusy) "Uploading…" else "Upload to Strava", enabled = !stravaBusy, color = Color(0xFFFC5200)) {
                        stravaBusy = true; stravaState = null
                        app.scope.launch {
                            app.strava.upload(a, hr) { stravaState = it }
                                .onSuccess { app.settings.setStravaActivity(a.id, it); stravaId = it; stravaState = null }
                                .onFailure { stravaState = "Upload failed: ${it.message}" }
                            stravaBusy = false
                        }
                    }
                }
                stravaState?.let { Text(it, color = if (it.startsWith("Upload failed")) BloopColors.Strain else BloopColors.Muted, style = MaterialTheme.typography.bodyMedium) }
                if (!running && stats != null) SecondaryButton("Share TCX file", Modifier.fillMaxWidth()) { shareTcx(ctx, a, hr) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Rename", Modifier.weight(1f)) { renaming = true }
            SecondaryButton("Delete", Modifier.weight(1f)) { deleting = true }
        }
        Text("Only the activity's times are saved; its heart rate comes from your synced band data, so it fills in after a sync.",
            color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium)
    }

    if (changingType) ActivityPicker(emptyList(), onDismiss = { changingType = false }) { t ->
        changingType = false
        app.scope.launch { app.db.activities().update(a.copy(type = t.key)) }
    }
    if (renaming) NameDialog("Rename", "Save", initial = a.name.orEmpty(), placeholder = type.label, onDismiss = { renaming = false }) { name ->
        renaming = false
        app.scope.launch { app.db.activities().update(a.copy(name = name)) }
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Delete this activity?") },
        text = { Text("Only the activity is removed from Bloop (not from Strava). Its heart-rate data stays on the phone.") },
        confirmButton = { TextButton({ deleting = false; app.scope.launch { app.db.activities().delete(a.id); app.settings.setStravaActivity(a.id, null) }; onClosed() }) { Text("Delete", color = BloopColors.Strain) } },
        dismissButton = { TextButton({ deleting = false }) { Text("Cancel") } },
        containerColor = BloopColors.Surface,
    )
}

private fun shareTcx(ctx: Context, a: ActivitySessionEntity, hr: List<HrPoint>) {
    val type = Activities.of(a.type)
    val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
    val date = Instant.ofEpochMilli(a.startUtcMs).atZone(ZoneId.systemDefault()).toLocalDate()
    val f = File(dir, "bloop-${type.key}-$date-${a.id}.tcx").apply { writeText(Tcx.build(a, hr, type)) }
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    val send = Intent(Intent.ACTION_SEND).setType("application/vnd.garmin.tcx+xml").putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, a.title()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Share TCX file").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun StatTile(label: String, value: String?, modifier: Modifier) = CardBox(modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(value ?: "—", style = MaterialTheme.typography.headlineMedium, color = if (value == null) BloopColors.Muted else BloopColors.OnSurface)
        Text(label.uppercase() + " BPM", style = MaterialTheme.typography.labelSmall, color = BloopColors.Muted)
    }
}

/**
 * Heart rate over the session, averaged into time buckets; gaps over a minute break the line. Touch and hold (or tap)
 * shows the nearest sample's time and bpm. With [selectedZone], the parts in that zone are coloured and shaded.
 */
@Composable
private fun HrChart(hr: List<HrPoint>, from: Long, to: Long, avg: Double, zoneOf: ((Int) -> Int)?, selectedZone: Int?) {
    val tm = rememberTextMeasurer()
    val label = MaterialTheme.typography.labelSmall.copy(color = BloopColors.Muted)
    val tipStyle = MaterialTheme.typography.labelSmall.copy(color = BloopColors.OnSurface)
    val n = 300
    val span = (to - from).coerceAtLeast(1)
    val buckets = remember(hr, from, to) {
        val sum = DoubleArray(n); val cnt = IntArray(n)
        for (p in hr) {
            val i = (((p.tsMs - from).toDouble() / span) * n).toInt().coerceIn(0, n - 1)
            sum[i] += p.hr; cnt[i]++
        }
        List(n) { if (cnt[it] > 0) (sum[it] / cnt[it]).toFloat() else null }
    }
    val times = remember(hr) { LongArray(hr.size) { hr[it].tsMs } }
    var touchX by remember { mutableStateOf<Float?>(null) }
    val lo = (hr.minOf { it.hr } - 5).coerceAtLeast(0).toFloat()
    val hi = (hr.maxOf { it.hr } + 5).toFloat()
    val tipFmt = remember { DateTimeFormatter.ofPattern("HH:mm:ss") }

    Canvas(
        Modifier.fillMaxWidth().height(220.dp)
            .pointerInput(hr) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { touchX = it.x },
                    onDrag = { change, _ -> touchX = change.position.x; change.consume() },
                    onDragEnd = { touchX = null },
                    onDragCancel = { touchX = null },
                )
            }
            .pointerInput(hr) { detectTapGestures(onTap = { touchX = if (touchX == null) it.x else null }) },
    ) {
        val left = 30.dp.toPx(); val top = 26.dp.toPx(); val bottom = 18.dp.toPx()
        val w = size.width - left; val h = size.height - top - bottom
        fun y(v: Float) = top + h - (v - lo) / (hi - lo) * h
        fun x(t: Long) = left + (t - from).toFloat() / span * w
        fun bx(i: Int) = left + (i + 0.5f) / n * w
        val bucketMs = span.toDouble() / n
        val inZone = { v: Float -> selectedZone != null && zoneOf != null && zoneOf(v.toInt()) == selectedZone }

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

        val zoneColor = selectedZone?.let { ZONE_COLORS[it - 1] }
        // shade the time spent in the selected zone
        if (zoneColor != null) buckets.forEachIndexed { i, v ->
            if (v != null && inZone(v)) drawRect(zoneColor.copy(alpha = 0.14f), Offset(left + i.toFloat() / n * w, top), Size(w / n + 0.5f, h))
        }
        // the heart-rate line (dimmed while a zone is selected), then the selected zone's parts on top
        fun line(keep: (Float) -> Boolean, color: Color, width: Float) {
            val path = Path()
            var last = -1
            buckets.forEachIndexed { i, v ->
                if (v == null || !keep(v)) { if (v != null) last = -1; return@forEachIndexed }
                val start = last < 0 || (i - last) * bucketMs > GAP_MS
                if (start) { path.moveTo(bx(i), y(v)); drawCircle(color, width / 2, Offset(bx(i), y(v))) } else path.lineTo(bx(i), y(v))
                last = i
            }
            drawPath(path, color, style = Stroke(width, join = StrokeJoin.Round, cap = StrokeCap.Round))
        }
        line({ true }, if (zoneColor != null) BloopColors.Live.copy(alpha = 0.3f) else BloopColors.Live, 2.dp.toPx())
        if (zoneColor != null) line(inZone, zoneColor, 3.dp.toPx())
        // average
        drawLine(BloopColors.OnSurface.copy(alpha = 0.6f), Offset(left, y(avg.toFloat())), Offset(size.width, y(avg.toFloat())), 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))

        // touch read-out: the nearest real sample to the finger
        val tx = touchX
        if (tx != null && times.isNotEmpty()) {
            val t = from + ((tx - left) / w * span).toLong()
            var i = java.util.Arrays.binarySearch(times, t).let { if (it >= 0) it else (-it - 1) }
            if (i >= times.size || (i > 0 && abs(times[i - 1] - t) < abs(times[i] - t))) i = (i - 1).coerceAtLeast(0)
            val p = hr[i]
            val near = abs(p.tsMs - t) <= maxOf(GAP_MS / 2, (bucketMs * 2).toLong())
            val px = if (near) x(p.tsMs) else tx.coerceIn(left, size.width)
            drawLine(BloopColors.OnSurface.copy(alpha = 0.7f), Offset(px, top), Offset(px, top + h), 1.dp.toPx())
            val text = Instant.ofEpochMilli(if (near) p.tsMs else t).atZone(ZoneId.systemDefault()).format(tipFmt) +
                if (near) "   ${p.hr} bpm" + (zoneOf?.let { z -> z(p.hr).takeIf { it > 0 }?.let { "  · zone $it" } } ?: "") else "   no data"
            if (near) {
                drawCircle(BloopColors.Background, 5.dp.toPx(), Offset(px, y(p.hr.toFloat())))
                drawCircle(zoneOf?.let { z -> ZONE_COLORS.getOrNull(z(p.hr) - 1) } ?: BloopColors.Live, 3.5.dp.toPx(), Offset(px, y(p.hr.toFloat())))
            }
            val m = tm.measure(text, tipStyle)
            val pad = 6.dp.toPx()
            val bw = m.size.width + 2 * pad; val bh = m.size.height + pad
            val bxl = (px - bw / 2).coerceIn(0f, size.width - bw)
            drawRoundRect(BloopColors.SurfaceHigh, Offset(bxl, 0f), Size(bw, bh), CornerRadius(6.dp.toPx()))
            drawText(m, topLeft = Offset(bxl + pad, pad / 2))
        }
    }
}

// ------------------------------------------------------------------ dialogs

/** Activity list like a sports app's: search, recently used first, then everything. */
@Composable
private fun ActivityPicker(recent: List<ActivityType>, onDismiss: () -> Unit, onPick: (ActivityType) -> Unit) {
    var query by remember { mutableStateOf("") }
    val matches = Activities.all.filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose activity") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    if (query.isBlank() && recent.isNotEmpty()) {
                        item { Text("RECENT", color = BloopColors.Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 4.dp)) }
                        items(recent, key = { "r" + it.key }) { ActivityRow(it) { onPick(it) } }
                        item { HorizontalDivider(color = BloopColors.Outline, modifier = Modifier.padding(vertical = 4.dp)) }
                    }
                    items(matches, key = { it.key }) { ActivityRow(it) { onPick(it) } }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
        containerColor = BloopColors.Surface,
    )
}

@Composable
private fun ActivityRow(t: ActivityType, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t.emoji, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(36.dp))
        Text(t.label, style = MaterialTheme.typography.bodyLarge)
    }

@Composable
private fun NameDialog(title: String, confirm: String, initial: String, placeholder: String, onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(text, { text = it.take(40) }, label = { Text("Name") }, placeholder = { Text(placeholder) }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton({ onConfirm(text.trim().ifEmpty { null }) }) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
        containerColor = BloopColors.Surface,
    )
}

/** An activity that already happened: type, a day, a start and an end time (an end before the start means the next day). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PastSessionDialog(recent: List<ActivityType>, onDismiss: () -> Unit, onConfirm: (ActivityType, String?, Long, Long) -> Unit) {
    val zone = ZoneId.systemDefault()
    val nowT = LocalTime.now().withSecond(0).withNano(0)
    var type by remember { mutableStateOf(recent.firstOrNull() ?: Activities.of("running")) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var start by remember { mutableStateOf(nowT.minusHours(1)) }
    var end by remember { mutableStateOf(nowT) }
    var name by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<Int?>(null) }   // 0 = start, 1 = end
    var choosingType by remember { mutableStateOf(false) }
    val from = date.atTime(start).atZone(zone).toInstant().toEpochMilli()
    val to = (if (end > start) date else date.plusDays(1)).atTime(end).atZone(zone).toInstant().toEpochMilli()
    val valid = to > from && from < System.currentTimeMillis()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a past activity") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth().clickable { choosingType = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(type.emoji, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(36.dp))
                    Text(type.label, modifier = Modifier.weight(1f))
                    Text("Change", color = BloopColors.Muted, style = MaterialTheme.typography.labelSmall)
                }
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name (optional)") }, placeholder = { Text(type.label) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, keyboardType = KeyboardType.Text), modifier = Modifier.fillMaxWidth())
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
                if (!valid) Text("The activity must start in the past.", color = BloopColors.Strain, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton({ onConfirm(type, name.trim().ifEmpty { null }, from, to) }, enabled = valid) { Text("Add") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
        containerColor = BloopColors.Surface,
    )

    if (choosingType) ActivityPicker(recent, onDismiss = { choosingType = false }) { type = it; choosingType = false }
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

// ------------------------------------------------------------------ Strava connection (Device screen)

@Composable
internal fun StravaCard(app: BloopApplication) = CardBox(Modifier.fillMaxWidth()) {
    val ctx = LocalContext.current
    val athlete by app.strava.athlete.collectAsState()
    var id by remember { mutableStateOf(app.settings.stravaClientId.orEmpty()) }
    var secret by remember { mutableStateOf(app.settings.stravaClientSecret.orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Strava", style = MaterialTheme.typography.titleMedium)
        val a = athlete
        if (a != null) {
            Row2("Connected as", a)
            Text("Open an activity and tap Upload to Strava.", color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium)
            SecondaryButton("Disconnect", Modifier.fillMaxWidth()) { app.strava.disconnect() }
        } else {
            Text("Strava only lets apps upload through an API application, so you make your own (free, about a minute):\n" +
                "1. In a browser, sign in at strava.com/settings/api and create an app. Any name, category and website will do; " +
                "set Authorization Callback Domain to localhost.\n" +
                "2. Copy its Client ID and Client Secret below.\n" +
                "3. Tap Connect and approve. The keys stay on this phone.",
                color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(id, { id = it.filter(Char::isDigit).take(12) }, label = { Text("Client ID") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(secret, { secret = it.trim().take(64) }, label = { Text("Client Secret") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            PrimaryButton("Connect Strava", enabled = id.isNotEmpty() && secret.length >= 20, color = Color(0xFFFC5200)) {
                app.settings.stravaClientId = id; app.settings.stravaClientSecret = secret
                ctx.startActivity(Intent(Intent.ACTION_VIEW, app.strava.authorizeUri()))
            }
        }
    }
}

// ------------------------------------------------------------------ formatting

private fun clock(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

private fun dayLabel(d: LocalDate): String =
    when (d) {
        LocalDate.now() -> "Today"
        LocalDate.now().minusDays(1) -> "Yesterday"
        else -> d.format(DateTimeFormatter.ofPattern("EEE d MMM"))
    }

private fun elapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s < 3600) "%d:%02d".format(s / 60, s % 60) else "%dh %02dm".format(s / 3600, (s % 3600) / 60)
}
