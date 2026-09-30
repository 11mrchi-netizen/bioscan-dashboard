package com.bioscan.fieldterminal.domain.stress

import com.bioscan.fieldterminal.domain.Confidence
import com.bioscan.fieldterminal.domain.comparison.personalBaseline
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// 05.1 Stress Rhythm (DAV-246-249). PhysiologicalStressReading/StressDay are
// the canonical raw-vs-derived shapes: a raw Zepp intraday sample (0-100
// watch-reported stress) kept explicitly separate from Field Terminal's own
// derived pattern outputs (StressRhythmResult) -- never blended, same
// "preserve raw vs derived" rule zepp_workout_detail's raw/decoded split
// already established for workout data. Never compared against or presented
// as equivalent to wellbeing_daily's subjective stress rating.
const val STRESS_RHYTHM_MODEL_VERSION = "v1"

data class PhysiologicalStressReading(val timestamp: Instant, val value: Int)

// avgStress/maxStress/.../proportions as reported by Zepp's own daily
// all_day_stress summary -- kept as-is, not re-derived from the intraday
// samples, since it's already real and independently computed by the watch.
data class StressDailySummary(
    val avg: Int?,
    val max: Int?,
    val min: Int?,
    val relaxProportion: Int?,
    val normalProportion: Int?,
    val mediumProportion: Int?,
    val highProportion: Int?,
)

data class StressDay(val date: LocalDate, val samples: List<PhysiologicalStressReading>, val summary: StressDailySummary)

data class StressRhythmResult(
    val baseline: Double?,
    val peakMagnitude: Int?,
    val peakDurationMinutes: Long?,
    val eveningDownRegulationPct: Double?,
    val deviationFromPersonalPattern: Double?,
    val confidence: Confidence,
    val modelVersion: String = STRESS_RHYTHM_MODEL_VERSION,
)

// A sample is "elevated" once it clears the day's own baseline by this many
// points (0-100 scale) -- named threshold, not fitted; no data volume exists
// yet to fit one.
private const val PEAK_THRESHOLD_ABOVE_BASELINE = 15
private const val EVENING_WINDOW_HOURS = 3L
private const val PERSONAL_BASELINE_WINDOW_DAYS = 28
private const val MIN_SAMPLES_FOR_DAY = 12 // ~1 sample/hour minimum to trust a day's shape

// DAV-247's explicit warning: exercise-driven HR elevation must never read as
// a generic stress peak. Samples inside any given exercise window are
// dropped before baseline/peak math runs, not just flagged.
fun excludeExerciseWindows(samples: List<PhysiologicalStressReading>, exerciseWindows: List<Pair<Instant, Instant>>): List<PhysiologicalStressReading> {
    if (exerciseWindows.isEmpty()) return samples
    return samples.filterNot { reading -> exerciseWindows.any { (start, end) -> !reading.timestamp.isBefore(start) && !reading.timestamp.isAfter(end) } }
}

private fun median(values: List<Int>): Double {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid].toDouble()
}

// Longest contiguous run of consecutive (by list order, already time-sorted)
// samples at or above the elevated threshold -- returned as a sample count,
// converted to minutes by the caller once real intraday spacing is known
// (this account's real sampling is ~5-10min, not a fixed interval).
private fun longestElevatedRun(samples: List<PhysiologicalStressReading>, threshold: Double): Pair<Int, ClosedRange<Instant>>? {
    var bestLen = 0
    var bestRange: ClosedRange<Instant>? = null
    var curLen = 0
    var curStart: Instant? = null
    for (sample in samples) {
        if (sample.value >= threshold) {
            if (curLen == 0) curStart = sample.timestamp
            curLen++
            if (curLen > bestLen) {
                bestLen = curLen
                bestRange = curStart!!..sample.timestamp
            }
        } else {
            curLen = 0
            curStart = null
        }
    }
    return bestRange?.let { bestLen to it }
}

fun analyzeStressDay(
    day: StressDay,
    recentDays: List<StressDay>,
    exerciseWindows: List<Pair<Instant, Instant>> = emptyList(),
    zone: ZoneId = ZoneId.systemDefault(),
): StressRhythmResult {
    val samples = excludeExerciseWindows(day.samples, exerciseWindows).sortedBy { it.timestamp }
    if (samples.size < MIN_SAMPLES_FOR_DAY) {
        return StressRhythmResult(null, null, null, null, null, Confidence(samples.size, MIN_SAMPLES_FOR_DAY))
    }

    val baseline = median(samples.map { it.value })
    val threshold = baseline + PEAK_THRESHOLD_ABOVE_BASELINE
    val peakMagnitude = samples.maxOf { it.value } - baseline.toInt()
    val elevatedRun = longestElevatedRun(samples, threshold)
    val peakDurationMinutes = elevatedRun?.let { (_, range) -> java.time.Duration.between(range.start, range.endInclusive).toMinutes() }

    val eveningStart = day.date.atStartOfDay(zone).plusHours(24 - EVENING_WINDOW_HOURS).toInstant()
    val eveningSamples = samples.filter { !it.timestamp.isBefore(eveningStart) }
    val peakValue = samples.maxOf { it.value }.toDouble()
    val eveningDownRegulationPct = if (eveningSamples.isNotEmpty() && peakValue > baseline) {
        val eveningAvg = eveningSamples.map { it.value }.average()
        (((peakValue - eveningAvg) / (peakValue - baseline)) * 100.0).coerceIn(0.0, 100.0)
    } else null

    val dailyAvgPoints = recentDays.mapNotNull { d -> d.summary.avg?.let { d.date to it.toDouble() } }
    val baselineStats = personalBaseline(dailyAvgPoints, PERSONAL_BASELINE_WINDOW_DAYS, day.date)
    val deviationFromPersonalPattern = baselineStats?.let { day.summary.avg?.toDouble()?.minus(it.mean) }

    return StressRhythmResult(
        baseline = baseline,
        peakMagnitude = peakMagnitude,
        peakDurationMinutes = peakDurationMinutes,
        eveningDownRegulationPct = eveningDownRegulationPct,
        deviationFromPersonalPattern = deviationFromPersonalPattern,
        confidence = Confidence(samples.size, MIN_SAMPLES_FOR_DAY),
    )
}
