package dev.whoop4.bloop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val MIN = 60_000L
private const val HOUR = 60 * MIN

/**
 * A synthetic 1 Hz night with known truth: awake and moving 18:00–23:00, asleep 23:00–07:00 (one 20-minute
 * awakening at 03:00), awake 07:00–14:00. During sleep HR settles to [sleepHr] and RR carries a ±[rsaMs]
 * respiratory oscillation, so RMSSD is known in closed form.
 */
private class SyntheticNight(
    val sleepHr: Int = 52,
    val rsaMs: Double = 40.0,
    seed: Int = 1,
    val artefactEvery: Int = 0,
    val wristOffFrom: Long? = null,
    val wristOffTo: Long? = null,
    /** Drop records for [dropMinutes] minutes out of every [dropEvery] minutes (band skipping records). */
    val dropEvery: Int = 0,
    val dropMinutes: Int = 0,
) {
    val from = 0L
    val to = 20 * HOUR
    val sleepStart = 5 * HOUR
    val sleepEnd = 13 * HOUR
    val wakeStart = 9 * HOUR
    val wakeEnd = 9 * HOUR + 20 * MIN
    val hr = ArrayList<HrPoint>()
    val rr = ArrayList<RrPoint>()

    init {
        val rnd = Random(seed)
        var beat = 0
        var t = from
        // Sleeping posture changes now and then without waking (angle jumps, then still again).
        var still = Triple(0.2f, -0.3f, 0.93f)
        while (t < to) {
            val asleep = t in sleepStart until sleepEnd && t !in wakeStart until wakeEnd
            val dropped = dropEvery > 0 && (t / MIN) % dropEvery < dropMinutes
            val off = (wristOffFrom != null && t in wristOffFrom until wristOffTo!!) || dropped
            if (!off) {
                val h: Int
                val acc: Triple<Float, Float, Float>
                if (asleep) {
                    if (rnd.nextDouble() < 1.0 / 3600) still = randomOrientation(rnd)
                    acc = Triple(still.first + noise(rnd), still.second + noise(rnd), still.third + noise(rnd))
                    h = sleepHr + rnd.nextInt(0, 3)
                } else {
                    acc = randomOrientation(rnd)
                    h = 75 + rnd.nextInt(0, 15)
                }
                hr += HrPoint(t, h, acc.first, acc.second, acc.third)
                if (asleep) {
                    // One beat per second-ish record: RR = mean ± RSA (alternating sign → successive diff = 2·RSA·|sin|).
                    val base = 60_000.0 / sleepHr
                    beat++
                    var v = base + rsaMs * (if (beat % 2 == 0) 1 else -1)
                    if (artefactEvery > 0 && beat % artefactEvery == 0) v = base * 0.5   // missed/extra beat
                    rr += RrPoint(t, v)
                }
            }
            t += 1000
        }
    }

    private fun noise(r: Random) = ((r.nextDouble() - 0.5) * 0.004).toFloat()
    private fun randomOrientation(r: Random): Triple<Float, Float, Float> {
        val a = r.nextDouble() * Math.PI; val b = r.nextDouble() * 2 * Math.PI
        return Triple((sin(a) * cos(b)).toFloat(), (sin(a) * sin(b)).toFloat(), cos(a).toFloat())
    }
}

class RrCleaningTest {
    @Test fun removesOutOfRangeAndJumps() {
        val rr = listOf(1000.0, 1010.0, 250.0, 990.0, 1500.0, 1005.0, 2500.0, 995.0).mapIndexed { i, v -> RrPoint(i * 1000L, v) }
        val c = RrCleaner.clean(rr)
        assertEquals(5, c.kept)
        assertEquals(3.0 / 8, c.artefactFraction, 1e-9)
        assertTrue("a rejected beat ends the run", c.segments.size >= 2)
    }

    @Test fun gapEndsRun() {
        val rr = listOf(RrPoint(0, 1000.0), RrPoint(1000, 1000.0), RrPoint(10_000, 1000.0), RrPoint(11_000, 1000.0))
        assertEquals(2, RrCleaner.clean(rr).segments.size)
    }

    @Test fun recoversFromEctopicFirstBeat() {
        val rr = (listOf(600.0) + List(20) { 1000.0 }).mapIndexed { i, v -> RrPoint(i * 1000L, v) }
        assertTrue(RrCleaner.clean(rr).kept >= 17)
    }
}

class HrvTest {
    @Test fun rmssdClosedForm() {
        // Alternating ±40 ms → every successive difference is 80 ms → RMSSD = 80.
        val n = SyntheticNight(rsaMs = 40.0)
        val h = Hrv.nightly(n.rr, n.sleepStart, n.sleepEnd) as Metric.Derived
        assertEquals(80.0, h.value.rmssdMs, 1e-6)
        assertEquals(ln(80.0), h.value.lnRmssd, 1e-9)
        assertTrue(h.confidence > 0.9)
    }

    @Test fun artefactsAreFilteredAndLowerConfidence() {
        val n = SyntheticNight(rsaMs = 40.0, artefactEvery = 50)
        val h = Hrv.nightly(n.rr, n.sleepStart, n.sleepEnd) as Metric.Derived
        assertEquals("the halved beats must not inflate RMSSD", 80.0, h.value.rmssdMs, 1e-6)
        assertTrue(h.value.artefactFraction > 0.015)
        assertTrue(h.confidence < 0.99)
    }

    @Test fun missingWithoutRr() = assertTrue(Hrv.nightly(emptyList(), 0, HOUR) is Metric.Missing)
}

class RestingHrTest {
    @Test fun lowestFiveMinuteMean() {
        val pts = (0 until 3600).map { s -> HrPoint(s * 1000L, if (s in 1200 until 1800) 48 else 60) }
        val r = RestingHr.during(pts, 0, HOUR) as Metric.Derived
        assertEquals(48.0, r.value.bpm, 1e-9)
    }

    @Test fun needsCoverage() {
        val pts = (0 until 3600 step 10).map { s -> HrPoint(s * 1000L, 50) }   // 10 % coverage
        assertTrue(RestingHr.during(pts, 0, HOUR) is Metric.Missing)
    }
}

class SleepTest {
    @Test fun findsTheSleepPeriod() {
        val n = SyntheticNight()
        val s = (SleepDetector.detect(n.hr, n.from, n.to) as Metric.Estimated).value
        assertTrue("onset within 15 min, was ${(s.startMs - n.sleepStart) / MIN} min off", abs(s.startMs - n.sleepStart) <= 15 * MIN)
        assertTrue("wake within 15 min, was ${(s.endMs - n.sleepEnd) / MIN} min off", abs(s.endMs - n.sleepEnd) <= 15 * MIN)
        val trueAsleep = (n.sleepEnd - n.sleepStart) - (n.wakeEnd - n.wakeStart)
        assertTrue("asleep ${s.asleepMs / MIN} min vs true ${trueAsleep / MIN}", abs(s.asleepMs - trueAsleep) <= 30 * MIN)
        assertTrue("the 03:00 awakening is detected", s.segments.any { it.state == SleepState.WAKE && it.startMs < n.wakeEnd && it.endMs > n.wakeStart })
        assertTrue(s.coverage > 0.99)
    }

    @Test fun survivesRegularDataGaps() {
        // 4 minutes missing out of every 20 (20 % of the night): the night must not fall apart into short pieces.
        val n = SyntheticNight(dropEvery = 20, dropMinutes = 4)
        val s = (SleepDetector.detect(n.hr, n.from, n.to) as Metric.Estimated).value
        assertTrue("onset ${(s.startMs - n.sleepStart) / MIN} min off", abs(s.startMs - n.sleepStart) <= 25 * MIN)
        assertTrue("wake ${(s.endMs - n.sleepEnd) / MIN} min off", abs(s.endMs - n.sleepEnd) <= 25 * MIN)
        val trueAsleep = (n.sleepEnd - n.sleepStart) - (n.wakeEnd - n.wakeStart)
        assertTrue("asleep ${s.asleepMs / MIN} min vs ${trueAsleep / MIN}", abs(s.asleepMs - trueAsleep) <= 40 * MIN)
    }

    @Test fun nightCutShortByMissingDownload() {
        // Only the first 2 h of sleep downloaded so far: a short sleep, not a full night (the UI flags it as incomplete).
        val n = SyntheticNight(wristOffFrom = 7 * HOUR, wristOffTo = 20 * HOUR)
        val s = SleepDetector.detect(n.hr, n.from, n.to)
        assertTrue(s is Metric.Missing || (s.valueOrNull!!.endMs <= 7 * HOUR))
    }

    @Test fun wristOffIsNotSleep() {
        // Band off the wrist all night: no samples, so no sleep (never "perfectly still").
        val n = SyntheticNight(wristOffFrom = 4 * HOUR, wristOffTo = 14 * HOUR)
        assertTrue(SleepDetector.detect(n.hr, n.from, n.to) is Metric.Missing)
    }

    @Test fun noDataIsMissing() = assertTrue(SleepDetector.detect(emptyList(), 0, 20 * HOUR) is Metric.Missing)

    @Test fun performance() {
        val n = SyntheticNight()
        val p = SleepPerformance.of(SleepDetector.detect(n.hr, n.from, n.to), 8 * HOUR) as Metric.Estimated
        assertTrue(p.value in 0.85..1.0)
    }
}

class NightAnalyzerTest {
    @Test fun endToEnd() {
        val n = SyntheticNight(sleepHr = 50)
        val a = NightAnalyzer.analyze(n.hr, n.rr, n.from, n.to, 8 * HOUR)
        assertEquals(80.0, a.hrv.valueOrNull!!.rmssdMs, 1.0)
        assertTrue(a.restingHr.valueOrNull!!.bpm in 50.0..52.0)
    }
}

class StrainTest {
    private val max = HrMax(190.0, "test", 1.0)

    @Test fun zones() {
        assertEquals(0, Zones.zone(100, 60.0, 190.0))   // 30.8 % HRR
        assertEquals(1, Zones.zone(130, 60.0, 190.0))   // 53.8 %
        assertEquals(3, Zones.zone(160, 60.0, 190.0))   // 76.9 %
        assertEquals(5, Zones.zone(185, 60.0, 190.0))
    }

    @Test fun oneHourZoneThree() {
        val pts = (0 until 3600).map { HrPoint(it * 1000L, 160) }
        val s = Strain.of(pts, 0, HOUR, Metric.Derived(60.0, "t", 1.0), max) as Metric.Estimated
        assertEquals(180.0, s.value.trimp, 0.1)
        assertEquals(21 * (1 - kotlin.math.exp(-180.0 / 250)), s.value.score, 1e-6)
    }

    @Test fun gapsAddNothing() {
        val pts = listOf(HrPoint(0, 160), HrPoint(HOUR, 160))
        val s = Strain.of(pts, 0, 2 * HOUR, Metric.Derived(60.0, "t", 1.0), max) as Metric.Estimated
        assertEquals("5 s + 1 s in zone 3", 0.3, s.value.trimp, 1e-9)
    }

    @Test fun scaleIsBounded() {
        assertEquals(0.0, Strain.scale(0.0), 1e-9)
        assertTrue(Strain.scale(1e6) <= 21.0)
    }

    @Test fun missingInputs() {
        assertTrue(Strain.of(emptyList(), 0, HOUR, Metric.Missing("x"), max) is Metric.Missing)
        assertTrue(Strain.of(emptyList(), 0, HOUR, Metric.Derived(60.0, "t", 1.0), null) is Metric.Missing)
    }

    @Test fun tanaka() = assertEquals(187.0, HeartRateMax.tanaka(30).bpm, 1e-9)
}

class RecoveryTest {
    private val history = (1L..10L).map { NightValues(it, ln(60.0) + if (it % 2 == 0L) 0.1 else -0.1, 55.0 + (it % 3), 0.9) }

    @Test fun calibratingUntilFourNights() {
        val r = Recovery.of(NightValues(4, ln(60.0), 55.0, 0.9), history.take(3), 1.0)
        assertTrue((r as Metric.Missing).reason.startsWith("calibrating: 3 of 4"))
    }

    @Test fun typicalNightIsAroundFifty() {
        val r = Recovery.of(NightValues(11, ln(60.0), 56.0, 0.85), history, 1.0) as Metric.Estimated
        assertEquals(50.0, r.value.score, 8.0)
    }

    @Test fun higherHrvLowerRhrIsBetter() {
        val good = Recovery.of(NightValues(11, ln(80.0), 50.0, 0.95), history, 1.0).valueOrNull!!.score
        val bad = Recovery.of(NightValues(11, ln(40.0), 63.0, 0.6), history, 1.0).valueOrNull!!.score
        assertTrue("good $good > 80", good > 80)
        assertTrue("bad $bad < 20", bad < 20)
    }

    @Test fun usesOnlyThePreviousFourWeeks() {
        val old = (1L..10L).map { NightValues(it, ln(30.0), 70.0, 0.9) }
        assertTrue(Recovery.of(NightValues(100, ln(60.0), 55.0, 0.9), old, 1.0) is Metric.Missing)
    }

    @Test fun normalCdf() {
        assertEquals(0.5, Recovery.normalCdf(0.0), 1e-7)
        assertEquals(0.841345, Recovery.normalCdf(1.0), 1e-5)
        assertEquals(0.022750, Recovery.normalCdf(-2.0), 1e-5)
    }

    @Test fun missingTonight() {
        assertNull(Recovery.of(NightValues(11, null, 55.0, 0.9), history, 1.0).valueOrNull)
        assertTrue(sqrt(4.0) == 2.0)
    }
}
