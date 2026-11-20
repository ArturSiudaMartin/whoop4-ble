package dev.whoop4.bloop.analytics

/**
 * Every value Bloop displays is tagged with where it came from, so the UI can always tell
 * measured values apart from estimates. A missing input is [Missing], never a number.
 * Algorithms (HRV, RHR, sleep, strain, recovery) arrive in Phase 8.
 */
sealed interface Metric<out T> {
    /** Read directly from the band (e.g. heart rate, RR intervals). */
    data class Measured<T>(val value: T) : Metric<T>

    /** Computed by Bloop with a named, published method. [confidence] is 0..1. */
    data class Estimated<T>(val value: T, val method: String, val confidence: Double) : Metric<T>

    /** Not enough data yet (e.g. recovery while the baseline is still calibrating). */
    data class Missing(val reason: String) : Metric<Nothing>
}
