package dev.whoop4.bloop.analytics

/**
 * Resting heart rate = the lowest 5-minute mean heart rate during sleep; a window needs 80% of its
 * seconds covered. The 5th percentile of all sleep HR (the method of the repo's monitor page) is
 * kept alongside as a cross-check.
 */
object RestingHr {
    const val METHOD = "lowest-5min-mean-v1"
    const val WINDOW_MS = 5 * 60_000L
    const val MIN_COVERAGE = 0.8

    data class Result(val bpm: Double, val p5Bpm: Double)

    fun during(hr: List<HrPoint>, fromMs: Long, toMs: Long): Metric<Result> {
        val pts = hr.filter { it.tsMs in fromMs until toMs && it.hr in 25..220 }.sortedBy { it.tsMs }
        if (pts.isEmpty()) return Metric.Missing("no heart rate during sleep")

        val needed = (WINDOW_MS / 1000 * MIN_COVERAGE).toInt()
        var best: Double? = null
        var sum = 0L
        var lo = 0
        for (hi in pts.indices) {
            sum += pts[hi].hr
            while (pts[hi].tsMs - pts[lo].tsMs >= WINDOW_MS) { sum -= pts[lo].hr; lo++ }
            val n = hi - lo + 1
            if (n >= needed) {
                val m = sum.toDouble() / n
                if (best == null || m < best) best = m
            }
        }
        if (best == null) return Metric.Missing("no 5-minute window with enough heart-rate samples")
        val p5 = percentile(pts.map { it.hr.toDouble() }, 5.0)
        val coverage = pts.size / ((toMs - fromMs) / 1000.0)
        return Metric.Derived(Result(best, p5), METHOD, coverage.coerceIn(0.0, 1.0))
    }
}
