package dev.whoop4.bloop.analytics

import kotlin.math.ln
import kotlin.math.sqrt

data class HrvResult(val rmssdMs: Double, val lnRmssd: Double, val windows: Int, val artefactFraction: Double)

/**
 * Heart-rate variability as RMSSD (Task Force of the ESC/NASPE, 1996) over clean RR intervals.
 * The sleep period is cut into 5-minute windows; a window counts if it holds at least
 * [MIN_DIFFS_PER_WINDOW] successive differences. The nightly value is the median of the window
 * RMSSDs, which is robust to a few noisy windows.
 */
object Hrv {
    const val METHOD = "rmssd-5min-median-v1"
    const val WINDOW_MS = 5 * 60_000L
    const val MIN_DIFFS_PER_WINDOW = 60
    const val MIN_WINDOWS = 3

    /** RMSSD over all runs, or null with fewer than 2 successive differences. */
    fun rmssd(segments: List<List<RrPoint>>): Double? {
        var sum = 0.0
        var n = 0
        for (seg in segments) for (i in 1 until seg.size) {
            val d = seg[i].rrMs - seg[i - 1].rrMs
            sum += d * d; n++
        }
        return if (n < 2) null else sqrt(sum / n)
    }

    fun nightly(rr: List<RrPoint>, fromMs: Long, toMs: Long): Metric<HrvResult> {
        val inWindow = rr.filter { it.tsMs in fromMs until toMs }
        if (inWindow.isEmpty()) return Metric.Missing("no RR intervals during sleep")
        val clean = RrCleaner.clean(inWindow)

        // Split runs at window boundaries: a pair of beats only counts inside one window.
        val byWindow = HashMap<Long, MutableList<List<RrPoint>>>()
        for (seg in clean.segments) for ((w, part) in seg.groupBy { (it.tsMs - fromMs) / WINDOW_MS }) {
            if (part.size > 1) byWindow.getOrPut(w) { mutableListOf() } += part
        }
        val perWindow = byWindow.values
            .filter { segs -> segs.sumOf { it.size - 1 } >= MIN_DIFFS_PER_WINDOW }
            .mapNotNull { rmssd(it) }
        if (perWindow.size < MIN_WINDOWS) {
            return Metric.Missing("only ${perWindow.size} usable 5-minute RR windows (need $MIN_WINDOWS)")
        }
        val value = median(perWindow)
        val possible = ((toMs - fromMs + WINDOW_MS - 1) / WINDOW_MS).toDouble()
        val coverage = perWindow.size / possible
        // Full confidence once half the windows are usable and there are no artefacts.
        val confidence = (coverage / 0.5).coerceAtMost(1.0) * (1 - clean.artefactFraction)
        return Metric.Derived(HrvResult(value, ln(value), perWindow.size, clean.artefactFraction), METHOD, confidence.coerceIn(0.0, 1.0))
    }
}
