package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.HrPoint
import java.time.LocalDate

// The band's minute heart rate for one day (wearable_daily.hr_timeseries, minutes from local midnight). The band writes
// 0, 254 and 255 for minutes without a reading; zepp-extract/minuteHr.ts now drops them and a migration cleaned the
// stored days, but the phone filters again so a day extracted by an older function is never charted at 255 bpm.

const val MINUTE_HR_MIN_BPM = 30
const val MINUTE_HR_MAX_BPM = 220

/** More minutes than this without a reading break the line: the band was off, not a slow change. */
const val MINUTE_HR_MAX_GAP_MINUTES = 5

data class HeartRateDay(val date: LocalDate, val points: List<HrPoint>)

data class MinuteHrSummary(val wornMinutes: Int, val low: Int, val high: Int, val average: Int)

/** Only the minutes a heart can beat at, in time order, one per minute. */
fun plausibleMinutes(points: List<HrPoint>): List<HrPoint> =
    points
        .filter { it.bpm in MINUTE_HR_MIN_BPM..MINUTE_HR_MAX_BPM && it.offsetMinutes in 0 until 24 * 60 }
        .distinctBy { it.offsetMinutes }
        .sortedBy { it.offsetMinutes }

/** Runs of minutes without a gap longer than [maxGapMinutes]; each run is drawn as its own line. */
fun minuteSegments(points: List<HrPoint>, maxGapMinutes: Int = MINUTE_HR_MAX_GAP_MINUTES): List<List<HrPoint>> {
    val out = mutableListOf<MutableList<HrPoint>>()
    for (p in points) {
        val last = out.lastOrNull()?.last()
        if (last == null || p.offsetMinutes - last.offsetMinutes > maxGapMinutes) out.add(mutableListOf(p)) else out.last().add(p)
    }
    return out
}

fun minuteHrSummary(points: List<HrPoint>): MinuteHrSummary? {
    if (points.isEmpty()) return null
    return MinuteHrSummary(
        wornMinutes = points.size,
        low = points.minOf { it.bpm },
        high = points.maxOf { it.bpm },
        average = Math.round(points.sumOf { it.bpm }.toDouble() / points.size).toInt(),
    )
}

/** "06:42" for a minute of the day. */
fun minuteOfDayLabel(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
