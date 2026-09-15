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
data class TimedValue(val at: Instant, val value: Double)

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
