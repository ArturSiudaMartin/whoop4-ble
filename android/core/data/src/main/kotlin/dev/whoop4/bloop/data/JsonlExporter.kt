package dev.whoop4.bloop.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Exports historical samples as `hr.jsonl` in the same format drain.py writes, so Bloop data can be merged
 * with data pulled by the Python toolkit or the web app:
 *
 *   {"ts": "2026-05-11T15:31:04.000+00:00", "hr": 60, "rr_ms": [993], "accel": [0.5319, 0.3221, 0.806]}
 *
 * `ts` is the record's UTC second plus a `.NNN` counter for records within the same second (drain.py's dedup
 * key). Bloop orders same-second records by their sub-second tiebreaker, which is deterministic; drain.py uses
 * arrival order. `accel` is rounded to 4 decimals like Python's round(); `rr_ms` and `accel` are omitted when absent.
 */
class JsonlExporter(private val db: BloopDatabase) {
    private val secondFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC)

    /** Writes every historical sample of [device] to [out]; returns the number of lines written. */
    suspend fun exportHr(device: String, out: Appendable, pageSize: Int = 5000): Long {
        var afterTs = Long.MIN_VALUE; var afterSub = Int.MIN_VALUE
        var lastSecond = Long.MIN_VALUE; var seqInSec = 0
        var lines = 0L
        while (true) {
            val page = db.samples().page(device, SampleSource.HISTORICAL, afterTs, afterSub, pageSize)
            if (page.isEmpty()) break
            val rr = db.samples().rrForRange(device, SampleSource.HISTORICAL, page.first().tsUtcMs, page.last().tsUtcMs)
                .groupBy { it.sampleTsUtcMs to it.subKey }
            for (s in page) {
                val sec = Math.floorDiv(s.tsUtcMs, 1000L)
                if (sec == lastSecond) seqInSec++ else { lastSecond = sec; seqInSec = 0 }
                out.append(line(s, rr[s.tsUtcMs to s.subKey].orEmpty(), sec, seqInSec)).append('\n')
                lines++
            }
            afterTs = page.last().tsUtcMs; afterSub = page.last().subKey
        }
        return lines
    }

    internal fun line(s: HrSampleEntity, rr: List<RrIntervalEntity>, second: Long, seqInSec: Int): String = buildString {
        append("{\"ts\": \"").append(secondFmt.format(Instant.ofEpochSecond(second)))
        append('.').append(seqInSec.toString().padStart(3, '0')).append("+00:00\", \"hr\": ").append(s.hr)
        if (rr.isNotEmpty()) append(", \"rr_ms\": [").append(rr.sortedBy { it.idx }.joinToString(", ") { it.rrMs.toLong().toString() }).append(']')
        if (s.accelX != null && s.accelY != null && s.accelZ != null) {
            append(", \"accel\": [").append(listOf(s.accelX, s.accelY, s.accelZ).joinToString(", ") { pyRound4(it) }).append(']')
        }
        append('}')
    }

    companion object {
        /** Python `repr(round(float(x), 4))`: exact binary value, half-even, shortest form, always with a decimal point. */
        fun pyRound4(x: Float): String {
            val s = BigDecimal(x.toDouble()).setScale(4, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()
            val withDot = if ('.' in s) s else "$s.0"
            return if (withDot == "0.0" && (x < 0 || 1 / x < 0)) "-0.0" else withDot
        }
    }
}
