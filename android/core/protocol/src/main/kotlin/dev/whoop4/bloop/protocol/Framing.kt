package dev.whoop4.bloop.protocol

import java.util.UUID

/**
 * Whoop 4.0 BLE identifiers and framing. Kotlin port of `src/whoop4/packet.py`,
 * which remains the reference implementation (see docs/PROTOCOL.md).
 *
 * Frame: [0xAA][len u16 LE][crc8(len)][type][seq][cmd][data…][crc32 LE], where len = payload + 4.
 */
object WhoopUuids {
    val SERVICE: UUID = UUID.fromString("61080001-8d6d-82b8-614a-1c8cb0f8dcc6")
    val CMD_TO: UUID = UUID.fromString("61080002-8d6d-82b8-614a-1c8cb0f8dcc6")
    val CMD_FROM: UUID = UUID.fromString("61080003-8d6d-82b8-614a-1c8cb0f8dcc6")
    val DATA_FROM: UUID = UUID.fromString("61080005-8d6d-82b8-614a-1c8cb0f8dcc6")
    val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

/** Packet types (payload byte 0). */
object PacketType {
    const val COMMAND = 0x23
    const val COMMAND_RESPONSE = 0x24
    const val REALTIME_DATA = 0x28
    const val REALTIME_RAW_DATA = 0x2B
    const val HISTORICAL_DATA = 0x2F
    const val EVENT = 0x30
    const val METADATA = 0x31
    const val CONSOLE_LOGS = 0x32
}

object Crc {
    private val CRC8 = IntArray(256)
    private val CRC32 = IntArray(256)

    init {
        for (i in 0 until 256) {
            var c = i
            repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF }
            CRC8[i] = c
            c = i
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
            CRC32[i] = c
        }
    }

    /** CRC-8, polynomial 0x07, init 0 (over the 2-byte length field). */
    fun crc8(bytes: ByteArray): Int {
        var c = 0
        for (b in bytes) c = CRC8[c xor (b.toInt() and 0xFF)]
        return c
    }

    /** Standard CRC-32 (0xEDB88320, init/xor 0xFFFFFFFF). Returned as an unsigned value in a Long. */
    fun crc32(bytes: ByteArray): Long {
        var c = -1
        for (b in bytes) c = CRC32[(c xor b.toInt()) and 0xFF] xor (c ushr 8)
        return c.inv().toLong() and 0xFFFFFFFFL
    }
}

object Framing {
    const val SOF = 0xAA

    /** Builds a framed packet ready to write to CMD_TO. Mirrors `WhoopPacket.framed_packet()`. */
    fun frame(type: Int, seq: Int, cmd: Int, data: ByteArray = ByteArray(0)): ByteArray {
        val payload = byteArrayOf(type.toByte(), seq.toByte(), cmd.toByte()) + data
        val len = payload.size + 4
        val lenBytes = byteArrayOf(len.toByte(), (len shr 8).toByte())
        val crc = Crc.crc32(payload)
        return byteArrayOf(SOF.toByte()) + lenBytes + byteArrayOf(Crc.crc8(lenBytes).toByte()) + payload + u32le(crc)
    }

    fun u32le(v: Long): ByteArray =
        byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
}
