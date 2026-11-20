package dev.whoop4.bloop.analytics

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.sqrt

enum class SleepState { ASLEEP, WAKE }

data class SleepSegment(val startMs: Long, val endMs: Long, val state: SleepState)

data class SleepResult(
    /** Sleep period: from first falling asleep to final waking (the HDCZA "sleep period time window"). */
    val startMs: Long,
    val endMs: Long,
    val asleepMs: Long,
    val awakeMs: Long,
    val segments: List<SleepSegment>,
    /** Fraction of the sleep period with motion data. */
    val coverage: Double,
) {
    val inBedMs: Long get() = endMs - startMs
    val efficiency: Double get() = if (inBedMs == 0L) 0.0 else asleepMs.toDouble() / inBedMs
}

/**
 * Sleep from wrist motion, following van Hees et al.:
 *  - **Sleep period** (2018, "HDCZA", Sci Rep 8:12975): the arm angle z = atan(az / √(ax²+ay²)) per 5-s epoch;
 *    absolute change between epochs; 5-minute rolling median; threshold = 10th percentile × 15 (bounded to
 *    0.13–0.50°); quiet blocks longer than 30 min, joined across gaps shorter than 60 min; the longest
 *    block is the main sleep period.
 *  - **Sleep vs wake inside it** (2015, PLoS ONE 10:e0142533): asleep where the arm angle changes by no more
 *    than 5° for at least 5 minutes; everything else is wake.
 * Only the angle is used, so the accelerometer's unit does not matter. Seconds without a band sample
 * (off the wrist, or HR = 0 records, which are not stored) count as missing, never as quiet.
 * This is an estimate; sleep stages are not attempted (plan §3: low accuracy, deferred).
 */
object SleepDetector {
    const val METHOD = "vanhees-hdcza-v1"
    const val EPOCH_MS = 5_000L
    private const val ROLL_EPOCHS = 60          // 5 min
    private const val MIN_BLOCK_MS = 30 * 60_000L
    private const val MAX_GAP_MS = 60 * 60_000L
    private const val SIB_DEG = 5.0
    private const val SIB_EPOCHS = 60           // 5 min
    const val MIN_SLEEP_PERIOD_MS = 2 * 60 * 60_000L

    fun detect(hr: List<HrPoint>, fromMs: Long, toMs: Long): Metric<SleepResult> {
        val n = ((toMs - fromMs) / EPOCH_MS).toInt()
        if (n <= ROLL_EPOCHS) return Metric.Missing("window too short")

        // Arm angle per epoch (median of the samples in it), NaN where there is no data.
        val buckets = arrayOfNulls<MutableList<Double>>(n)
        for (p in hr) {
            if (p.tsMs < fromMs || p.tsMs >= toMs) continue
            val ax = p.ax ?: continue; val ay = p.ay ?: continue; val az = p.az ?: continue
            val h = sqrt((ax * ax + ay * ay).toDouble())
            if (h == 0.0 && az == 0f) continue
            val angle = Math.toDegrees(atan(az / h))
            val i = ((p.tsMs - fromMs) / EPOCH_MS).toInt()
            (buckets[i] ?: mutableListOf<Double>().also { buckets[i] = it }) += angle
        }
        val angle = DoubleArray(n) { i -> buckets[i]?.let { median(it) } ?: Double.NaN }
        val present = angle.count { !it.isNaN() }
        if (present < n / 10) return Metric.Missing("not enough motion data in the night window (${present * 100 / n}% covered)")

        val diff = DoubleArray(n) { i -> if (i == 0) Double.NaN else abs(angle[i] - angle[i - 1]) }

        // 5-minute rolling median of the angle change (needs half the window present).
        val rolled = DoubleArray(n) { Double.NaN }
        for (i in 0 until n) {
            val lo = maxOf(0, i - ROLL_EPOCHS / 2); val hi = minOf(n, i + ROLL_EPOCHS / 2)
            val vals = (lo until hi).map { diff[it] }.filter { !it.isNaN() }
            if (vals.size >= ROLL_EPOCHS / 2) rolled[i] = median(vals)
        }
        val valid = rolled.filter { !it.isNaN() }
        if (valid.isEmpty()) return Metric.Missing("not enough continuous motion data")
        val threshold = (percentile(valid, 10.0) * 15).coerceIn(0.13, 0.50)

        // Quiet blocks > 30 min, joined across gaps < 60 min; the longest is the sleep period.
        val blocks = runs(n) { !rolled[it].isNaN() && rolled[it] < threshold }
            .filter { (a, b) -> (b - a) * EPOCH_MS > MIN_BLOCK_MS }
        if (blocks.isEmpty()) return Metric.Missing("no sustained period of rest found")
        val joined = mutableListOf(blocks.first())
        for (b in blocks.drop(1)) {
            val last = joined.last()
            if ((b.first - last.second) * EPOCH_MS < MAX_GAP_MS) joined[joined.size - 1] = last.first to b.second
            else joined += b
        }
        val (spStart, spEnd) = joined.maxBy { it.second - it.first }
        if ((spEnd - spStart) * EPOCH_MS < MIN_SLEEP_PERIOD_MS) return Metric.Missing("longest rest period is under 2 hours")

        // Sustained inactivity bouts inside the sleep period.
        val asleep = BooleanArray(n)
        for ((a, b) in runs(n) { it in spStart until spEnd && !diff[it].isNaN() && diff[it] <= SIB_DEG }) {
            if (b - a >= SIB_EPOCHS) for (i in a until b) asleep[i] = true
        }
        val segments = mutableListOf<SleepSegment>()
        var i = spStart
        while (i < spEnd) {
            var j = i
            while (j < spEnd && asleep[j] == asleep[i]) j++
            segments += SleepSegment(fromMs + i * EPOCH_MS, fromMs + j * EPOCH_MS, if (asleep[i]) SleepState.ASLEEP else SleepState.WAKE)
            i = j
        }
        // Trim leading/trailing wake so the period runs from sleep onset to final waking.
        while (segments.isNotEmpty() && segments.first().state == SleepState.WAKE) segments.removeAt(0)
        while (segments.isNotEmpty() && segments.last().state == SleepState.WAKE) segments.removeAt(segments.size - 1)
        if (segments.isEmpty()) return Metric.Missing("no sleep detected in the rest period")

        val start = segments.first().startMs; val end = segments.last().endMs
        val asleepMs = segments.filter { it.state == SleepState.ASLEEP }.sumOf { it.endMs - it.startMs }
        val e0 = ((start - fromMs) / EPOCH_MS).toInt(); val e1 = ((end - fromMs) / EPOCH_MS).toInt()
        val coverage = (e0 until e1).count { !angle[it].isNaN() }.toDouble() / maxOf(1, e1 - e0)
        val result = SleepResult(start, end, asleepMs, (end - start) - asleepMs, segments, coverage)
        // Wrist actigraphy agrees well with PSG on sleep/wake for the sleep period; confidence tracks data coverage.
        return Metric.Estimated(result, METHOD, (0.8 * coverage).coerceIn(0.0, 1.0))
    }

    /** Maximal runs [a, b) of consecutive indices where [pred] holds. */
    private fun runs(n: Int, pred: (Int) -> Boolean): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < n) {
            if (!pred(i)) { i++; continue }
            var j = i
            while (j < n && pred(j)) j++
            out += i to j
            i = j
        }
        return out
    }
}

/** Sleep performance = time asleep ÷ the user's sleep need. */
object SleepPerformance {
    const val METHOD = "asleep-over-need-v1"
    fun of(sleep: Metric<SleepResult>, needMs: Long): Metric<Double> = when (sleep) {
        is Metric.Estimated -> Metric.Estimated((sleep.value.asleepMs.toDouble() / needMs).coerceAtMost(1.0), METHOD, sleep.confidence)
        is Metric.Missing -> sleep
        else -> Metric.Missing("unsupported sleep input")
    }
}
