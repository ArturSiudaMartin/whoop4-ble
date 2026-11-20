package dev.whoop4.bloop.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleTest {
    @Test fun rtcInvalidExact() = assertEquals(12345678L, ConsoleParser.invalidRtcValue("RTC timestamp 12345678 is invalid"))

    // Real console lines arrive with single characters missing ("Nordic Neg MTs", "Data Lenghs").
    @Test fun rtcInvalidWithDroppedCharacters() {
        assertEquals(12345678L, ConsoleParser.invalidRtcValue("RTC timestmp 12345678 is invalid"))
        assertEquals(12345678L, ConsoleParser.invalidRtcValue("RTC timestamp 12345678 is invald"))
    }

    @Test fun rtcValidLineIgnored() = assertNull(ConsoleParser.invalidRtcValue("RTC timestamp 12345678 set"))

    @Test fun noiseDetection() {
        assertTrue(ConsoleParser.isLinkNoise("BLE: Nordic Neg MTs"))
        assertTrue(ConsoleParser.isLinkNoise("      Data Lenghs: 244, 251"))
        assertTrue(ConsoleParser.isLinkNoise("    Effective MTUs: 244, 247"))
        assertFalse(ConsoleParser.isLinkNoise("BLE: Command Send Historical Data"))
    }
}
