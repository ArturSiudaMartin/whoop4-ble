package dev.whoop4.bloop.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import dev.whoop4.bloop.protocol.Commands
import dev.whoop4.bloop.protocol.Framing
import dev.whoop4.bloop.protocol.HeartRateMeasurement
import dev.whoop4.bloop.protocol.PacketType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseTest {
    private val dev = "AA:BB:CC:DD:EE:FF"
    private val ts = 1778513464L   // 2026-05-11T15:31:04Z
    private lateinit var db: BloopDatabase
    private lateinit var ingest: IngestRepository

    @get:Rule
    val migrations = MigrationTestHelper(
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(), BloopDatabase::class.java,
    )

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BloopDatabase::class.java)
            .allowMainThreadQueries().build()
        ingest = IngestRepository(db)
    }

    @After fun close() = db.close()

    // ---------------------------------------------------------------- frame builders (layout per packet.py)

    private fun record(seq: Long, t: Long, subsec: Int, hr: Int, rr: List<Int> = emptyList(), accel: FloatArray? = floatArrayOf(0.5319f, 0.3221f, 0.806f)): ByteArray {
        val b = ByteBuffer.allocate(93).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, seq.toInt()); b.putInt(4, t.toInt()); b.putShort(8, subsec.toShort())
        b.put(14, hr.toByte()); b.put(15, rr.size.toByte())
        rr.forEachIndexed { i, v -> b.putShort(16 + 2 * i, v.toShort()) }
        if (accel != null) { b.putFloat(33, accel[0]); b.putFloat(37, accel[1]); b.putFloat(41, accel[2]) }
        else b.putFloat(33, 500f)   // out of range → no accel
        return Framing.frame(PacketType.HISTORICAL_DATA, 0, 0, b.array())
    }

    private fun event(type: Int, clock: Long, payload: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(9 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0, 0); b.putInt(1, clock.toInt()); b.putShort(5, 7); b.putShort(7, payload.size.toShort())
        payload.copyInto(b.array(), 9)
        return Framing.frame(PacketType.EVENT, 0, type, b.array())
    }

    private fun battery(clock: Long, tenths: Int, tempCenti: Int): ByteArray {
        val p = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
        p.put(0, 2); p.putInt(1, tenths); p.putShort(5, tempCenti.toShort())
        return event(3, clock, p.array())
    }

    private fun batch() = listOf(
        record(1, ts, 100, 60, listOf(993)),
        record(2, ts, 200, 61, listOf(800, 810)),            // same second, later sub-second
        record(3, ts + 1, 50, 62, accel = null),
        record(4, 32154354, 0, 60),                          // invalid RTC timestamp → rejected, raw kept
        battery(1000, 313, 3805),
        event(9, 1001, ByteArray(0)),                        // wrist_on
        Framing.frame(PacketType.METADATA, 0, 2, ByteArray(18)),  // HISTORY_END: archived raw only
    )

    // ---------------------------------------------------------------- tests

    @Test fun ingestStoresSamplesRrEventsAndRaw() = runTest {
        val r = ingest.ingestFrames(dev, batch(), receivedAtUtcMs = 5_000)
        assertEquals(7, r.frames)
        assertEquals(7, r.newRawPackets)
        assertEquals(3, r.records)
        assertEquals(1, r.rejectedRecords)
        assertEquals(3, r.newSamples)
        assertEquals(3, r.newRr)
        assertEquals(2, r.newEvents)

        val s = db.samples().samplesBetween(dev, 0, Long.MAX_VALUE)
        assertEquals(listOf(60, 61, 62), s.map { it.hr })
        assertEquals(ts * 1000, s[0].tsUtcMs)
        assertEquals(listOf(1L, 2L, 3L), s.map { it.bandSequence })
        assertNull(s[2].accelX)

        val ev = db.events().all(dev)
        assertEquals(listOf("battery", "wrist_on"), ev.map { it.name })
        assertEquals(31.3, ev[0].batteryPct!!, 1e-9)
        assertEquals(38.05, ev[0].skinTempC!!, 1e-9)
        assertEquals(31.3, db.devices().get(dev)!!.lastBatteryPct!!, 1e-9)
    }

    @Test fun reSyncingTheSameDataAddsNothing() = runTest {
        ingest.ingestFrames(dev, batch(), 5_000)
        val again = ingest.ingestFrames(dev, batch(), 9_000)   // e.g. the band re-sent an un-ACKed batch
        assertEquals(0, again.newRawPackets)
        assertEquals(0, again.newSamples)
        assertEquals(0, again.newRr)
        assertEquals(0, again.newEvents)
        assertEquals(3L, db.samples().count(dev, SampleSource.HISTORICAL))
        assertEquals(7L, db.rawPackets().count(dev))
    }

    @Test fun differentDevicesDoNotCollide() = runTest {
        ingest.ingestFrames(dev, batch(), 5_000)
        val other = ingest.ingestFrames("11:22:33:44:55:66", batch(), 5_000)
        assertEquals(3, other.newSamples)
    }

    @Test fun rawArchiveCanBeTurnedOff() = runTest {
        val noRaw = IngestRepository(db, keepRawPackets = { false })
        val r = noRaw.ingestFrames(dev, batch(), 5_000)
        assertEquals(0, r.newRawPackets)
        assertEquals(3, r.newSamples)
    }

    @Test fun liveSamplesAreStoredWithRr() = runTest {
        assertTrue(ingest.ingestLive(dev, HeartRateMeasurement(62, listOf(1049)), 10_000))
        assertFalse("duplicate notification time", ingest.ingestLive(dev, HeartRateMeasurement(62, listOf(1049)), 10_000))
        assertFalse("0 bpm is not a reading", ingest.ingestLive(dev, HeartRateMeasurement(0, emptyList()), 11_000))
        assertEquals(1L, db.samples().count(dev, SampleSource.LIVE))
        val rr = db.samples().rrBetween(dev, 0, Long.MAX_VALUE)
        assertEquals(1024.4, rr.single().rrMs, 0.05)
    }

    @Test fun interruptedSyncRunsAreMarkedPartial() = runTest {
        val id = db.syncRuns().insert(SyncRunEntity(deviceAddress = dev, startedAtUtcMs = 1, trigger = "manual", readOnly = false))
        assertEquals(1, db.syncRuns().markInterrupted(nowUtcMs = 99))
        val run = db.syncRuns().get(id)!!
        assertEquals(SyncOutcome.PARTIAL, run.outcome)
        assertEquals(99L, run.endedAtUtcMs)
    }

    @Test fun exportMatchesDrainPyFormat() = runTest {
        ingest.ingestFrames(dev, batch(), 5_000)
        val sb = StringBuilder()
        assertEquals(3L, JsonlExporter(db).exportHr(dev, sb))
        // Expected lines written by hand from drain.py's write_record() rules.
        assertEquals(
            listOf(
                """{"ts": "2026-05-11T15:31:04.000+00:00", "hr": 60, "rr_ms": [993], "accel": [0.5319, 0.3221, 0.806]}""",
                """{"ts": "2026-05-11T15:31:04.001+00:00", "hr": 61, "rr_ms": [800, 810], "accel": [0.5319, 0.3221, 0.806]}""",
                """{"ts": "2026-05-11T15:31:05.000+00:00", "hr": 62}""",
            ),
            sb.toString().trimEnd().lines(),
        )
    }

    @Test fun pythonStyleRounding() {
        assertEquals("1.0", JsonlExporter.pyRound4(1.0f))
        assertEquals("0.1235", JsonlExporter.pyRound4(0.123456f))
        assertEquals("-0.9876", JsonlExporter.pyRound4(-0.98765f))   // float32 value is just below the half
        assertEquals("-0.0", JsonlExporter.pyRound4(-0.00001f))
    }

    @Test fun ingestIsAtomic() = runTest {
        // A transaction that fails part-way must leave nothing behind (commit-before-ACK depends on it).
        runCatching {
            db.withTransaction<Unit> {
                db.samples().insertSamples(listOf(HrSampleEntity(deviceAddress = dev, source = 0, tsUtcMs = 1, subKey = 0, hr = 60)))
                error("simulated crash mid-batch")
            }
        }
        assertEquals(0L, db.samples().count(dev, SampleSource.HISTORICAL))
    }

    @Test fun migratesFromPhase2Schema() {
        // Full path: under Robolectric on Windows the helper's driver otherwise mismatches a bare name.
        val name = ApplicationProvider.getApplicationContext<Context>().getDatabasePath("migration-test.db").absolutePath
        migrations.createDatabase(name, 1).apply {
            execSQL("INSERT INTO device (address, name, lastSeenUtcMs) VALUES ('$dev', 'WHOOP', 123)")
            close()
        }
        val v2 = migrations.runMigrationsAndValidate(name, 2, true)
        v2.query("SELECT name, lastSeenUtcMs, lastBatteryPct FROM device").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("WHOOP", c.getString(0))
            assertEquals(123L, c.getLong(1))
            assertTrue(c.isNull(2))
        }
        v2.close()
    }

    @Test fun commandFrameStillBuilds() {
        // Sanity: the data module sees the protocol module's command builders (used by the Phase 5 sync engine).
        assertEquals(20, Commands.historicalDataResult(1).size)
    }
}
