package dev.whoop4.bloop.analytics

import kotlin.math.exp
import kotlin.math.pow

/** Maximum heart rate and where it came from. */
data class HrMax(val bpm: Double, val source: String, val confidence: Double)

object HeartRateMax {
    /** Tanaka, Monahan & Seals (2001): HRmax = 208 − 0.7 × age. */
    fun tanaka(age: Int) = HrMax(208 - 0.7 * age, "Tanaka formula (age $age)", 0.8)
}

object Zones {
    /** Lower bound of zone k (1..5) as a fraction of max HR (or of HRR for [zone]); zone 5 ends at max. */
    fun lowerFraction(k: Int) = 0.4 + 0.1 * k

    /**
     * Display zones, as % of maximum HR (the usual watch/app convention): zone k covers 50+10(k−1) % to
     * 60+10(k−1) % of HRmax. These are what the Strain screen shows as time in zones.
     */
    fun ofMax(hr: Int, max: Double): Int {
        if (max <= 0) return 0
        val f = hr / max
        return when {
            f < 0.5 -> 0
            f < 0.6 -> 1
            f < 0.7 -> 2
            f < 0.8 -> 3
            f < 0.9 -> 4
            else -> 5
        }
    }

    /** User-defined zones: [lower] holds the lower bound in bpm of zones 1..5, ascending. */
    fun ofBounds(hr: Int, lower: List<Int>): Int = lower.indexOfLast { hr >= it } + 1

    /** Karvonen heart-rate-reserve zones (not used for the score since v3): zone k covers 50+10(k−1) % to 60+10(k−1) % of HRR. */
    fun zone(hr: Int, rest: Double, max: Double): Int {
        if (max <= rest) return 0
        val f = (hr - rest) / (max - rest)
        return when {
            f < 0.5 -> 0
            f < 0.6 -> 1
            f < 0.7 -> 2
            f < 0.8 -> 3
            f < 0.9 -> 4
            else -> 5
        }
    }
}

data class StrainResult(
    /** Bloop's 0–21 scale, tuned to read like WHOOP's day strain (see [Strain]). */
    val score: Double,
    /** Banister TRIMP: Σ minutes × HRR × 0.64·e^(1.92·HRR), for HRR ≥ [Strain.HRR_FLOOR]. */
    val trimp: Double,
    /** Minutes in the display zones 1..5 (index 0 = below zone 1): % of max HR, or the custom bpm zones. */
    val zoneMinutes: List<Double>,
    val coverage: Double,
    /** Minutes of the period that have heart-rate data. */
    val minutesWithData: Double = 0.0,
    /** Timestamp of the newest sample used. */
    val lastSampleMs: Long = 0,
    val restBpm: Double = 0.0,
    val maxBpm: Double = 0.0,
)

/**
 * Daily cardiovascular load (v3). Each second is weighted continuously by heart-rate reserve
 * x = (HR − rest) / (max − rest) with Banister's TRIMP weighting x · 0.64 · e^(1.92·x), so harder minutes count
 * exponentially more; seconds below [HRR_FLOOR] (sitting, standing) add nothing. The total is mapped to 0–21 with
 * a saturating curve, 21 · T^p / (T^p + C^p), fitted to typical WHOOP day strains:
 * an ordinary day with some walking (T ≈ 30) ≈ 6, plus a 1 h easy run (T ≈ 120) ≈ 13.5, plus a hard hour
 * (T ≈ 175) ≈ 15.5, and several hard hours ≈ 19+. Each sample counts for the time until the next one, at most
 * [MAX_DT_MS], so gaps add nothing. Custom bpm zones change only the displayed time in zones, not the score.
 *
 * v2 (Edwards TRIMP on zones, 21·(1 − e^(−T/250))) read far below WHOOP: nothing under zone 1 counted, and an
 * hour in zone 2 scored only ≈ 8.
 */
object Strain {
    const val METHOD = "banister-trimp-bloop21-v3"
    const val HRR_FLOOR = 0.3
    const val SCALE_C = 70.0
    const val SCALE_P = 1.1
    const val MAX_DT_MS = 5_000L
    /** Resting HR assumed when none is known yet (lowers confidence). */
    const val DEFAULT_REST = 60.0

    fun scale(trimp: Double): Double {
        if (trimp <= 0) return 0.0
        val a = trimp.pow(SCALE_P)
        return 21 * a / (a + SCALE_C.pow(SCALE_P))
    }

    /** TRIMP per minute at heart-rate-reserve fraction [x]. */
    fun weight(x: Double): Double = if (x < HRR_FLOOR) 0.0 else x.coerceAtMost(1.1) * 0.64 * exp(1.92 * x.coerceAtMost(1.1))

    /**
     * With [customZones] (bpm lower bounds of zones 1..5) the displayed minutes use those zones. The score always
     * uses heart-rate reserve; without a known max, custom zones imply one (zone 5 starts at 90 % of max), and
     * without a resting HR [DEFAULT_REST] is assumed.
     */
    fun of(hr: List<HrPoint>, fromMs: Long, toMs: Long, rest: Metric<Double>, max: HrMax?, customZones: List<Int>? = null): Metric<StrainResult> {
        val custom = customZones?.takeIf { it.size == 5 }
        var conf = 1.0
        val r = rest.valueOrNull ?: if (custom != null) DEFAULT_REST.also { conf *= 0.6 } else return Metric.Missing("needs a resting heart rate (one night of sleep data)")
        val m = max ?: if (custom != null) HrMax(custom[4] / 0.9, "from your custom zones", 0.7) else return Metric.Missing("needs your age, or 3 days of data to estimate your maximum heart rate")
        if (m.bpm <= r + 20) return Metric.Missing("maximum heart rate (%.0f) is not above resting (%.0f)".format(m.bpm, r))
        val pts = hr.filter { it.tsMs in fromMs until toMs && it.hr in 25..240 }.sortedBy { it.tsMs }
        if (pts.isEmpty()) return Metric.Missing("no heart rate in this period")
        val minutes = DoubleArray(6)
        var trimp = 0.0
        var covered = 0L
        for (i in pts.indices) {
            val next = if (i + 1 < pts.size) pts[i + 1].tsMs else pts[i].tsMs + 1000
            val dt = (next - pts[i].tsMs).coerceIn(0, MAX_DT_MS)
            covered += dt
            val dtMin = dt / 60_000.0
            val z = if (custom != null) Zones.ofBounds(pts[i].hr, custom) else Zones.ofMax(pts[i].hr, m.bpm)
            minutes[z] += dtMin
            trimp += dtMin * weight((pts[i].hr - r) / (m.bpm - r))
        }
        val coverage = covered.toDouble() / (toMs - fromMs)
        val confidence = coverage.coerceAtMost(1.0) * conf * m.confidence * rest.confidenceOrZero.coerceAtLeast(0.5)
        return Metric.Estimated(StrainResult(scale(trimp), trimp, minutes.toList(), coverage,
            covered / 60_000.0, pts.last().tsMs, r, m.bpm), METHOD, confidence.coerceIn(0.0, 1.0))
    }
}
