package dev.whoop4.bloop.app

import dev.whoop4.bloop.analytics.HrPoint
import dev.whoop4.bloop.data.ActivitySessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class TcxTest {
    @Test fun buildsValidTcxWithOneTrackpointPerSecond() {
        val start = 1_763_800_000_000L
        val s = ActivitySessionEntity(id = 7, deviceAddress = "x", type = "running", origin = "MANUAL",
            startUtcMs = start, endUtcMs = start + 600_000, name = "Easy <run> & co", createdAtUtcMs = start)
        val hr = (0 until 600).map { HrPoint(start + it * 1000L, 120 + it % 30) } + HrPoint(start + 500, 130)   // duplicate second
        val xml = Tcx.build(s, hr, Activities.of("running"))
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        assertEquals("Running", (doc.getElementsByTagName("Activity").item(0) as org.w3c.dom.Element).getAttribute("Sport"))
        assertEquals(600, doc.getElementsByTagName("Trackpoint").length)
        assertEquals("600", doc.getElementsByTagName("TotalTimeSeconds").item(0).textContent)
        assertEquals("149", doc.getElementsByTagName("MaximumHeartRateBpm").item(0).textContent.trim())
        assertTrue(doc.getElementsByTagName("Time").item(0).textContent.endsWith("Z"))
    }
}
