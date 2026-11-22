package dev.whoop4.bloop.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import dev.whoop4.bloop.app.BloopApplication
import dev.whoop4.bloop.app.Updater
import dev.whoop4.bloop.ui.theme.BloopColors
import kotlinx.coroutines.launch

/**
 * App updates. On Home it appears only when a newer release exists; on the Device screen it is always there,
 * with the running version and a Check button.
 */
@Composable
internal fun UpdateCard(onlyWhenAvailable: Boolean) {
    val ctx = LocalContext.current
    val app = remember { BloopApplication.from(ctx) }
    val u = app.updater
    val state by u.state.collectAsStateWithLifecycle()
    // Re-read the install permission when coming back from Android's settings screen.
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    val canInstall = remember(resumed) { u.canInstall() }
    var showNotes by remember { mutableStateOf(false) }
    val active = state is Updater.State.Available || state is Updater.State.Downloading || state is Updater.State.Installing
    if (onlyWhenAvailable && !active) return

    CardBox(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (active) "Update available" else "App updates", style = MaterialTheme.typography.titleMedium)
            when (val s = state) {
                is Updater.State.Available -> {
                    Text("Bloop ${s.version} is ready (you have ${u.currentVersion})" +
                        (if (s.sizeBytes > 0) ", %.0f MB".format(s.sizeBytes / 1e6) else "") + ".",
                        color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium)
                    if (s.notes.isNotEmpty()) {
                        if (showNotes) Text(s.notes, style = MaterialTheme.typography.bodyMedium)
                        else Text("What's new ›", color = BloopColors.Sleep, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.clickable { showNotes = true })
                    }
                    if (!canInstall) {
                        Text("First allow Bloop to install updates: tap below, turn on \"Allow from this source\", then come back.",
                            color = BloopColors.Caution, style = MaterialTheme.typography.bodyMedium)
                        PrimaryButton("Allow updates") { u.openInstallPermission(ctx) }
                    } else PrimaryButton("Update now", color = BloopColors.Recovery) { app.scope.launch { u.downloadAndInstall() } }
                    Text("Your data stays. Android asks you to confirm the update.", color = BloopColors.Muted, style = MaterialTheme.typography.labelSmall)
                }
                is Updater.State.Downloading -> {
                    Text("Downloading Bloop ${s.version}… ${(s.fraction * 100).toInt()} %", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { s.fraction }, modifier = Modifier.fillMaxWidth(), color = BloopColors.Recovery,
                        trackColor = BloopColors.SurfaceHigh)
                }
                is Updater.State.Installing -> Text("Installing… confirm on the Android screen if it asks. Bloop restarts when it's done.",
                    style = MaterialTheme.typography.bodyMedium)
                is Updater.State.UpToDate -> Text("Bloop ${u.currentVersion} is the newest version.", color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium)
                is Updater.State.Failed -> {
                    Text(s.message, color = BloopColors.Strain, style = MaterialTheme.typography.bodyMedium)
                    SecondaryButton("Open the releases page", Modifier.fillMaxWidth()) {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.RELEASES_PAGE)))
                    }
                }
                Updater.State.Checking -> Text("Checking…", color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium)
                Updater.State.Idle -> Text("You have Bloop ${u.currentVersion}. Bloop checks for a new version when you open it.",
                    color = BloopColors.Muted, style = MaterialTheme.typography.bodyMedium)
            }
            if (!onlyWhenAvailable && !active) Row {
                SecondaryButton("Check for updates", Modifier.fillMaxWidth(), enabled = state != Updater.State.Checking) {
                    app.scope.launch { u.check() }
                }
            }
        }
    }
}
