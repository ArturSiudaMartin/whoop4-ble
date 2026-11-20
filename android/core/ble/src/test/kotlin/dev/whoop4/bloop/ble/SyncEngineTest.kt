package dev.whoop4.bloop.ble

import dev.whoop4.bloop.protocol.CommandNumber
import dev.whoop4.bloop.protocol.Framing
import dev.whoop4.bloop.protocol.HistoricalParser
import dev.whoop4.bloop.protocol.MetadataType
import dev.whoop4.bloop.protocol.Packet
import dev.whoop4.bloop.protocol.PacketType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tests the drain state machine against a simulated band that follows the documented protocol:
 * it streams one batch per SEND_HISTORICAL_DATA, ends it with HISTORY_END(trim), moves to the next batch
 * only when that trim is ACKed, re-sends un-ACKed data after a reconnect, and ends with HISTORY_COMPLETE.
 */
class SyncEngineTest {
    private val t0 = 1778513464L

    private fun record(seq: Int, ts: Long, hr: Int = 60): ByteArray {
        val b = ByteBuffer.allocate(93).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, seq); b.putInt(4, ts.toInt()); b.putShort(8, (seq % 30000).toShort()); b.put(14, hr.toByte())
        return Framing.frame(PacketType.HISTORICAL_DATA, 0, 0, b.array())
    }

    private fun metadata(kind: Int, trim: Long = 0): ByteArray {
        val b = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN); b.putInt(10, trim.toInt())
        return Framing.frame(PacketType.METADATA, 0, kind, b.array())
    }

    private fun clockResponse(rtc: Long): ByteArray {
        val b = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN); b.put(1, 1); b.putInt(2, rtc.toInt())
        return Framing.frame(PacketType.COMMAND_RESPONSE, 0, CommandNumber.GET_CLOCK, b.array())
    }

    private fun console(text: String) = Framing.frame(PacketType.CONSOLE_LOGS, 0, 0, ByteArray(11) + text.toByteArray())

    /** In-memory store that dedups like Room does (by record timestamp + sub-second). */
    class FakeStore : SyncStore {
        val keys = HashSet<Pair<Long, Int>>()
        var batchesStored = 0
        var hwRtc: Long? = null
        val runs = HashMap<Long, SyncProgress>()
        override suspend fun startRun(readOnly: Boolean, trigger: String) = (runs.size + 1L).also { runs[it] = SyncProgress(it) }
        override suspend fun storeBatch(runId: Long, frames: List<ByteArray>): Int {
            batchesStored++
            return frames.count { f ->
                val p = Packet.parse(f)
                p != null && p.type == PacketType.HISTORICAL_DATA &&
                    HistoricalParser.parse(p.data)?.let { keys.add(it.timestamp to it.subsec) } == true
            }
        }
        override suspend fun updateRun(runId: Long, p: SyncProgress) { runs[runId] = p }
        override suspend fun lastHwRtc() = hwRtc
        override suspend fun saveHwRtc(hwRtc: Long, atUtcMs: Long) { this.hwRtc = hwRtc }
    }

    inner class FakeBand(
        private val scope: CoroutineScope,
        private val store: FakeStore,
        batches: List<List<ByteArray>>,
        var rtc: Long = t0,
        /** Drop the connection after this many frames of the first transmit (simulates walking out of range). */
        private var dropAfterFrames: Int = -1,
        private val consoleLines: List<String> = emptyList(),
    ) : BandLink {
        private val remaining = ArrayDeque(batches)
        private val flow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 100_000)
        override val frames: SharedFlow<ByteArray> = flow
        override var isConnected = false
        val written = ArrayList<Int>()            // command numbers in order
        val acked = ArrayList<Long>()
        var connects = 0
        private var currentTrim = 0L
        private var trimCounter = 100L

        override suspend fun connect() { isConnected = true; connects++ }
        override suspend fun disconnect() { isConnected = false }

        override suspend fun write(frame: ByteArray) {
            check(isConnected) { "write while disconnected" }
            val p = Packet.parse(frame)!!
            written.add(p.cmd)
            when (p.cmd) {
                CommandNumber.GET_CLOCK -> flow.tryEmit(clockResponse(rtc))
                CommandNumber.SEND_HISTORICAL_DATA -> scope.launch { transmit() }
                CommandNumber.HISTORICAL_DATA_RESULT -> {
                    val trim = ByteBuffer.wrap(p.data).order(ByteOrder.LITTLE_ENDIAN).getInt(1).toLong() and 0xFFFFFFFFL
                    // The invariant that protects the user's data: the batch must already be stored.
                    val batch = remaining.first()
                    val stored = batch.mapNotNull { HistoricalParser.parse(Packet.parse(it)!!.data) }.all { (it.timestamp to it.subsec) in store.keys }
                    assertTrue("ACK for trim $trim arrived before the batch was stored", stored)
                    assertEquals(currentTrim, trim)
                    acked.add(trim)
                    remaining.removeFirst()   // the band may now discard it
                    scope.launch { transmit() }
                }
            }
        }

        private suspend fun transmit() {
            delay(50)
            for (l in consoleLines) flow.tryEmit(console(l + "\n"))
            val batch = remaining.firstOrNull()
            if (batch == null) { flow.tryEmit(metadata(MetadataType.HISTORY_COMPLETE)); return }
            batch.forEachIndexed { i, f ->
                if (!isConnected) return
                if (dropAfterFrames == i) { isConnected = false; dropAfterFrames = -1; return }
                flow.tryEmit(f); delay(1)
            }
            currentTrim = ++trimCounter
            flow.tryEmit(metadata(MetadataType.HISTORY_END, currentTrim))
        }
    }

    private fun batches(n: Int, size: Int) = List(n) { b -> List(size) { i -> record(b * size + i, t0 + b * size + i) } }

    private fun TestScope.engine(band: BandLink, store: SyncStore, config: SyncConfig = SyncConfig()) =
        SyncEngine(band, store, DebugLog(), config) { testScheduler.currentTime }

    @Test fun fullSyncAcksEveryBatchAfterStoringAndCompletes() = runTest {
        val store = FakeStore()
        val band = FakeBand(backgroundScope, store, batches(5, 40))
        val p = engine(band, store).run(readOnly = false, trigger = "test")
        assertEquals("COMPLETE", p.outcome)
        assertTrue(p.complete)
        assertEquals(200L, p.newSamples)
        assertEquals(5, p.batches)
        assertEquals(listOf(101L, 102L, 103L, 104L, 105L), band.acked)
        // Every pass starts with the 5-byte SET_CLOCK, then SEND_HISTORICAL_DATA, then GET_CLOCK.
        assertEquals(listOf(CommandNumber.SET_CLOCK, CommandNumber.SEND_HISTORICAL_DATA, CommandNumber.GET_CLOCK), band.written.take(3))
    }

    @Test fun readOnlyNeverAcksAndStopsAfterOnePass() = runTest {
        val store = FakeStore()
        val band = FakeBand(backgroundScope, store, batches(3, 30))
        val p = engine(band, store).run(readOnly = true, trigger = "test")
        assertFalse(CommandNumber.HISTORICAL_DATA_RESULT in band.written)
        assertEquals(1, p.pass)
        assertEquals(30L, p.newSamples)          // first batch stored…
        assertEquals("PARTIAL", p.outcome)      // …but nothing freed on the band
        assertTrue(band.acked.isEmpty())
    }

    @Test fun droppedConnectionResumesWithoutDuplicates() = runTest {
        val store = FakeStore()
        val band = FakeBand(backgroundScope, store, batches(3, 50), dropAfterFrames = 20)
        val p = engine(band, store).run(readOnly = false, trigger = "test")
        assertEquals("COMPLETE", p.outcome)
        assertEquals(150L, p.newSamples)                 // 20 partial + 30 re-sent remainder + 100: no duplicates
        assertEquals(150, store.keys.size)
        assertTrue("reconnected for another pass", band.connects >= 2)
        assertEquals(3, band.acked.size)
    }

    @Test fun invalidRtcIsDetectedFromClockAndConsole() = runTest {
        val store = FakeStore()
        val band = FakeBand(backgroundScope, store, batches(1, 5), rtc = 32154354,
            consoleLines = listOf("E, 1: Flash: RTC timestamp 32154354 is invalid; not saving data to flash."))
        val log = DebugLog()
        val p = SyncEngine(band, store, log) { testScheduler.currentTime }.run(readOnly = false, trigger = "test")
        assertTrue(p.rtcInvalid)
        assertEquals(32154354L, p.hwRtc)
        assertTrue(log.lines.value.any { "INVALID RTC" in it })
        assertEquals(null, store.hwRtc)   // an invalid clock is not saved as the reboot-detection baseline
    }

    @Test fun rebootIsDetectedWhenTheBandClockGoesBackwards() = runTest {
        val store = FakeStore().apply { hwRtc = t0 + 10_000 }
        val band = FakeBand(backgroundScope, store, batches(1, 5), rtc = t0)
        val p = engine(band, store).run(readOnly = false, trigger = "test")
        assertTrue(p.rebootDetected)
    }

    @Test fun stopAbortsTheTransmit() = runTest {
        val store = FakeStore()
        val band = FakeBand(backgroundScope, store, batches(50, 200))
        val e = engine(band, store)
        backgroundScope.launch { delay(1_500); e.requestStop() }
        val p = e.run(readOnly = false, trigger = "test")
        assertEquals("STOPPED", p.outcome)
        assertTrue(CommandNumber.ABORT_HISTORICAL_TRANSMITS in band.written)
    }

    @Test fun emptyBandFinishesWithoutLooping() = runTest {
        val store = FakeStore()
        val band = FakeBand(backgroundScope, store, emptyList())
        val p = engine(band, store).run(readOnly = false, trigger = "test")
        assertEquals("COMPLETE", p.outcome)
        assertEquals(0L, p.newSamples)
        assertEquals(1, p.pass)
    }
}
