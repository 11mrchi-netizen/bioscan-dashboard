package com.bioscan.fieldterminal.domain

import java.time.LocalDate

// Phase A3 (Analysis Layer). Framework-free mirror of Supabase's
// `focus_quality` enum (see ROADMAP.md Phase A1) -- kept as its own Kotlin
// enum rather than a raw string so callers can't typo a quality name past
// the compiler, matching the same reasoning that made the DB side an enum
// instead of free text.
enum class FocusQuality {
    Hypertrophy, Strength, Power, AerobicBase, RaceSpecificEndurance,
    PeakingRealization, SportSkill, RecoveryDeload, Maintenance;

    companion object {
        fun fromDb(value: String): FocusQuality? = when (value) {
            "hypertrophy" -> Hypertrophy
            "strength" -> Strength
            "power" -> Power
            "aerobic_base" -> AerobicBase
            "race_specific_endurance" -> RaceSpecificEndurance
            "peaking_realization" -> PeakingRealization
            "sport_skill" -> SportSkill
            "recovery_deload" -> RecoveryDeload
            "maintenance" -> Maintenance
            else -> null
        }
    }
}

enum class FocusRole { Primary, Maintained }

data class FocusEntry(val quality: FocusQuality, val weight: Double, val role: FocusRole)

// Training Cycle Framing doc, section 4: a mesocycle -- weeks to ~3 months,
// one or more stated foci with weights (concurrent training gets equal
// standing with classic single-focus blocks, not forced into one box).
// `endDate == null` means "ongoing," evaluable provisionally per that doc's
// own explicit call.
//
// DAV-291: goalMetric/startingValue/targetValue extend this same mesocycle
// (see docs/user-profile-milestone/01-canonical-contracts-audit.md) instead
// of a parallel "training block" model -- a cycle either has a stated
// numeric goal or it doesn't. currentValue is never stored here: it's
// whatever the live metric reads right now, passed into progressFraction()
// at render time, not a column that would drift stale between syncs.
data class TrainingCycle(
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val focus: List<FocusEntry>,
    val goalMetric: String? = null,
    val startingValue: Double? = null,
    val targetValue: Double? = null,
) {
    fun isActiveOn(date: LocalDate): Boolean =
        !date.isBefore(startDate) && (endDate == null || !date.isAfter(endDate))
}

// Direction-agnostic on purpose: whether the goal is "get faster" (target <
// starting) or "lift heavier" (target > starting), measuring progress as how
// far `current` has moved from `starting` toward `target` self-corrects
// either way -- no separate higher-is-better/lower-is-better flag needed.
// Null when there's no goal, or no real distance between starting and target
// to measure progress across. Clamped to [0, 1]: overshooting the target
// still reads as "done," not ">100%."
fun progressFraction(startingValue: Double?, targetValue: Double?, currentValue: Double?): Double? {
    if (startingValue == null || targetValue == null || currentValue == null) return null
    val span = targetValue - startingValue
    if (span == 0.0) return null
    return ((currentValue - startingValue) / span).coerceIn(0.0, 1.0)
}
