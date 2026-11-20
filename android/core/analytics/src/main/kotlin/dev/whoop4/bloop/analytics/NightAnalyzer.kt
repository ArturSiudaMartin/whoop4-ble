package dev.whoop4.bloop.analytics

data class NightAnalysis(
    val sleep: Metric<SleepResult>,
    val performance: Metric<Double>,
    val hrv: Metric<HrvResult>,
    val restingHr: Metric<RestingHr.Result>,
)

/**
 * One night: find the main sleep period in [fromMs, toMs) (typically 18:00 the evening before to 14:00
 * on the wake-up day), then compute HRV and resting HR inside it. Without a sleep period there is no
 * HRV or resting HR either: they are defined as sleep values, and daytime values would not be comparable.
 */
object NightAnalyzer {
    fun analyze(hr: List<HrPoint>, rr: List<RrPoint>, fromMs: Long, toMs: Long, sleepNeedMs: Long): NightAnalysis {
        val sleep = SleepDetector.detect(hr, fromMs, toMs)
        val period = sleep.valueOrNull
            ?: return NightAnalysis(sleep, SleepPerformance.of(sleep, sleepNeedMs), Metric.Missing("no sleep detected"), Metric.Missing("no sleep detected"))
        return NightAnalysis(
            sleep = sleep,
            performance = SleepPerformance.of(sleep, sleepNeedMs),
            hrv = Hrv.nightly(rr, period.startMs, period.endMs),
            restingHr = RestingHr.during(hr, period.startMs, period.endMs),
        )
    }
}
