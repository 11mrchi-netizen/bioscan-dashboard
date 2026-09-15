package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.ExerciseSessionRow
import com.bioscan.fieldterminal.data.model.Vo2MaxRow
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

// Phase G3: generalized from run-only (the old `runs` table, one RunRow per
// row) to any exercise type (`exercise_sessions`). These functions only
// ever cared about distance/duration, never "is this specifically a run" --
// the endurance-vs-strength type filtering now happens once in
// TrainingRepository when it builds the lists passed in here, so this file
// stays exactly as generic as it always effectively was.
//
// `exercise_sessions.start_time` is a real timestamptz (even migrated
// pre-Health-Connect rows carry a nominal-but-real one), so "which day did
// this happen" is a real OffsetDateTime parse now, not a bare LocalDate.

private fun localDateOf(startTime: String): LocalDate = OffsetDateTime.parse(startTime).toLocalDate()

fun sumDistanceKmSince(sessions: List<ExerciseSessionRow>, today: LocalDate, days: Long): Double =
    sessions.filter { s -> ChronoUnit.DAYS.between(localDateOf(s.startTime), today) < days }
        .sumOf { it.distanceKm ?: 0.0 }

fun longestRunKm(sessions: List<ExerciseSessionRow>): Double? = sessions.mapNotNull { it.distanceKm }.maxOrNull()

// Health Connect gives distance + duration, not a stored pace -- derived
// here the same way the old `runs.pace_min_per_km` column was presumably
// computed in the first place, rather than carrying a redundant column.
fun averagePaceMinPerKmSince(sessions: List<ExerciseSessionRow>, today: LocalDate, days: Long): Double? {
    val paces = sessions.filter { s -> ChronoUnit.DAYS.between(localDateOf(s.startTime), today) < days }
        .mapNotNull { s ->
            val distance = s.distanceKm
            val duration = s.durationMin
            if (distance != null && distance > 0 && duration != null) duration / distance else null
        }
    return if (paces.isEmpty()) null else paces.average()
}

// Ported 1:1 from index.html's `[...wearable.vo2].reverse().find(v=>v!==null)`
// -- most recent non-null reading, not necessarily the very latest row.
fun latestNonNullVo2Max(rows: List<Vo2MaxRow>): Double? =
    rows.asReversed().firstNotNullOfOrNull { it.vo2max }
