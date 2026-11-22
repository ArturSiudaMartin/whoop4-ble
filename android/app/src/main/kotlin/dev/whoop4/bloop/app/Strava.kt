package dev.whoop4.bloop.app

import android.net.Uri
import dev.whoop4.bloop.analytics.HrPoint
import dev.whoop4.bloop.ble.DebugLog
import dev.whoop4.bloop.data.ActivitySessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.temporal.ChronoUnit

/** A session as a Garmin TCX file: one lap, one trackpoint per second with heart rate (no GPS or distance). */
object Tcx {
    fun build(session: ActivitySessionEntity, hr: List<HrPoint>, type: ActivityType): String {
        val start = Instant.ofEpochMilli(session.startUtcMs).truncatedTo(ChronoUnit.SECONDS)
        val end = session.endUtcMs ?: hr.lastOrNull()?.tsMs ?: session.startUtcMs
        val sport = when (type.strava) { "Run", "TrailRun", "VirtualRun" -> "Running"; "Ride", "MountainBikeRide", "VirtualRide" -> "Biking"; else -> "Other" }
        val perSecond = hr.filter { it.hr in 25..240 }.distinctBy { it.tsMs / 1000 }
        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
            append("""<TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">""").append('\n')
            append("""<Activities><Activity Sport="$sport"><Id>$start</Id>""").append('\n')
            append("""<Lap StartTime="$start"><TotalTimeSeconds>${(end - session.startUtcMs) / 1000}</TotalTimeSeconds>""")
            append("<DistanceMeters>0</DistanceMeters><Calories>0</Calories>")
            if (perSecond.isNotEmpty()) {
                append("<AverageHeartRateBpm><Value>${perSecond.map { it.hr }.average().toInt()}</Value></AverageHeartRateBpm>")
                append("<MaximumHeartRateBpm><Value>${perSecond.maxOf { it.hr }}</Value></MaximumHeartRateBpm>")
            }
            append("<Intensity>Active</Intensity><TriggerMethod>Manual</TriggerMethod><Track>\n")
            for (p in perSecond) {
                append("<Trackpoint><Time>${Instant.ofEpochMilli(p.tsMs).truncatedTo(ChronoUnit.SECONDS)}</Time>")
                append("<HeartRateBpm><Value>${p.hr}</Value></HeartRateBpm></Trackpoint>\n")
            }
            append("</Track></Lap>")
            session.name?.let { append("<Notes>${it.replace("&", "&amp;").replace("<", "&lt;")}</Notes>") }
            append("</Activity></Activities></TrainingCenterDatabase>\n")
        }
    }
}

/**
 * Strava upload through the user's own Strava API application (strava.com/settings/api): they enter its client
 * ID and secret, sign in once in the browser, and Bloop keeps the refresh token on the phone. Uploads a TCX file,
 * waits for Strava to process it, then sets the sport type (TCX itself only knows running, biking or other).
 */
class Strava(private val settings: Settings, private val log: DebugLog) {
    private val _athlete = MutableStateFlow(settings.stravaAthlete)
    /** The connected athlete's name, or null when not connected. */
    val athlete: StateFlow<String?> = _athlete

    val connected get() = settings.stravaRefreshToken != null

    fun authorizeUri(): Uri = Uri.parse("https://www.strava.com/oauth/mobile/authorize").buildUpon()
        .appendQueryParameter("client_id", settings.stravaClientId)
        .appendQueryParameter("redirect_uri", REDIRECT)
        .appendQueryParameter("response_type", "code")
        .appendQueryParameter("approval_prompt", "auto")
        .appendQueryParameter("scope", "activity:write,activity:read")
        .build()

    fun isRedirect(uri: Uri?) = uri != null && uri.scheme == "bloop" && uri.host == "localhost" && uri.path == "/strava"

    /** Finishes sign-in from the browser's redirect (bloop://localhost/strava?code=…). */
    suspend fun handleRedirect(uri: Uri): Result<String> = runCatching {
        uri.getQueryParameter("error")?.let { error("Strava said: $it") }
        val code = uri.getQueryParameter("code") ?: error("no code in the redirect")
        if (uri.getQueryParameter("scope")?.contains("activity:write") == false) error("uploading was not allowed; connect again and keep \"Upload your activities\" ticked")
        val j = post("https://www.strava.com/oauth/token", mapOf(
            "client_id" to settings.stravaClientId.orEmpty(), "client_secret" to settings.stravaClientSecret.orEmpty(),
            "code" to code, "grant_type" to "authorization_code",
        ))
        saveTokens(j)
        val a = j.optJSONObject("athlete")
        val name = listOfNotNull(a?.optString("firstname"), a?.optString("lastname")).joinToString(" ").trim().ifEmpty { "connected" }
        settings.stravaAthlete = name; _athlete.value = name
        log.i("strava: connected")
        name
    }.onFailure { log.w("strava: sign-in failed: ${it.message}") }

    fun disconnect() {
        settings.stravaAccessToken = null; settings.stravaRefreshToken = null; settings.stravaExpiresAt = 0
        settings.stravaAthlete = null; _athlete.value = null
    }

    /** Uploads the session; returns the Strava activity ID. [progress] receives short status texts. */
    suspend fun upload(session: ActivitySessionEntity, hr: List<HrPoint>, progress: (String) -> Unit): Result<Long> = runCatching {
        val type = Activities.of(session.type)
        val token = accessToken()
        progress("Uploading…")
        val tcx = Tcx.build(session, hr, type).toByteArray()
        var up = multipart("https://www.strava.com/api/v3/uploads", token, mapOf(
            "data_type" to "tcx", "name" to (session.name ?: type.label), "external_id" to "bloop-session-${session.id}",
        ), "file", "bloop-session-${session.id}.tcx", tcx)
        val uploadId = up.getLong("id")
        progress("Strava is processing it…")
        var waited = 0
        while (up.isNull("activity_id") || up.optLong("activity_id") == 0L) {
            up.optString("error").takeIf { it.isNotEmpty() && it != "null" }?.let { err ->
                Regex("activities/(\\d+)").find(err)?.groupValues?.get(1)?.toLongOrNull()?.let { return@runCatching it }   // duplicate
                error(err.replace(Regex("<[^>]*>"), ""))
            }
            if (waited >= 60) error("Strava is still processing the file; check Strava in a minute")
            delay(2_000); waited += 2
            up = get("https://www.strava.com/api/v3/uploads/$uploadId", token)
        }
        val activityId = up.getLong("activity_id")
        progress("Setting the activity type…")
        runCatching { put("https://www.strava.com/api/v3/activities/$activityId", token, mapOf("sport_type" to type.strava)) }
            .onFailure { log.w("strava: setting sport_type ${type.strava} failed: ${it.message}") }
        log.i("strava: uploaded session ${session.id} as activity $activityId")
        activityId
    }.onFailure { log.w("strava: upload failed: ${it.message}") }

    private suspend fun accessToken(): String {
        val refresh = settings.stravaRefreshToken ?: error("not connected to Strava")
        if (settings.stravaAccessToken != null && settings.stravaExpiresAt > System.currentTimeMillis() / 1000 + 120) return settings.stravaAccessToken!!
        val j = post("https://www.strava.com/oauth/token", mapOf(
            "client_id" to settings.stravaClientId.orEmpty(), "client_secret" to settings.stravaClientSecret.orEmpty(),
            "refresh_token" to refresh, "grant_type" to "refresh_token",
        ))
        saveTokens(j)
        return settings.stravaAccessToken!!
    }

    private fun saveTokens(j: JSONObject) {
        settings.stravaAccessToken = j.getString("access_token")
        settings.stravaRefreshToken = j.getString("refresh_token")
        settings.stravaExpiresAt = j.getLong("expires_at")
    }

    // ---------------------------------------------------------------- HTTP

    private fun form(fields: Map<String, String>) = fields.entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }

    private suspend fun post(url: String, fields: Map<String, String>, token: String? = null) =
        request("POST", url, token, "application/x-www-form-urlencoded", form(fields).toByteArray())

    private suspend fun put(url: String, token: String, fields: Map<String, String>) =
        request("PUT", url, token, "application/x-www-form-urlencoded", form(fields).toByteArray())

    private suspend fun get(url: String, token: String) = request("GET", url, token, null, null)

    private suspend fun multipart(url: String, token: String, fields: Map<String, String>, fileField: String, fileName: String, file: ByteArray): JSONObject {
        val b = "----bloop${System.nanoTime()}"
        val out = ByteArrayOutputStream()
        for ((k, v) in fields) out.write("--$b\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n".toByteArray())
        out.write("--$b\r\nContent-Disposition: form-data; name=\"$fileField\"; filename=\"$fileName\"\r\nContent-Type: application/xml\r\n\r\n".toByteArray())
        out.write(file)
        out.write("\r\n--$b--\r\n".toByteArray())
        return request("POST", url, token, "multipart/form-data; boundary=$b", out.toByteArray())
    }

    private suspend fun request(method: String, url: String, token: String?, contentType: String?, body: ByteArray?): JSONObject = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).let { j -> j.optString("message") + (j.optJSONArray("errors")?.let { " $it" } ?: "") } }.getOrDefault(text.take(200))
                error("HTTP $code: $msg")
            }
            JSONObject(text.ifEmpty { "{}" })
        } finally { c.disconnect() }
    }

    companion object {
        const val REDIRECT = "bloop://localhost/strava"
    }
}
