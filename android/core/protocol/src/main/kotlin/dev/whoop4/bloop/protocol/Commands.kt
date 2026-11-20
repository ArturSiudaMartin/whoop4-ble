package dev.whoop4.bloop.protocol

/** Command numbers. Only those marked verified in docs/STATUS.md may be sent (see [Commands]). */
object CommandNumber {
    const val SET_CLOCK = 0x0A
    const val GET_CLOCK = 0x0B
    const val ABORT_HISTORICAL_TRANSMITS = 0x14
    const val SEND_HISTORICAL_DATA = 0x16
    const val HISTORICAL_DATA_RESULT = 0x17
}

/**
 * Builders for the verified, non-destructive command set. This is the only way Bloop produces command
 * frames: there is deliberately no "send arbitrary bytes" path (unlike the debug monitor).
 */
object Commands {
    private fun cmd(c: Int, data: ByteArray = ByteArray(0)) = Framing.frame(PacketType.COMMAND, 0, c, data)

    /** SET_CLOCK: [u32 LE unix seconds][0x01]. The 5th byte is required; 4-byte payloads are silently ignored. */
    fun setClock(epochSeconds: Long): ByteArray = cmd(CommandNumber.SET_CLOCK, Framing.u32le(epochSeconds) + byteArrayOf(0x01))

    /** GET_CLOCK: 1-byte payload; answers only while a historical transmit is active. */
    fun getClock(): ByteArray = cmd(CommandNumber.GET_CLOCK, byteArrayOf(0x00))

    /** Starts a historical transmit (and enables COMMAND_RESPONSEs). */
    fun sendHistoricalData(): ByteArray = cmd(CommandNumber.SEND_HISTORICAL_DATA, byteArrayOf(0x00))

    /** Stops an active historical transmit. */
    fun abortHistoricalTransmits(): ByteArray = cmd(CommandNumber.ABORT_HISTORICAL_TRANSMITS)

    /**
     * ACK for a HISTORY_END batch: [0x01][trim u32 LE][0u32]. This lets the band discard the batch,
     * so it must only be sent after the batch is safely stored.
     */
    fun historicalDataResult(trim: Long): ByteArray =
        cmd(CommandNumber.HISTORICAL_DATA_RESULT, byteArrayOf(0x01) + Framing.u32le(trim) + Framing.u32le(0))
}
