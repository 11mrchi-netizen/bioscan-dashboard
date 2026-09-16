package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Phase A2 (Analysis Layer). Read models for data/AnalysisRepository.kt --
// separate from StatusModels.kt's WearableDailyRow/SleepDailyRow, which
// select a narrower column set (limit(10), no bedtime/wake_time/respiratory
// rate) tuned for the BodyConsole readiness display, not the 60-day lookback
// and extra sleep-timing columns the Evaluation Method Spec's formulas need.
@Serializable
data class WearableAnalysisRow(
    val date: String,
    val hrv: Double? = null,
    val rhr: Double? = null,
)

@Serializable
data class SleepAnalysisRow(
    val date: String,
    val hours: Double? = null,
    val bedtime: String? = null,
    @SerialName("wake_time") val wakeTime: String? = null,
    @SerialName("respiratory_rate") val respiratoryRate: Double? = null,
)

// body_metrics has no read path anywhere in this app before this phase --
// see ROADMAP.md's Phase A2 notes.
@Serializable
data class BodyMetricsAnalysisRow(
    val date: String,
    @SerialName("weight_kg") val weightKg: Double? = null,
    @SerialName("body_fat_pct") val bodyFatPct: Double? = null,
)

// Phase A4. Narrower than TrainingRepository's own ExerciseSessionRow --
// this needs only what session_load's formula (duration_min * rpe) uses,
// plus start_time to bucket by date.
@Serializable
data class TrainingLoadSessionRow(
    @SerialName("start_time") val startTime: String,
    @SerialName("duration_min") val durationMin: Double? = null,
    val rpe: Int? = null,
)

// Phase A4 (Category 3). The Log tab's own LogRepository/LogWellbeingRow
// already reads wellbeing_daily, but only the latest N rows for the feed --
// this is the separate 60-day-lookback shape the Evaluation Method Spec's
// formulas need, same reasoning as WearableAnalysisRow/SleepAnalysisRow.
@Serializable
data class WellbeingAnalysisRow(
    val date: String,
    val energy: Int? = null,
    val mood: Int? = null,
    val stress: Int? = null,
    val soreness: Int? = null,
)

// Phase A4 (Category 8). Bristol half.
@Serializable
data class StoolAnalysisRow(
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("bristol_type") val bristolType: Int? = null,
)

// Phase A4 (Category 8). OSTRC-H2 half.
@Serializable
data class OstrcAnalysisRow(
    @SerialName("check_date") val checkDate: String,
    @SerialName("body_area") val bodyArea: String,
    @SerialName("severity_score") val severityScore: Int? = null,
)
