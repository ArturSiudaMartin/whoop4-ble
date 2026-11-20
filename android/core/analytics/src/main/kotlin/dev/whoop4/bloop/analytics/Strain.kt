package dev.whoop4.bloop.analytics

import kotlin.math.exp

/** Maximum heart rate and where it came from. */
data class HrMax(val bpm: Double, val source: String, val confidence: Double)

object HeartRateMax {
    /** Tanaka, Monahan & Seals (2001): HRmax = 208 − 0.7 × age. */
    fun tanaka(age: Int) = HrMax(208 - 0.7 * age, "Tanaka formula (age $age)", 0.8)
}

/** Karvonen heart-rate-reserve zones: zone k (1..5) covers 50+10(k−1) % to 60+10(k−1) % of HRR. */
object Zones {
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
    /** Bloop's own 0–21 scale (not comparable with any other app's). */
    val score: Double,
    /** Edwards TRIMP: Σ minutes in zone × zone number. */
    val trimp: Double,
    /** Minutes in zones 1..5 (index 0 = below zone 1). */
    val zoneMinutes: List<Double>,
    val coverage: Double,
)

/**
 * Daily cardiovascular load as Edwards' TRIMP (1993), shown on Bloop's own saturating 0–21 scale:
 * score = 21 × (1 − e^(−TRIMP / 250)). With it, ~30 min in zone 2 scores ≈ 4, an hour in zone 3 ≈ 11,
 * and 2 hours in zone 4 ≈ 18. Each sample counts for the time until the next one, at most [MAX_DT_MS],
 * so gaps in the data add nothing.
 */
object Strain {
    const val METHOD = "edwards-trimp-bloop21-v1"
    const val SCALE_K = 250.0
    const val MAX_DT_MS = 5_000L

    fun scale(trimp: Double) = 21 * (1 - exp(-trimp / SCALE_K))

    fun of(hr: List<HrPoint>, fromMs: Long, toMs: Long, rest: Metric<Double>, max: HrMax?): Metric<StrainResult> {
        val r = rest.valueOrNull ?: return Metric.Missing("needs a resting heart rate (one night of sleep data)")
        val m = max ?: return Metric.Missing("needs your age, or 3 days of data to estimate your maximum heart rate")
        val pts = hr.filter { it.tsMs in fromMs until toMs && it.hr in 25..240 }.sortedBy { it.tsMs }
        if (pts.isEmpty()) return Metric.Missing("no heart rate in this period")
        val minutes = DoubleArray(6)
        var covered = 0L
        for (i in pts.indices) {
            val next = if (i + 1 < pts.size) pts[i + 1].tsMs else pts[i].tsMs + 1000
            val dt = (next - pts[i].tsMs).coerceIn(0, MAX_DT_MS)
            covered += dt
            minutes[Zones.zone(pts[i].hr, r, m.bpm)] += dt / 60_000.0
        }
        val trimp = (1..5).sumOf { it * minutes[it] }
        val coverage = covered.toDouble() / (toMs - fromMs)
        val confidence = (coverage.coerceAtMost(1.0)) * m.confidence * rest.confidenceOrZero.coerceAtLeast(0.5)
        return Metric.Estimated(StrainResult(scale(trimp), trimp, minutes.toList(), coverage), METHOD, confidence.coerceIn(0.0, 1.0))
    }
}
