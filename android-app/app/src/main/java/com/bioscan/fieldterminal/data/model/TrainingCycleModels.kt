package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Phase A3 (Analysis Layer). Mirrors training_cycles' real jsonb shape
// (focus jsonb: [{quality, weight, role}], see ROADMAP.md Phase A1) --
// kept as loose strings here since this is the raw-wire DTO; parsing into
// the real FocusQuality/FocusRole enums (and dropping anything that
// doesn't parse, rather than crashing on it) happens in
// data/TrainingCyclesRepository.kt's mapper.
@Serializable
data class FocusEntryDto(val quality: String, val weight: Double, val role: String)

@Serializable
data class TrainingCycleRow(
    val id: Long = 0,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String? = null,
    val focus: List<FocusEntryDto> = emptyList(),
    // DAV-291: extends this existing mesocycle row rather than a parallel
    // training-block table (see docs/user-profile-milestone/01-canonical-contracts-audit.md).
    // All three null together means "no stated numeric goal for this cycle."
    @SerialName("goal_metric") val goalMetric: String? = null,
    @SerialName("starting_value") val startingValue: Double? = null,
    @SerialName("target_value") val targetValue: Double? = null,
)

// Separate from TrainingCycleRow (which carries `id`) for the same reason
// NewInjuryRow is separate from the read DTO elsewhere in this app: an
// insert/update body must never serialize an `id` field -- writing 0 into a
// bigint primary key column would be a real, silent data-corrupting bug, not
// a cosmetic one.
@Serializable
data class NewTrainingCycleRow(
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String? = null,
    val focus: List<FocusEntryDto> = emptyList(),
    @SerialName("goal_metric") val goalMetric: String? = null,
    @SerialName("starting_value") val startingValue: Double? = null,
    @SerialName("target_value") val targetValue: Double? = null,
)
