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
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String? = null,
    val focus: List<FocusEntryDto> = emptyList(),
)
