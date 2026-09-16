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
data class TrainingCycle(val startDate: LocalDate, val endDate: LocalDate?, val focus: List<FocusEntry>) {
    fun isActiveOn(date: LocalDate): Boolean =
        !date.isBefore(startDate) && (endDate == null || !date.isAfter(endDate))
}
