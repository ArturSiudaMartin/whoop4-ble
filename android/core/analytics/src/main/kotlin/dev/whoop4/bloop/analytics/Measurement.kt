package dev.whoop4.bloop.analytics

/**
 * Every value Bloop displays is tagged with where it came from, so the UI can always tell
 * measured values apart from estimates. A missing input is [Missing], never a number.
 */
sealed interface Metric<out T> {
    /** Read directly from the band (e.g. heart rate, RR intervals). */
    data class Measured<T>(val value: T) : Metric<T>

    /**
     * A fixed formula applied to measured data (e.g. RMSSD, lowest 5-minute heart rate).
     * Deterministic, but its quality depends on the data, hence [confidence] (0..1).
     */
    data class Derived<T>(val value: T, val method: String, val confidence: Double) : Metric<T>

    /** Inferred by a model or heuristic (sleep, strain scale, recovery). [confidence] is 0..1. */
    data class Estimated<T>(val value: T, val method: String, val confidence: Double) : Metric<T>

    /** Not enough data yet (e.g. recovery while the baseline is still calibrating). */
    data class Missing(val reason: String) : Metric<Nothing>
}

/** The value, or null for [Metric.Missing]. */
val <T> Metric<T>.valueOrNull: T?
    get() = when (this) {
        is Metric.Measured -> value
        is Metric.Derived -> value
        is Metric.Estimated -> value
        is Metric.Missing -> null
    }

/** Confidence of a computed metric; 1 for measured values, 0 for missing ones. */
val Metric<*>.confidenceOrZero: Double
    get() = when (this) {
        is Metric.Measured -> 1.0
        is Metric.Derived -> confidence
        is Metric.Estimated -> confidence
        is Metric.Missing -> 0.0
    }

/** One heart-rate sample (≈1 per second from the band). Accelerometer axes in g; null when not sent. */
data class HrPoint(
    val tsMs: Long,
    val hr: Int,
    val ax: Float? = null,
    val ay: Float? = null,
    val az: Float? = null,
)

/** One RR interval in milliseconds, stamped with the time of the sample it arrived with. */
data class RrPoint(val tsMs: Long, val rrMs: Double)
