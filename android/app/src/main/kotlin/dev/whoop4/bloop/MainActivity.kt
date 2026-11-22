package dev.whoop4.bloop

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import dev.whoop4.bloop.app.BloopApplication
import dev.whoop4.bloop.ui.BloopApp
import dev.whoop4.bloop.ui.theme.BloopTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light system-bar icons on Bloop's always-dark UI.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent { BloopTheme { BloopApp() } }
        if (savedInstanceState == null) handleStravaRedirect(intent)
    }

    override fun onResume() {
        super.onResume()
        // At most once an hour: is there a newer Bloop release on GitHub?
        val app = BloopApplication.from(this)
        lifecycleScope.launch { app.updater.check(ifDue = true) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleStravaRedirect(intent)
    }

    private fun handleStravaRedirect(intent: Intent?) {
        val strava = BloopApplication.from(this).strava
        val uri = intent?.data?.takeIf { strava.isRedirect(it) } ?: return
        lifecycleScope.launch {
            strava.handleRedirect(uri)
                .onSuccess { Toast.makeText(this@MainActivity, "Strava connected: $it", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(this@MainActivity, "Strava: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
}
