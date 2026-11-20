package dev.whoop4.bloop.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Frames copied from a real band's debug log (2026-11-20). */
class RealtimeHrTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun decodesRealFrame() {
        val r = RealtimeHr.parse(Packet.parse(hex("aa1800ff2802e75a006bd0314d0000000000000000000101a1587e0e"))!!)!!
        assertEquals(1795185383L, r.timestampS)   // 2026-11-20 14:36:23Z, logged at 14:36:24.049Z
        assertEquals(77, r.hr)
    }

    @Test fun decodesSecondFrame() {
        val r = RealtimeHr.parse(Packet.parse(hex("aa1800ff2802f65a006b90624f000000000000000000010189804dd6"))!!)!!
        assertEquals(1795185398L, r.timestampS)
        assertEquals(79, r.hr)
    }

    @Test fun ignoresOtherTypes() {
        assertNull(RealtimeHr.parse(Packet(PacketType.EVENT, 0, 0xe7, hex("5a006bd0314d"))))
    }

    @Test fun rejectsZeroHr() {
        assertNull(RealtimeHr.parse(Packet(PacketType.REALTIME_DATA, 2, 0xe7, hex("5a006bd03100"))))
    }
}
