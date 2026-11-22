package dev.whoop4.bloop.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import dev.whoop4.bloop.ble.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates from the GitHub releases of the fork. Bloop releases are pre-releases tagged `bloop-vX.Y.Z` with
 * the APK attached as `app-release.apk`, so the full release list is read (`/releases/latest` skips pre-releases).
 * Installs go through PackageInstaller; Android checks the signature matches, so only builds signed with the same
 * key can replace the app. Nothing about the user is sent: it is an anonymous read of a public list.
 */
class Updater(private val context: Context, private val settings: Settings, private val log: DebugLog) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val version: String) : State
        data class Available(val version: String, val notes: String, val apkUrl: String, val sizeBytes: Long) : State
        data class Downloading(val version: String, val fraction: Float) : State
        data object Installing : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    val currentVersion: String = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    /** Checks GitHub; with [ifDue], only when the last check was more than [CHECK_EVERY_MS] ago. */
    suspend fun check(ifDue: Boolean = false) {
        if (ifDue && System.currentTimeMillis() - settings.updateLastCheck < CHECK_EVERY_MS) return
        if (_state.value is State.Downloading || _state.value is State.Installing) return
        _state.value = State.Checking
        _state.value = runCatching { fetchNewest() }.fold(
            onSuccess = { it ?: State.UpToDate(currentVersion) },
            onFailure = { log.w("update check failed: ${it.message}"); State.Failed("Couldn't check for updates (${it.message})") },
        )
        settings.updateLastCheck = System.currentTimeMillis()
        (_state.value as? State.Available)?.let { log.i("update: ${it.version} available (running $currentVersion)") }
    }

    private suspend fun fetchNewest(): State.Available? = withContext(Dispatchers.IO) {
        val c = URL("$API/releases?per_page=30").openConnection() as HttpURLConnection
        val text = try {
            c.connectTimeout = 15_000; c.readTimeout = 20_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "Bloop-updater")
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
        val releases = JSONArray(text)
        var best: State.Available? = null
        for (i in 0 until releases.length()) {
            val r = releases.getJSONObject(i)
            if (r.optBoolean("draft")) continue
            val version = r.optString("tag_name").removePrefix(TAG_PREFIX).takeIf { r.optString("tag_name").startsWith(TAG_PREFIX) } ?: continue
            if (compareVersions(version, currentVersion) <= 0) continue
            if (best != null && compareVersions(version, best.version) <= 0) continue
            val assets = r.optJSONArray("assets") ?: continue
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") } ?: continue
            best = State.Available(version, r.optString("body").trim(), apk.getString("browser_download_url"), apk.optLong("size"))
        }
        best
    }

    /** True when Android lets Bloop install apps; otherwise [openInstallPermission] must be shown first. */
    fun canInstall() = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(from: Context) {
        from.startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Downloads the available update and hands it to PackageInstaller. */
    suspend fun downloadAndInstall() {
        val a = _state.value as? State.Available ?: return
        try {
            val file = download(a)
            val info = context.packageManager.getPackageArchiveInfo(file.path, 0) ?: error("the download is not a valid app")
            if (info.packageName != context.packageName) error("the download is a different app (${info.packageName})")
            _state.value = State.Installing
            install(file)
            log.i("update: installing ${a.version}")
        } catch (e: Exception) {
            log.w("update failed: ${e.message}")
            _state.value = State.Failed("Update failed: ${e.message}")
        }
    }

    private suspend fun download(a: State.Available): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "bloop-${a.version}.apk")
        _state.value = State.Downloading(a.version, 0f)
        val c = URL(a.apkUrl).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "Bloop-updater")
            if (c.responseCode != 200) error("download HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: a.sizeBytes
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastShown = 0f
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) {
                            val f = (done.toFloat() / total).coerceAtMost(1f)
                            if (f - lastShown >= 0.01f) { _state.value = State.Downloading(a.version, f); lastShown = f }
                        }
                    }
                }
            }
        } finally { c.disconnect() }
        file
    }

    private fun install(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Android 12+: once Bloop has installed itself, later updates may go through without a prompt.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("bloop.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val done = PendingIntent.getBroadcast(context, id, Intent(context, UpdateResultReceiver::class.java), flags)
            session.commit(done.intentSender)
        }
    }

    internal fun onResult(status: Int, message: String?) {
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> { log.i("update: installed"); _state.value = State.Idle }
            PackageInstaller.STATUS_FAILURE_ABORTED -> { log.i("update: cancelled"); _state.value = State.Idle }
            else -> {
                log.w("update: install failed ($status): $message")
                _state.value = State.Failed("Install failed: ${message ?: "status $status"}" +
                    if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE)
                        ". The new version may be signed differently; install it from the GitHub release page instead." else "")
            }
        }
    }

    companion object {
        const val API = "https://api.github.com/repos/ArturSiudaMartin/whoop4-ble"
        const val RELEASES_PAGE = "https://github.com/ArturSiudaMartin/whoop4-ble/releases"
        const val TAG_PREFIX = "bloop-v"
        const val CHECK_EVERY_MS = 3_600_000L

        /** Compares dotted numeric versions ("0.4.10" > "0.4.9"); missing parts count as 0. */
        fun compareVersions(a: String, b: String): Int {
            val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
            val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
                if (d != 0) return d
            }
            return 0
        }
    }
}

/** PackageInstaller's answer: asks the user to confirm when Android requires it, otherwise reports the outcome. */
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
            c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        BloopApplication.from(c).updater.onResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
