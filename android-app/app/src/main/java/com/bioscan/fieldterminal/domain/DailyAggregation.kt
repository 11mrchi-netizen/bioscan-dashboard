package com.bioscan.fieldterminal.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Phase G2. Generic, framework-free helpers for turning a list of
// timestamped Health Connect readings into one value per local calendar
// date -- the same "compute in the domain layer, keep data/repository code
// thin" convention every other domain file in this app already follows.
// Health Connect itself has no equivalent of "group these readings by the
// device's local day," so this fills that gap once, reused across every
// metric in HealthConnectDailySyncRepository rather than reimplemented per
// metric.
// `origin` (Analysis Layer 3 / R1) is the Health Connect dataOrigin package that wrote the reading, when the caller
// knows it; the aggregation helpers below ignore it.
data class TimedValue(val at: Instant, val value: Double, val origin: String? = null)

private fun groupByLocalDate(values: List<TimedValue>, zone: ZoneId): Map<LocalDate, List<Double>> =
    values.groupBy { it.at.atZone(zone).toLocalDate() }.mapValues { (_, v) -> v.map { it.value } }

fun sumByLocalDate(values: List<TimedValue>, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, Double> =
    groupByLocalDate(values, zone).mapValues { (_, v) -> v.sum() }

fun averageByLocalDate(values: List<TimedValue>, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, Double> =
    groupByLocalDate(values, zone).mapValues { (_, v) -> v.average() }

// "Latest" per day, not overall -- e.g. two weigh-ins on the same day should
// resolve to the more recent one, matching how a person would expect their
// own daily weight reading to behave.
fun latestByLocalDate(values: List<TimedValue>, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, Double> =
    values.groupBy { it.at.atZone(zone).toLocalDate() }
        .mapValues { (_, v) -> v.maxBy { it.at }.value }

// Sleep-stage duration summed by stage type, in minutes -- used to compute
// deep_min/rem_min/light_min from a SleepSessionRecord's stage list.
data class SleepStageInterval(val startEpochSeconds: Long, val endEpochSeconds: Long, val stageType: Int)

fun sumStageMinutes(stages: List<SleepStageInterval>, stageType: Int): Double? {
    val matching = stages.filter { it.stageType == stageType }
    if (matching.isEmpty()) return null
    return matching.sumOf { Duration.ofSeconds(it.endEpochSeconds - it.startEpochSeconds).toMinutes().toDouble() }
}

// The origin that wrote the most readings on each local date; ties go to the origin of the most recent reading.
// Readings without a known origin are ignored, so a date with none is absent (never labelled with a guess).
fun dominantOriginByLocalDate(values: List<TimedValue>, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, String> =
    values.filter { it.origin != null }
        .groupBy { it.at.atZone(zone).toLocalDate() }
        .mapValues { (_, day) ->
            val counts = day.groupingBy { it.origin!! }.eachCount()
            val top = counts.values.max()
            day.filter { counts.getValue(it.origin!!) == top }.maxBy { it.at }.origin!!
        }

// Analysis review phase B (docs/analysis-review/README.md). Overnight readings (HRV, resting heart rate) belong to the
// night they were taken in, keyed like sleep_daily by the morning that night ends on. A plain calendar-day average split
// one night's samples across two dates (23:00 on one day, 03:00 on the next), so "next-day HRV" did not cleanly mean the
// morning after. Readings from 18:00 count toward the next date; daytime readings before 18:00 stay on their own date.
const val NIGHT_KEY_FROM_HOUR = 18

fun nightDate(at: Instant, zone: ZoneId = ZoneId.systemDefault()): LocalDate {
    val local = at.atZone(zone)
    return if (local.hour >= NIGHT_KEY_FROM_HOUR) local.toLocalDate().plusDays(1) else local.toLocalDate()
}

fun averageByNightDate(values: List<TimedValue>, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, Double> =
    values.groupBy { nightDate(it.at, zone) }.mapValues { (_, v) -> v.map { it.value }.average() }

fun dominantOriginByNightDate(values: List<TimedValue>, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, String> =
    values.filter { it.origin != null }
        .groupBy { nightDate(it.at, zone) }
        .mapValues { (_, day) ->
            val counts = day.groupingBy { it.origin!! }.eachCount()
            val top = counts.values.max()
            day.filter { counts.getValue(it.origin!!) == top }.maxBy { it.at }.origin!!
        }
