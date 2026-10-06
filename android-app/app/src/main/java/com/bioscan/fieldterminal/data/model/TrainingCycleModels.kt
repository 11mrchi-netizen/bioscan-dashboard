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
    val notes: String? = null,
)

// Flat DTO for v_training_block_metrics view rows. Parallel path from
// TrainingCycleRow -- TB cycles store focus as a jsonb object (not array),
// so the view extracts the fields directly and this DTO never needs to parse
// focus at all.
@Serializable
data class TrainingBlockSummaryRow(
    val id: Long,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    val template: String? = null,
    @SerialName("tb_category") val tbCategory: String? = null,
    @SerialName("primary_goal") val primaryGoal: String? = null,
    val progression: String? = null,
    @SerialName("block_days") val blockDays: Int? = null,
    // Tier 1
    @SerialName("strength_sessions") val strengthSessions: Int = 0,
    @SerialName("conditioning_sessions") val conditioningSessions: Int = 0,
    @SerialName("endurance_sessions") val enduranceSessions: Int = 0,
    @SerialName("avg_conditioning_hr") val avgConditioningHr: Double? = null,
    @SerialName("early_hr") val earlyHr: Double? = null,
    @SerialName("late_hr") val lateHr: Double? = null,
    @SerialName("peak_hr") val peakHr: Double? = null,
    @SerialName("conditioning_hr_delta") val conditioningHrDelta: Double? = null,
    @SerialName("linked_wearable_count") val linkedWearableCount: Int = 0,
    // Tier 2
    @SerialName("rhr_avg") val rhrAvg: Double? = null,
    @SerialName("rhr_start") val rhrStart: Double? = null,
    @SerialName("rhr_end") val rhrEnd: Double? = null,
    @SerialName("rhr_delta") val rhrDelta: Double? = null,
    @SerialName("rhr_days_count") val rhrDaysCount: Int = 0,
    @SerialName("hrv_avg") val hrvAvg: Double? = null,
    @SerialName("hrv_start") val hrvStart: Double? = null,
    @SerialName("hrv_end") val hrvEnd: Double? = null,
    @SerialName("hrv_delta") val hrvDelta: Double? = null,
    @SerialName("hrv_days_count") val hrvDaysCount: Int = 0,
    @SerialName("vo2max_first") val vo2maxFirst: Double? = null,
    @SerialName("vo2max_last") val vo2maxLast: Double? = null,
    @SerialName("vo2max_delta") val vo2maxDelta: Double? = null,
    @SerialName("vo2max_days_count") val vo2maxDaysCount: Int = 0,
    @SerialName("spo2_avg") val spo2Avg: Double? = null,
    // Tier 3
    @SerialName("prev_avg_conditioning_hr") val prevAvgConditioningHr: Double? = null,
    @SerialName("prev_rhr_avg") val prevRhrAvg: Double? = null,
    @SerialName("prev_hrv_avg") val prevHrvAvg: Double? = null,
    @SerialName("prev_vo2max") val prevVo2max: Double? = null,
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
    val notes: String? = null,
)
