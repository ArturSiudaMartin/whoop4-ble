package dev.whoop4.bloop.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Expected values were produced by the Python reference implementation (src/whoop4/packet.py):
 * crc8/crc32 and WhoopPacket(...).framed_packet().hex() for the same inputs.
 */
class FramingTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun crc8MatchesPython() {
        assertEquals(168, Crc.crc8(bytes(0x08, 0x00)))
        assertEquals(252, Crc.crc8(bytes(0x0C, 0x00)))
        assertEquals(0, Crc.crc8(ByteArray(0)))
    }

    @Test fun crc32MatchesPython() {
        assertEquals(0x0L, Crc.crc32(ByteArray(0)))
        assertEquals(0xcbf43926L, Crc.crc32("123456789".toByteArray()))
        assertEquals(0x8f5b6a1bL, Crc.crc32(bytes(0x23, 0, 0x16, 0)))
    }

    @Test fun sendHistoricalDataFrame() {
        assertEquals("aa0800a8230016001b6a5b8f", hex(Framing.frame(PacketType.COMMAND, 0, 0x16, bytes(0x00))))
    }

    @Test fun setClockFrameUsesFiveBytePayload() {
        val data = Framing.u32le(1778539127L) + bytes(0x01)
        assertEquals("aa0c00fc23000a775a026a01762d93b7", hex(Framing.frame(PacketType.COMMAND, 0, 0x0A, data)))
    }

    @Test fun historicalDataResultAckFrame() {
        val data = bytes(0x01) + Framing.u32le(0x12345678L) + Framing.u32le(0L)
        assertEquals("aa1000572300170178563412000000004496087c", hex(Framing.frame(PacketType.COMMAND, 0, 0x17, data)))
    }

    @Test fun abortFrameWithEmptyPayload() {
        assertEquals("aa07006b230014d6f590df", hex(Framing.frame(PacketType.COMMAND, 0, 0x14)))
    }
}
