package dev.whoop4.bloop.protocol

/**
 * ⚠ UNVERIFIED commands, kept apart from [Commands] on purpose.
 *
 * These are the "enable heart-rate broadcast" commands the web app and the legacy Java app have sent on
 * every connect since Nov 2026 (no harm observed). Their numbers come from the community whoomp project's
 * numbering; they are not in docs/PROTOCOL.md, and whether they are what makes live HR work is unknown.
 *
 * Bloop sends them **only** as a fallback, when the user taps "Go live" and no heart rate arrives from the
 * standard Heart Rate service. Every use is written to the debug log with the outcome, to settle the question.
 */
object ExperimentalCommands {
    const val TOGGLE_REALTIME_HR = 0x03
    const val TOGGLE_GENERIC_HR_PROFILE = 0x0E

    fun enableGenericHrProfile(): ByteArray = Framing.frame(PacketType.COMMAND, 0, TOGGLE_GENERIC_HR_PROFILE, byteArrayOf(0x01))
    fun enableRealtimeHr(): ByteArray = Framing.frame(PacketType.COMMAND, 0, TOGGLE_REALTIME_HR, byteArrayOf(0x01))

    // ---- Haptics (wake alarm). Numbers from the community whoomp project's CommandNumber table
    // (github.com/k0n5n/whoomp packet.js: RUN_ALARM = 68, STOP_HAPTICS = 122), whose web page sends
    // RUN_ALARM with payload [0x00] to make the strap buzz. Consistent with this repo's own probing:
    // 0x44 answered `000101000000000000` and a vibration was observed in the 0x43-0x4D range (docs/PROTOCOL.md).
    // Bloop sends them only when the user taps "Test buzz" or their alarm fires; results are logged.
    const val RUN_ALARM = 0x44
    const val STOP_HAPTICS = 0x7A

    fun runAlarm(): ByteArray = Framing.frame(PacketType.COMMAND, 0, RUN_ALARM, byteArrayOf(0x00))
    fun stopHaptics(): ByteArray = Framing.frame(PacketType.COMMAND, 0, STOP_HAPTICS, byteArrayOf(0x00))
}
