package com.bioscan.fieldterminal.domain.training

import com.bioscan.fieldterminal.data.model.StrengthSetDto
import com.bioscan.fieldterminal.domain.estimatedOneRepMax
import com.bioscan.fieldterminal.domain.median
import java.time.LocalDate

// DAV-343. Max snapshots are append-only records (athlete_maxes) with a source and a
// derivation. This file produces the *estimates* from logged history; tests, manual
// entries and progressions are written directly by the caller and always win over an
// estimate for the same exercise when they are more recent.

// Mirrors athlete_maxes.source.
enum class MaxSource(val db: String) {
    Test("test"),
    ImpliedLoggedPercent("implied_logged_percent"),
    Estimate("estimate"),
    Manual("manual"),
    Progression("progression"),
}

data class DatedSet(val date: LocalDate, val reps: Int, val weightKg: Double, val percentOneRm: Double? = null)

data class MaxEstimate(val kg: Double, val source: MaxSource, val asOf: LocalDate, val basedOnSets: Int)

// Weight logged for weighted calisthenics is the *added* load; the max the programs
// use is on the total (added + bodyweight).
fun withBodyweight(sets: List<DatedSet>, bodyweightKg: Double): List<DatedSet> =
    sets.map { it.copy(weightKg = it.weightKg + bodyweightKg) }

// Priority, from the user's own recorded intent down to a formula:
// 1. percent_1rm the user logged: weight / percent is the max they programmed from.
//    The most recent session that carries one is used (median across its sets).
// 2. Epley on the best recent set, reps <= 12 only (the same gate StrengthLoad.kt uses).
// Null when neither exists; nothing is guessed.
fun estimateMaxFromHistory(sets: List<DatedSet>, today: LocalDate, lookbackDays: Long = 84): MaxEstimate? {
    val recent = sets.filter { !it.date.isAfter(today) && !it.date.isBefore(today.minusDays(lookbackDays)) && it.weightKg > 0 }

    val withPercent = recent.filter { (it.percentOneRm ?: 0.0) > 0.0 }
    if (withPercent.isNotEmpty()) {
        val day = withPercent.maxOf { it.date }
        val implied = withPercent.filter { it.date == day }.map { it.weightKg / (it.percentOneRm!! / 100.0) }
        return MaxEstimate(median(implied), MaxSource.ImpliedLoggedPercent, day, implied.size)
    }

    val best = recent.mapNotNull { s ->
        estimatedOneRepMax(StrengthSetDto(reps = s.reps, weightKg = s.weightKg))?.let { it to s.date }
    }.maxByOrNull { it.first } ?: return null
    return MaxEstimate(best.first, MaxSource.Estimate, best.second, 1)
}

// Block-to-block progression: add the lift's increment to the 1RM, unless the last
// block was not completed (then keep the same number). Increments in kg, already
// converted from the definition's lb range.
fun forcedProgression(currentKg: Double, incrementKg: Double, completedAllReps: Boolean): Double =
    if (completedAllReps) currentKg + incrementKg else currentKg
