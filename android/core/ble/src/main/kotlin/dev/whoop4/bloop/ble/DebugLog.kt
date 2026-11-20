package dev.whoop4.bloop.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Bloop's diagnostic log: every connection step, every command sent and every non-bulk frame received
 * (bulk historical records are summarised). The user can share it from the Device screen so problems can
 * be diagnosed remotely, without the phone being attached to a PC.
 *
 * Lines are kept in memory (for the UI) and appended to a file with simple rotation (current + one previous).
 * Pure Kotlin: no Android classes, so it can be used from unit tests.
 */
class DebugLog(private val file: File? = null, private val maxMemoryLines: Int = 3000, private val maxFileBytes: Long = 2_000_000) {
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val buf = ArrayDeque<String>()
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    /** Most recent lines, oldest first. */
    val lines: StateFlow<List<String>> = _lines

    @Synchronized
    fun log(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] $msg"
        buf.addLast(line)
        while (buf.size > maxMemoryLines) buf.removeFirst()
        _lines.value = buf.toList()
        file?.let { f ->
            runCatching {
                f.parentFile?.mkdirs()
                if (f.length() > maxFileBytes) { File(f.path + ".1").delete(); f.renameTo(File(f.path + ".1")) }
                f.appendText(line + "\n")
            }
        }
    }

    fun i(msg: String) = log("info", msg)
    fun w(msg: String) = log("WARN", msg)
    fun tx(what: String, bytes: ByteArray) = log("tx", "$what ${bytes.hex()}")
    fun rx(what: String) = log("rx", what)

    /** Previous + current log file contents, for sharing. */
    @Synchronized
    fun exportText(header: String): String = buildString {
        appendLine(header)
        file?.let { f ->
            File(f.path + ".1").takeIf { it.exists() }?.let { append(it.readText()) }
            if (f.exists()) append(f.readText())
        } ?: buf.forEach { appendLine(it) }
    }

    @Synchronized
    fun clear() {
        buf.clear(); _lines.value = emptyList()
        file?.let { runCatching { it.delete(); File(it.path + ".1").delete() } }
    }
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
