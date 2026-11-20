package dev.whoop4.bloop.analytics

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/** One past night's inputs for the baseline. Null = that value was missing that night. */
data class NightValues(val dayIndex: Long, val lnRmssd: Double?, val restingHr: Double?, val sleepPerformance: Double?)

data class RecoveryResult(
    /** 1..99 %. */
    val score: Double,
    val hrvZ: Double,
    val rhrZ: Double,
    val sleepZ: Double?,
    val baselineNights: Int,
)

/**
 * Recovery as a comparison with the user's own baseline (Plews et al. 2013; Buchheit 2014): tonight's
 * ln RMSSD and resting HR as z-scores against the previous 28 days (at least [MIN_NIGHTS] valid nights),
 * plus sleep performance. Combined as 0.5·z(HRV) − 0.3·z(RHR) + 0.2·z(sleep), normalised and mapped
 * through the normal CDF to 1–99 %: 50 % means a typical night for this person.
 * Standard deviations have floors (0.10 ln-ms, 2 bpm) so a very stable baseline doesn't exaggerate small changes.
 */
object Recovery {
    const val METHOD = "baseline-z-v1"
    const val MIN_NIGHTS = 4
    const val FULL_CONFIDENCE_NIGHTS = 14
    const val BASELINE_DAYS = 28

    fun of(today: NightValues, history: List<NightValues>, hrvConfidence: Double): Metric<RecoveryResult> {
        val ln = today.lnRmssd ?: return Metric.Missing("no HRV for last night")
        val rhr = today.restingHr ?: return Metric.Missing("no resting heart rate for last night")
        val base = history.filter {
            it.dayIndex < today.dayIndex && it.dayIndex >= today.dayIndex - BASELINE_DAYS && it.lnRmssd != null && it.restingHr != null
        }
        if (base.size < MIN_NIGHTS) return Metric.Missing("calibrating: ${base.size} of $MIN_NIGHTS nights with HRV and resting HR")

        val hrvZ = (ln - mean(base.map { it.lnRmssd!! })) / maxOf(sd(base.map { it.lnRmssd!! }), 0.10)
        val rhrZ = (rhr - mean(base.map { it.restingHr!! })) / maxOf(sd(base.map { it.restingHr!! }), 2.0)
        // Sleep: performance against 85 % (one SD ≈ 15 %), when known.
        val sleepZ = today.sleepPerformance?.let { ((it - 0.85) / 0.15).coerceIn(-3.0, 3.0) }

        val terms = mutableListOf(0.5 to hrvZ.coerceIn(-4.0, 4.0), -0.3 to rhrZ.coerceIn(-4.0, 4.0))
        sleepZ?.let { terms += 0.2 to it }
        val combined = terms.sumOf { it.first * it.second } / sqrt(terms.sumOf { it.first * it.first })
        val score = (100 * normalCdf(combined)).coerceIn(1.0, 99.0)

        val confidence = minOf(1.0, base.size.toDouble() / FULL_CONFIDENCE_NIGHTS) * hrvConfidence
        return Metric.Estimated(RecoveryResult(score, hrvZ, rhrZ, sleepZ, base.size), METHOD, confidence.coerceIn(0.0, 1.0))
    }

    /** Φ(x) via the Abramowitz–Stegun 7.1.26 erf approximation (|error| < 1.5e-7). */
    internal fun normalCdf(x: Double): Double {
        val z = abs(x) / sqrt(2.0)
        val t = 1 / (1 + 0.3275911 * z)
        val erf = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-z * z)
        return if (x >= 0) 0.5 * (1 + erf) else 0.5 * (1 - erf)
    }
}
