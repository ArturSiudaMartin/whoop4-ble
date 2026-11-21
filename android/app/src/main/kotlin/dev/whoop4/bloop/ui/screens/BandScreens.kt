package dev.whoop4.bloop.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.whoop4.bloop.app.BandService
import dev.whoop4.bloop.app.BloopApplication
import dev.whoop4.bloop.ble.BandStatus
import dev.whoop4.bloop.ble.ConnectionState
import dev.whoop4.bloop.ble.FoundDevice
import dev.whoop4.bloop.data.SyncRunEntity
import dev.whoop4.bloop.ui.theme.BloopColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.DateFormat
import java.util.Date

// ------------------------------------------------------------------ permissions

private fun requiredPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
    else add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private fun bluetoothGranted(c: Context) = requiredPermissions()
    .filter { it != Manifest.permission.POST_NOTIFICATIONS }
    .all { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED }

/** Returns (granted, request). */
@Composable
private fun rememberBluetoothPermission(): Pair<Boolean, () -> Unit> {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(bluetoothGranted(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = bluetoothGranted(ctx) }
    return granted to { launcher.launch(requiredPermissions()) }
}

@Composable
private fun PermissionCard(request: () -> Unit) = CardBox(Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Bluetooth permission needed", style = MaterialTheme.typography.titleMedium)
        Text("Bloop needs the Nearby devices permission to find and talk to your band, and notifications to show a sync or live session in progress.",
            color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium)
        PrimaryButton("Grant permission", onClick = request)
    }
}

// ------------------------------------------------------------------ shared bits

@Composable
internal fun PrimaryButton(text: String, enabled: Boolean = true, color: Color = BloopColors.OnSurface, onClick: () -> Unit) = Button(
    onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(50.dp),
    colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = BloopColors.Background),
) { Text(text) }

@Composable
internal fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.height(46.dp)) { Text(text, color = if (enabled) BloopColors.OnSurface else BloopColors.Muted) }

@Composable
internal fun Row2(label: String, value: String, valueColor: Color = BloopColors.OnSurface) = Row(Modifier.fillMaxWidth()) {
    Text(label, color = BloopColors.Muted, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    Text(value, color = valueColor, style = MaterialTheme.typography.bodyMedium)
}

private fun statusText(s: BandStatus): Pair<String, Color> = when {
    s.syncing -> "Syncing" to BloopColors.Caution
    s.connection == ConnectionState.CONNECTED -> (if (s.liveRequested) "Live" else "Connected") to BloopColors.Recovery
    s.connection == ConnectionState.CONNECTING -> "Connecting…" to BloopColors.Caution
    s.connection == ConnectionState.ERROR -> "Couldn't connect" to BloopColors.Strain
    s.connection == ConnectionState.NO_BAND -> "No band chosen" to BloopColors.Muted
    else -> "Not connected" to BloopColors.Muted
}

@Composable
private fun StatusLine(s: BandStatus) = Row(verticalAlignment = Alignment.CenterVertically) {
    val (t, c) = statusText(s)
    Box(Modifier.size(10.dp).background(c, CircleShape))
    Spacer(Modifier.width(8.dp))
    Text(t, color = c, style = MaterialTheme.typography.titleMedium)
}

private fun fmtTime(ms: Long?) = ms?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: "never"

private fun act(ctx: Context, block: (BloopApplication) -> Unit) {
    BandService.ensureRunning(ctx)
    block(BloopApplication.from(ctx))
}

// ------------------------------------------------------------------ Live

@Composable
fun LiveScreen() {
    val ctx = LocalContext.current
    val app = remember { BloopApplication.from(ctx) }
    val s by app.band.status.collectAsStateWithLifecycle()
    val (granted, request) = rememberBluetoothPermission()
    val hist = remember { mutableStateListOf<Int>() }
    LaunchedEffect(Unit) { app.band.heartRate.collect { m -> if (m.hr > 0) { hist.add(m.hr); if (hist.size > 180) hist.removeAt(0) } } }
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(1_000); value = System.currentTimeMillis() } }
    val fresh = s.lastHrAtUtcMs?.let { now - it < 10_000 } == true && s.liveRequested

    Page {
        if (!granted) PermissionCard(request)
        StatusLine(s)
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (fresh) "${s.lastHr}" else "--", style = MaterialTheme.typography.displayLarge, color = BloopColors.Live)
                Text("bpm · measured", color = BloopColors.Muted)
            }
        }
        if (hist.size >= 2 && s.liveRequested) Sparkline(hist)
        if (s.liveRequested) CardBox(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row2("Last RR interval", s.lastRrMs?.let { "%.0f ms".format(it) } ?: "--")
                Row2("Source", s.liveSource ?: "--")
                Row2("Last reading", s.lastHrAtUtcMs?.let { "${(now - it) / 1000} s ago" } ?: "--")
            }
        }
        s.liveNote?.let { Text(it, color = BloopColors.Caution, style = MaterialTheme.typography.bodyMedium) }
        when {
            s.address == null -> Text("Choose your band on the Device screen first (the sliders icon, top right).", color = BloopColors.Muted)
            s.liveRequested -> PrimaryButton("Stop live", color = BloopColors.Strain) { app.band.stopLive() }
            else -> PrimaryButton("Go live", enabled = granted && !s.syncing) { act(ctx) { it.band.startLive() } }
        }
        Text("Live keeps a connection open and streams heart rate and RR intervals until you stop it, even with the screen off. " +
            "Live readings are saved. Between live sessions Bloop only connects to sync.", color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Sparkline(values: List<Int>) = Canvas(Modifier.fillMaxWidth().height(70.dp)) {
    val lo = values.min() - 3f; val hi = values.max() + 3f
    val p = Path()
    values.forEachIndexed { i, v ->
        val x = i / (values.size - 1f) * size.width
        val y = size.height - (v - lo) / (hi - lo) * size.height
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    drawPath(p, BloopColors.Live, style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round))
}

// ------------------------------------------------------------------ Device

@Composable
fun DeviceScreen() {
    val ctx = LocalContext.current
    val app = remember { BloopApplication.from(ctx) }
    val s by app.band.status.collectAsStateWithLifecycle()
    val (granted, request) = rememberBluetoothPermission()
    var scanning by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var readOnly by remember { mutableStateOf(app.settings.readOnlySync) }
    val devices by app.db.devices().observeAll().collectAsStateWithLifecycle(emptyList())
    val device = devices.firstOrNull { it.address == s.address }
    val runs by remember(s.address) { s.address?.let { app.db.syncRuns().observeRecent(it, 5) } ?: flowOf(emptyList()) }
        .collectAsStateWithLifecycle(emptyList())
    val lastComplete by remember(s.address) { s.address?.let { app.db.syncRuns().observeLastComplete(it) } ?: flowOf(null) }
        .collectAsStateWithLifecycle(null)

    Page {
        if (!granted) PermissionCard(request)

        // ---- band
        CardBox(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Band", style = MaterialTheme.typography.titleMedium)
                StatusLine(s)
                Row2("Band", s.name ?: s.address ?: "none chosen")
                if (s.address != null) Row2("Address", s.address!!)
                Row2("Battery", device?.lastBatteryPct?.let { "%.0f%% (%s)".format(it, fmtTime(device.lastBatteryAtUtcMs)) } ?: "not reported yet")
                s.error?.takeIf { s.connection == ConnectionState.ERROR }?.let { Text(it, color = BloopColors.Strain, style = MaterialTheme.typography.bodyMedium) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(if (s.address == null) "Find band" else "Change band", Modifier.weight(1f), enabled = granted && !s.syncing) { scanning = true }
                    if (s.address != null) {
                        if (s.active) SecondaryButton("Disconnect", Modifier.weight(1f)) { app.band.disconnect() }
                        else SecondaryButton("Connect", Modifier.weight(1f), enabled = granted) { act(ctx) { it.band.connect() } }
                    }
                }
                Text("Close the official Whoop app first: it holds the band's Bluetooth connection.", color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // ---- sync
        CardBox(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sync", style = MaterialTheme.typography.titleMedium)
                Row2("Last complete sync", fmtTime(lastComplete?.endedAtUtcMs))
                s.sync?.let { p ->
                    Row2("Status", if (s.syncing) "running" else p.outcome.lowercase(), if (p.outcome == "FAILED") BloopColors.Strain else BloopColors.OnSurface)
                    Row2("Pass / batches", "${p.pass} / ${p.batches}")
                    Row2("Records received", "%,d".format(p.packets))
                    Row2("New samples saved", "%,d".format(p.newSamples))
                    p.latestRecordUtcS?.let { Row2("Newest record", fmtTime(it * 1000)) }
                    p.hwRtc?.let { Row2("Band clock", if (p.rtcInvalid) "INVALID ($it)" else "OK, drift ${p.rtcDriftS} s", if (p.rtcInvalid) BloopColors.Strain else BloopColors.OnSurface) }
                    if (p.rebootDetected) Text("The band's clock went backwards: it probably rebooted.", color = BloopColors.Caution)
                    p.error?.let { Text(it, color = BloopColors.Strain, style = MaterialTheme.typography.bodyMedium) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Read-only sync", style = MaterialTheme.typography.bodyMedium)
                        Text(if (readOnly) "Downloads without acknowledging: nothing is freed on the band. One pass only."
                             else "Each batch is saved, then acknowledged so the band frees that storage.",
                            color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(readOnly, onCheckedChange = { readOnly = it; app.settings.readOnlySync = it }, enabled = !s.syncing,
                        colors = SwitchDefaults.colors(checkedTrackColor = BloopColors.Recovery))
                }
                if (s.syncing) PrimaryButton("Stop sync", color = BloopColors.Strain) { confirmStop = true }
                else PrimaryButton("Sync now", enabled = granted && s.address != null) { act(ctx) { it.band.sync(readOnly) } }
                if (runs.isNotEmpty()) {
                    Text("History", color = BloopColors.Muted, style = MaterialTheme.typography.labelSmall)
                    runs.forEach { RunRow(it) }
                }
            }
        }

        // ---- profile (inputs for the metrics)
        ProfileCard(app)

        // ---- debug log
        DebugLogCard(app)
    }

    if (scanning) ScanDialog(app, onDismiss = { scanning = false })
    // "Stop sync" appears where "Sync now" was, so a double tap would stop a sync that just started.
    if (confirmStop) AlertDialog(
        onDismissRequest = { confirmStop = false },
        title = { Text("Stop the sync?") },
        text = { Text("Everything downloaded so far is kept. The next sync continues where this one stopped.") },
        confirmButton = { TextButton({ confirmStop = false; app.band.stopSync() }) { Text("Stop sync", color = BloopColors.Strain) } },
        dismissButton = { TextButton({ confirmStop = false }) { Text("Keep syncing") } },
        containerColor = BloopColors.Surface,
    )
}

@Composable
private fun RunRow(r: SyncRunEntity) = Row(Modifier.fillMaxWidth()) {
    Text(fmtTime(r.startedAtUtcMs), color = BloopColors.Muted, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    Text("${r.outcome.lowercase()} · +%,d".format(r.newSamples) + if (r.readOnly) " · read-only" else "",
        color = if (r.outcome == "FAILED") BloopColors.Strain else BloopColors.OnSurface, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ScanDialog(app: BloopApplication, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val found = remember { mutableStateListOf<FoundDevice>() }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withTimeoutOrNull(12_000) { app.band.scanner.scan().collect { found.clear(); found.addAll(it) } }
        done = true
    }
    val list = found.filter { it.isWhoop }.ifEmpty { if (done) found.toList() else emptyList() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (done) (if (found.any { it.isWhoop }) "Choose your band" else "No Whoop found: all devices") else "Searching…") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (list.isEmpty()) Text(if (done) "Nothing found. Make sure Bluetooth is on, the band is nearby and the official Whoop app is closed." else "Looking for your band (up to 12 s)…", color = BloopColors.Muted)
                list.forEach { d ->
                    Column(Modifier.fillMaxWidth().clickable {
                        app.chooseBand(d.address, d.name); onDismiss()
                    }.padding(vertical = 10.dp)) {
                        Text(d.name ?: "Unnamed device")
                        Text("${d.address} · ${d.via}" + (d.rssi?.let { " · $it dBm" } ?: ""), color = BloopColors.Muted, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = BloopColors.Surface,
    )
}

@Composable
private fun DebugLogCard(app: BloopApplication) {
    val ctx = LocalContext.current
    val lines by app.log.lines.collectAsStateWithLifecycle()
    CardBox(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Debug log", style = MaterialTheme.typography.titleMedium)
            Text("Every connection step and message exchanged with the band. If something doesn't work, tap Share and send the file.",
                color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium)
            Text(lines.takeLast(14).joinToString("\n") { it.substringAfter(' ').substringAfter(' ') }.ifEmpty { "(empty)" },
                fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp, color = BloopColors.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Share log", Modifier.weight(1f)) { shareLog(ctx, app) }
                SecondaryButton("Clear", Modifier.weight(1f)) { app.log.clear() }
            }
        }
    }
}

private fun shareLog(ctx: Context, app: BloopApplication) {
    val header = buildString {
        appendLine("Bloop ${ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName} debug log")
        appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Status: ${app.band.status.value}")
        appendLine("----")
    }
    val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
    val f = File(dir, "bloop-debug-log.txt").apply { writeText(app.log.exportText(header)) }
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "Bloop debug log").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Share debug log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun ProfileCard(app: BloopApplication) = CardBox(Modifier.fillMaxWidth()) {
    var ageText by remember { mutableStateOf(app.settings.age?.toString().orEmpty()) }
    var need by remember { mutableStateOf(app.settings.sleepNeedHours) }
    var customZones by remember { mutableStateOf(app.settings.zoneBounds != null) }
    val zoneText = remember { (app.settings.zoneBounds ?: DEFAULT_ZONES).map { mutableStateOf(it.toString()) } }
    val bounds = zoneText.map { it.value.toIntOrNull() }
    val zonesValid = bounds.all { it != null && it in 40..240 } && bounds.filterNotNull().zipWithNext().all { (a, b) -> a < b }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Profile", style = MaterialTheme.typography.titleMedium)
        androidx.compose.material3.OutlinedTextField(
            value = ageText,
            onValueChange = { v -> ageText = v.filter { it.isDigit() }.take(3) },
            label = { Text("Age (for maximum heart rate)") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sleep need", modifier = Modifier.weight(1f))
            TextButton({ need = (need - 0.25).coerceAtLeast(5.0) }) { Text("−") }
            Text("%dh %02dm".format(need.toInt(), ((need % 1) * 60).toInt()))
            TextButton({ need = (need + 0.25).coerceAtMost(12.0) }) { Text("+") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Custom heart-rate zones")
                Text("Set where each zone starts, in bpm", color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium)
            }
            androidx.compose.material3.Switch(customZones, onCheckedChange = { customZones = it })
        }
        if (customZones) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (z in 0 until 5) androidx.compose.material3.OutlinedTextField(
                    value = zoneText[z].value,
                    onValueChange = { v -> zoneText[z].value = v.filter { it.isDigit() }.take(3) },
                    label = { Text("Z${z + 1}") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            Text(if (zonesValid) (0 until 5).joinToString("   ") { z -> "Z${z + 1}: ${bounds[z]}" + if (z < 4) "–${bounds[z + 1]!! - 1}" else "+" }
                else "Each zone must start higher than the one before (40–240 bpm).",
                color = if (zonesValid) BloopColors.Muted else BloopColors.Strain, style = MaterialTheme.typography.labelSmall)
        }
        Text("Only used on this phone. Without an age, maximum heart rate is estimated from the highest heart rate seen, which makes strain read high.",
            color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium)
        PrimaryButton("Save and recompute metrics", enabled = !customZones || zonesValid) {
            app.settings.zoneBounds = if (customZones && zonesValid) bounds.filterNotNull() else null
            app.settings.age = ageText.toIntOrNull()?.takeIf { it in 10..100 }
            app.settings.sleepNeedHours = need
            app.recomputeAll()
        }
    }
}

/** Starting values for custom zones: zone 2 = 140–154 bpm, 15 bpm per zone. */
private val DEFAULT_ZONES = listOf(125, 140, 155, 170, 185)
