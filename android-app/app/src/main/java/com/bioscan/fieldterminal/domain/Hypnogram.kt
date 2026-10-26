package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.HrPoint
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// The hypnogram (Today › Sleep phase breakdown): the night's timed stages, the wake time, estimated sleep cycles and the
// overnight heart rate on the same minute axis. Stages come from sleep_daily.stage_intervals (zepp-extract/sleepStages.ts):
// [offsetMin, durationMin, zeppMode] from the night's first stage. Pure, so the shapes are unit-tested.

enum class SleepStage(val code: Int, val label: String) {
    Awake(7, "AWAKE"), Rem(8, "REM"), Light(4, "LIGHT"), Deep(5, "DEEP");

    companion object {
        fun of(code: Int): SleepStage? = entries.firstOrNull { it.code == code }
    }
}

data class StageBlock(val startMin: Int, val durationMin: Int, val stage: SleepStage) {
    val endMin: Int get() = startMin + durationMin
}

/** The stored [[offset, duration, mode]] rows as blocks; rows with an unknown mode or a bad shape are skipped. */
fun parseStageBlocks(raw: List<List<Int>>): List<StageBlock> =
    raw.mapNotNull { r ->
        if (r.size < 3 || r[1] <= 0) return@mapNotNull null
        SleepStage.of(r[2])?.let { StageBlock(r[0], r[1], it) }
    }.sortedBy { it.startMin }

/** Minutes in each stage over the night. */
fun stageMinutes(blocks: List<StageBlock>): Map<SleepStage, Int> =
    blocks.groupBy { it.stage }.mapValues { (_, v) -> v.sumOf { it.durationMin } }

/** A sleep cycle estimated from the stages: [startMin] to [endMin] from sleep onset. */
data class SleepCycle(val startMin: Int, val endMin: Int)

/** REM episodes apart by no more than this many minutes read as one REM period. */
const val REM_MERGE_GAP_MIN = 10

/** A cycle shorter than this is folded into the next (a REM blip is not a cycle). */
const val MIN_CYCLE_MIN = 45

/**
 * Cycles are not reported by the band, so they are estimated: a cycle ends where a REM period ends (REM blocks closer than
 * [REM_MERGE_GAP_MIN] are one period), and the stretch after the last REM period to waking is one more if it is long
 * enough. The night's ends are the first and last stage. An estimate for orientation, not a measurement.
 */
fun estimateCycles(blocks: List<StageBlock>): List<SleepCycle> {
    if (blocks.isEmpty()) return emptyList()
    val rem = blocks.filter { it.stage == SleepStage.Rem }
    val periods = mutableListOf<IntRange>()
    for (b in rem) {
        val last = periods.lastOrNull()
        if (last != null && b.startMin - (last.last + 1) <= REM_MERGE_GAP_MIN) periods[periods.lastIndex] = last.first..(b.endMin - 1)
        else periods += b.startMin..(b.endMin - 1)
    }
    val end = blocks.maxOf { it.endMin }
    val cuts = (periods.map { it.last + 1 } + end).distinct().sorted()
    val cycles = mutableListOf<SleepCycle>()
    var start = blocks.first().startMin
    for (cut in cuts) {
        if (cut - start >= MIN_CYCLE_MIN) {
            cycles += SleepCycle(start, cut)
            start = cut
        }
    }
    // A tail too short for a cycle joins the last one.
    if (start < end && cycles.isNotEmpty()) cycles[cycles.lastIndex] = cycles.last().copy(endMin = end)
    else if (start < end) cycles += SleepCycle(start, end)
    return cycles
}

/** One heart-rate reading on the night's axis: minutes from sleep onset. */
data class NightHr(val minute: Int, val bpm: Int)

/**
 * The night's heart rate from the two days it spans. wearable_daily.hr_timeseries holds minutes from LOCAL midnight of its
 * date, so each reading's instant is that midnight (the date at UTC midnight minus the band's offset) plus its minute; the
 * night keeps readings from sleep onset to [spanMin] minutes after it.
 */
fun nightHeartRate(
    bedtime: Instant,
    tzOffsetMin: Int,
    spanMin: Int,
    days: List<Pair<LocalDate, List<HrPoint>>>,
): List<NightHr> =
    days.flatMap { (date, points) ->
        val localMidnight = date.atStartOfDay().toInstant(ZoneOffset.UTC).minusSeconds(tzOffsetMin * 60L)
        points.mapNotNull { p ->
            if (p.bpm !in MINUTE_HR_MIN_BPM..MINUTE_HR_MAX_BPM) return@mapNotNull null
            val minute = ((localMidnight.epochSecond + p.offsetMinutes * 60L - bedtime.epochSecond) / 60).toInt()
            NightHr(minute, p.bpm).takeIf { minute in 0..spanMin }
        }
    }.distinctBy { it.minute }.sortedBy { it.minute }

/** Local wall-clock "HH:mm" of [minuteFromOnset] minutes after [bedtime], for the band's offset. */
fun clockLabel(bedtime: Instant, tzOffsetMin: Int, minuteFromOnset: Int): String {
    val t = bedtime.plusSeconds((minuteFromOnset + tzOffsetMin) * 60L).atOffset(ZoneOffset.UTC)
    return "%02d:%02d".format(t.hour, t.minute)
}

/** One night ready to draw: the stages, wake time, estimated cycles and the overnight heart rate, all on minutes from onset. */
data class HypnogramNight(
    val date: LocalDate,
    val bedtime: Instant,
    val tzOffsetMin: Int,
    val blocks: List<StageBlock>,
    val cycles: List<SleepCycle>,
    val heartRate: List<NightHr>,
) {
    val spanMin: Int get() = blocks.maxOfOrNull { it.endMin } ?: 0
}
