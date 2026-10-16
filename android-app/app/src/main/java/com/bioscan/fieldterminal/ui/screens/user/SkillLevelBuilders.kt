package com.bioscan.fieldterminal.ui.screens.user

import com.bioscan.fieldterminal.data.ConditioningHistory
import com.bioscan.fieldterminal.data.RunningHistory
import com.bioscan.fieldterminal.data.StrengthHistory
import com.bioscan.fieldterminal.domain.levels.SkillLevel
import com.bioscan.fieldterminal.domain.levels.conditioningSkillLevel
import com.bioscan.fieldterminal.domain.levels.ratedLifts
import com.bioscan.fieldterminal.domain.levels.runningSkillLevel
import com.bioscan.fieldterminal.domain.levels.strengthSkillLevel
import com.bioscan.fieldterminal.domain.liftE1rmSeries
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// Glue between the loaded histories and the pure level functions in domain/levels/SkillLevel.kt.

// Pace comparisons only use road runs of at least 3 km: shorter efforts and trail terrain are not
// comparable with a normal run.
private const val MIN_PACE_RUN_KM = 3.0
private const val RECENT_DAYS = 28L

fun runningLevelOf(history: RunningHistory, vo2Percentile: Double?, asOf: LocalDate = LocalDate.now()): SkillLevel {
    val road = history.roadRuns.filter { it.distanceKm >= MIN_PACE_RUN_KM && !it.date.isAfter(asOf) }
    val (recent, older) = road.partition { ChronoUnit.DAYS.between(it.date, asOf) < RECENT_DAYS }
    return runningSkillLevel(
        vo2max = history.latestVo2Max,
        vo2Percentile = vo2Percentile,
        recentPaceMinPerKm = recent.minOfOrNull { it.paceMinPerKm },
        paceHistoryMinPerKm = older.map { it.paceMinPerKm },
    )
}

fun strengthLevelOf(history: StrengthHistory, asOf: LocalDate = LocalDate.now()): SkillLevel =
    strengthSkillLevel(ratedLifts(liftE1rmSeries(history.sets), history.bodyWeightKg, asOf), bodyWeightMissing = history.bodyWeightKg == null)

fun conditioningLevelOf(history: ConditioningHistory, asOf: LocalDate = LocalDate.now()): SkillLevel =
    conditioningSkillLevel(history.entries.map { it.date }, asOf)
