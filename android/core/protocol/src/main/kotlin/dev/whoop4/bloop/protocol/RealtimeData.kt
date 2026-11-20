package dev.whoop4.bloop.protocol

/**
 * REALTIME_DATA (0x28) heart rate, as streamed on DATA_FROM once per second after the band has received
 * TOGGLE_REALTIME_HR (see [ExperimentalCommands]). Not in the Python reference; the layout was read from a
 * real band's frames (Bloop debug log, 2026-11-20):
 *
 *     aa 1800 ff | 28 02 | e7 5a 00 6b | d0 31 | 4d | 00 … 01 01 | crc32
 *     SOF len crc  type ?   Unix time LE  subsec  HR
 *
 * The four time bytes decoded to the phone's clock (±1 s) on every frame checked, and HR moved smoothly
 * (102 → 77 bpm over an hour), so the HR byte is treated as heart rate but labelled as the band's
 * "realtime stream" in the UI. In [Packet] terms the time's low byte lands in `cmd`, the rest in data[0..2].
 */
data class RealtimeHr(val timestampS: Long, val hr: Int) {
    companion object {
        fun parse(pkt: Packet): RealtimeHr? {
            if (pkt.type != PacketType.REALTIME_DATA || pkt.data.size < 6) return null
            val d = pkt.data
            val ts = (pkt.cmd.toLong()) or ((d[0].toLong() and 0xFF) shl 8) or
                ((d[1].toLong() and 0xFF) shl 16) or ((d[2].toLong() and 0xFF) shl 24)
            val hr = d[5].toInt() and 0xFF
            if (ts !in HistoricalParser.TS_MIN..HistoricalParser.TS_MAX || hr !in 25..220) return null
            return RealtimeHr(ts, hr)
        }
    }
}
