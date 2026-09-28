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
    // Live check: synced daily for 1,000+ real days, never read anywhere --
    // added for the Cardio tab's STEPS card.
    val steps: Double? = null,
)

@Serializable
data class SleepAnalysisRow(
    val date: String,
    val hours: Double? = null,
    val bedtime: String? = null,
    @SerialName("wake_time") val wakeTime: String? = null,
    @SerialName("respiratory_rate") val respiratoryRate: Double? = null,
    // Live check: already synced by HealthConnectDailySyncRepository.syncSleep()
    // (real per-night deep/rem/light minutes from SleepSessionRecord.stages),
    // just never read anywhere -- added for the Recovery tab's sleep-phases card.
    @SerialName("deep_min") val deepMin: Double? = null,
    @SerialName("rem_min") val remMin: Double? = null,
    @SerialName("light_min") val lightMin: Double? = null,
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
// DAV-205 (24/9 fixes): avg_hr/max_hr added as a TRIMP fallback input for
// the ~99.9% of sessions with no RPE (see TrainingTileScreen.kt's
// TrainingLoadSection()) -- both already synced by
// HealthConnectExerciseSyncRepository, just never selected here before.
@Serializable
data class TrainingLoadSessionRow(
    @SerialName("start_time") val startTime: String,
    @SerialName("duration_min") val durationMin: Double? = null,
    val rpe: Int? = null,
    @SerialName("avg_hr") val avgHr: Double? = null,
    @SerialName("max_hr") val maxHr: Double? = null,
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

// Phase A4 (Category 9). Joined client-side by draw_id -> draw_date
// (matching this project's existing "no relational embedding, join in
// Kotlin" convention -- see TrainingCyclesRepository for the same pattern).
@Serializable
data class LabDrawAnalysisRow(
    val id: Long,
    @SerialName("draw_date") val drawDate: String,
)

@Serializable
data class LabResultAnalysisRow(
    @SerialName("draw_id") val drawId: Long,
    @SerialName("marker_name") val markerName: String,
    val value: Double? = null,
    val unit: String? = null,
    @SerialName("ref_low") val refLow: Double? = null,
    @SerialName("ref_high") val refHigh: Double? = null,
)
