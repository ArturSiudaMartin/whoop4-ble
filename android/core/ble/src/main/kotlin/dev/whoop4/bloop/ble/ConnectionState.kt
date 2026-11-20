package dev.whoop4.bloop.ble

/**
 * Connection states exposed to the UI. The GATT client, scanner, live-HR source and sync engine
 * that drive these states arrive in Phases 3–5.
 */
sealed interface ConnectionState {
    data object NoBandChosen : ConnectionState
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data object Live : ConnectionState
    data class Syncing(val pass: Int, val records: Long) : ConnectionState
    data class Error(val message: String) : ConnectionState
}
