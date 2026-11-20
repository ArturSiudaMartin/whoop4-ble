package dev.whoop4.bloop.ble

/** Link state shown in the UI. */
enum class ConnectionState { NO_BAND, DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class BandStatus(
    val address: String? = null,
    val name: String? = null,
    val connection: ConnectionState = ConnectionState.NO_BAND,
    val error: String? = null,
    /** The user asked for a live connection ("Go live"). */
    val liveRequested: Boolean = false,
    /** Where live HR currently comes from, if any. */
    val liveSource: String? = null,
    /** Short explanation shown on the Live screen (e.g. why no HR is arriving). */
    val liveNote: String? = null,
    val lastHr: Int? = null,
    val lastHrAtUtcMs: Long? = null,
    val lastRrMs: Double? = null,
    val syncing: Boolean = false,
    val sync: SyncProgress? = null,
) {
    /** True while Bloop needs to keep running in the background (connected, live or syncing). */
    val active: Boolean get() = liveRequested || syncing || connection == ConnectionState.CONNECTING || connection == ConnectionState.CONNECTED
}
