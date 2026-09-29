package com.bioscan.fieldterminal.domain

// DAV-294: canonical shape for conditioning work that isn't an ordinary
// run/ride/swim or a structured strength session -- rowing, assault bike,
// sleds, kettlebell complexes, circuits, intervals. No ingestion path writes
// this yet (see docs/user-profile-milestone/01-canonical-contracts-audit.md)
// -- this is the target shape a future exercise_sessions.details parser
// fills, following the same "type-specific keys inside the existing jsonb
// details column" convention ExerciseSessionDetails already uses for
// run/strength (data/model/ExerciseSessionModels.kt) rather than a new table.
enum class ConditioningModality { Rowing, AssaultBike, Sled, KettlebellComplex, Circuit, Interval, Other }

data class ConditioningSession(
    val modality: ConditioningModality,
    val durationMin: Double? = null,
    val distanceM: Double? = null,
    val workSec: Double? = null,
    val restSec: Double? = null,
    val avgHr: Double? = null,
    // Unit depends on modality (e.g. seconds/500m for rowing, watts for an
    // assault bike) -- `unit` names it rather than assuming one meaning.
    val avgPaceOrPower: Double? = null,
    val unit: String? = null,
    val calories: Double? = null,
    val source: String,
)

// Work:rest ratio is the one derived "density" metric this milestone actually
// needs (DAV-294's own acceptance criteria mention it by name). Benchmark
// performance and conditioning achievements wait for real data before a
// formula is worth writing -- a real gap, not a guess, same convention
// StrengthCard's heat map already follows for an unresolved exercise.
fun workRestRatio(session: ConditioningSession): Double? {
    val work = session.workSec ?: return null
    val rest = session.restSec ?: return null
    if (rest <= 0) return null
    return work / rest
}
