package dev.whoop4.bloop.analytics

import kotlin.math.abs

/** Clean beats split into runs with no gap or rejected beat inside; successive differences are only taken within a run. */
data class CleanRr(val segments: List<List<RrPoint>>, val total: Int, val kept: Int) {
    val artefactFraction: Double get() = if (total == 0) 0.0 else 1.0 - kept.toDouble() / total
}

/**
 * RR artefact filter (Malik-style): a beat must be physiological ([MIN_MS]..[MAX_MS]) and within ±20%
 * of the median of the last five accepted beats. A rejected beat, or a gap of more than [MAX_GAP_MS]
 * between samples, ends the current run, so a difference is never taken across a missing beat.
 */
object RrCleaner {
    const val MIN_MS = 300.0
    const val MAX_MS = 2000.0
    const val MAX_REL_DIFF = 0.20
    const val MAX_GAP_MS = 3_000L

    fun clean(rr: List<RrPoint>): CleanRr {
        val segments = mutableListOf<List<RrPoint>>()
        var current = mutableListOf<RrPoint>()
        val recent = ArrayDeque<Double>()
        var kept = 0
        var rejectedInRow = 0
        var lastTs: Long? = null

        fun endRun() {
            if (current.isNotEmpty()) segments += current
            current = mutableListOf()
        }

        for (p in rr.sortedBy { it.tsMs }) {
            if (lastTs != null && p.tsMs - lastTs > MAX_GAP_MS) { endRun(); recent.clear() }
            lastTs = p.tsMs
            val inRange = p.rrMs in MIN_MS..MAX_MS
            val ref = if (recent.isEmpty()) null else median(recent.toList())
            val ok = inRange && (ref == null || abs(p.rrMs - ref) <= MAX_REL_DIFF * ref)
            if (ok) {
                current += p
                kept++
                rejectedInRow = 0
                recent.addLast(p.rrMs)
                if (recent.size > 5) recent.removeFirst()
            } else {
                endRun()
                // Several in-range rejections in a row mean the reference itself is off (it was seeded by an
                // ectopic beat, or the rhythm really changed): restart the reference from the next beat.
                if (inRange && ++rejectedInRow >= 3) { recent.clear(); rejectedInRow = 0 }
            }
        }
        endRun()
        return CleanRr(segments, rr.size, kept)
    }
}

internal fun median(xs: List<Double>): Double {
    require(xs.isNotEmpty())
    val s = xs.sorted()
    val m = s.size / 2
    return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
}

/** Linear-interpolated percentile, p in 0..100. */
internal fun percentile(xs: List<Double>, p: Double): Double {
    require(xs.isNotEmpty())
    val s = xs.sorted()
    val pos = p / 100.0 * (s.size - 1)
    val lo = pos.toInt()
    val hi = minOf(lo + 1, s.size - 1)
    return s[lo] + (s[hi] - s[lo]) * (pos - lo)
}

internal fun mean(xs: List<Double>): Double = xs.sum() / xs.size

internal fun sd(xs: List<Double>): Double {
    if (xs.size < 2) return 0.0
    val m = mean(xs)
    return kotlin.math.sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1))
}
