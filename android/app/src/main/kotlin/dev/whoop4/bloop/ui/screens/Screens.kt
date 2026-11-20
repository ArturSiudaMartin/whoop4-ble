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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.whoop4.bloop.ui.theme.BloopColors

/*
 * Phase 2: navigation and visual language only. Every screen states plainly what it will show and
 * when; no screen displays a number until real band data backs it (no mock data in production screens).
 */

@Composable
fun HomeScreen() = Page {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        RingTile("Recovery", BloopColors.Recovery, Modifier.weight(1f))
        RingTile("Strain", BloopColors.Caution, Modifier.weight(1f))
        RingTile("Sleep", BloopColors.Sleep, Modifier.weight(1f))
    }
    PendingCard("Today", "Heart rate, resting heart rate, HRV and your daily summary appear here once the band has synced and the analytics engine is in place.", "Phases 5–8")
}

@Composable
fun RecoveryScreen() = Page {
    BigRing("Recovery", BloopColors.Recovery)
    PendingCard("How recovery works", "An estimate built from your overnight HRV (RMSSD) and resting heart rate compared with your own rolling baseline, plus sleep. It stays in a \"calibrating\" state until there are enough valid nights; it is never shown as a made-up number.", "Phase 8")
    PendingCard("HRV · Resting HR · Respiratory rate", "Nightly values and baseline comparisons. Respiratory rate is only shown if it can be estimated reliably from RR intervals.", "Phase 8")
}

@Composable
fun SleepScreen() = Page {
    BigRing("Sleep performance", BloopColors.Sleep)
    PendingCard("Sleep", "Total sleep, timeline, consistency and history, detected from motion, heart rate and wrist on/off events. Sleep stages are low-confidence estimates and will be labelled as such.", "Phase 8")
}

@Composable
fun StrainScreen() = Page {
    BigRing("Strain", BloopColors.Caution)
    PendingCard("Strain", "Daily cardiovascular load (TRIMP-based, Bloop's own scale), heart-rate zones, activity history and how strain builds through the day.", "Phase 8")
}

@Composable
fun LiveScreen() = Page {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("--", style = MaterialTheme.typography.displayLarge, color = BloopColors.Live)
            Text("bpm", color = BloopColors.Muted)
        }
    }
    Button(
        onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = BloopColors.OnSurface, contentColor = BloopColors.Background),
    ) { Text("Go live") }
    PendingCard("Live connection on demand", "Tap Go live to open a live connection and stream heart rate and RR intervals for as long as you want. Between live sessions Bloop just connects every few hours to download what the band stored.", "Phases 3–4")
}

@Composable
fun DeviceScreen() = Page {
    PendingCard("Band", "Find and connect your band, see its battery and connection state, and disconnect.", "Phase 3")
    PendingCard("Sync", "Sync status, last successful sync and history. Downloads are saved before each batch is acknowledged, with an optional read-only mode.", "Phase 5")
    PendingCard("Background", "Automatic sync every few hours and when the band is nearby, plus Samsung battery settings guidance.", "Phase 10")
}

// ------------------------------------------------------------------ building blocks

@Composable
private fun Page(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content(); Spacer(Modifier.height(8.dp)) }
}

@Composable
private fun CardBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .background(BloopColors.Surface, RoundedCornerShape(20.dp))
            .border(1.dp, BloopColors.Outline, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) { content() }
}

@Composable
private fun PendingCard(title: String, body: String, phase: String) = CardBox(Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                "NOT YET AVAILABLE · $phase".uppercase(), style = MaterialTheme.typography.labelSmall, color = BloopColors.Muted,
            )
        }
        Text(body, style = MaterialTheme.typography.bodyMedium, color = BloopColors.Muted)
    }
}

@Composable
private fun RingTile(label: String, color: Color, modifier: Modifier) = CardBox(modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        EmptyRing(color, 76.dp)
        Spacer(Modifier.height(8.dp))
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = BloopColors.Muted)
    }
}

@Composable
private fun BigRing(label: String, color: Color) = Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
    EmptyRing(color, 200.dp)
    Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = BloopColors.Muted,
        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 70.dp))
}

/** Ring with no progress drawn and "—" in the middle: there is no data to show yet. */
@Composable
private fun EmptyRing(color: Color, size: Dp) = Box(contentAlignment = Alignment.Center) {
    Canvas(Modifier.size(size)) {
        val w = this.size.minDimension * 0.08f
        drawArc(color.copy(alpha = 0.18f), 0f, 360f, false, style = Stroke(w, cap = StrokeCap.Round),
            topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
            size = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w))
    }
    Text("—", style = if (size > 100.dp) MaterialTheme.typography.displayLarge else MaterialTheme.typography.headlineMedium,
        color = BloopColors.Muted)
}
