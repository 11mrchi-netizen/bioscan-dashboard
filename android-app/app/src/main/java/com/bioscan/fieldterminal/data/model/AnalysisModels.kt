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
    // Already synced daily by HealthConnectDailySyncRepository.syncVitals()
    // (OxygenSaturationRecord), same as hrv/rhr -- just never read anywhere,
    // added for the Cardio tab's SPO2 card.
    @SerialName("spo2_avg") val spo2: Double? = null,
    @SerialName("hr_timeseries") val hrTimeseries: List<HrPoint>? = null,
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
    // Redesign: the session type, so hydration accounting can tell a run from a strength session.
    val type: String? = null,
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
    @SerialName("morning_erection_quality") val morningErectionQuality: Int? = null,
    @SerialName("arousal_level") val arousalLevel: Int? = null,
)

// Analysis review phase G: what became of a planned session, for READY FOR's scorecard. Done means it was matched to a
// recorded session (or confirmed by hand); a planned day that passed without one counts as not done.
@Serializable
data class PlannedOutcomeRow(
    @SerialName("scheduled_date") val scheduledDate: String,
    val status: String = "planned",
    @SerialName("exercise_session_id") val exerciseSessionId: Long? = null,
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

// Analysis Layer 3 / R1. Read model for analysis_results rows of kind "series_break" (published by the analysis-run Edge
// Function). Only the field this app needs is decoded: the last CONFIRMED break date, the start of the current regime.
@Serializable
data class SeriesBreakResultRow(val key: String, val payload: SeriesBreakPayload = SeriesBreakPayload())

@Serializable
data class SeriesBreakPayload(val lastConfirmed: String? = null)

// Analysis Layer 3 / Phase 3. Read model for analysis_results rows of kind "relationship": one confirmatory hypothesis each
// (supabase/functions/_shared/analysis-core/relationship.ts). Every field is defaulted so an older or partial payload still decodes.
@Serializable
data class RelationshipResultRow(val key: String, val state: String = "insufficient", val payload: RelationshipPayload = RelationshipPayload())

// Analysis Layer 3 / Phase 4. Read model for the "typicality" row (key personal_normal). Every field is defaulted.
@Serializable
data class TypicalityResultRow(val key: String, val state: String = "building", val payload: TypicalityPayload = TypicalityPayload())

@Serializable
data class TypicalityDriver(val signal: String = "", val z: Double = 0.0, val direction: String = "")

@Serializable
data class TypicalityDay(
    val date: String = "",
    val state: String = "insufficient",
    val strain: Boolean = false,
    val excluded: Boolean = false,
    val drivers: List<TypicalityDriver> = emptyList(),
)

@Serializable
data class TypicalityLookalike(val date: String = "", val illness: Boolean = false)

// Analysis review phase G: the strain flag replayed on the person's history and checked against logged illness.
@Serializable
data class TypicalityScorecardPayload(
    // earns_its_place | no_better | unclear | not_yet
    val verdict: String = "not_yet",
    val onsets: Int = 0,
    val caught: Int = 0,
    val flags: Int = 0,
    val flagsBeforeOnset: Int = 0,
    val precision: Double? = null,
    val recall: Double? = null,
    val baseRate: Double? = null,
    val sentence: String = "",
)

@Serializable
data class TypicalityPayload(
    val baselineDays: Int = 0,
    val baselineNeed: Int = 45,
    val today: TypicalityDay? = null,
    val suggestIllnessLog: Boolean = false,
    val lookalikes: List<TypicalityLookalike> = emptyList(),
    val skipped: List<String> = emptyList(),
    val scorecard: TypicalityScorecardPayload? = null,
)

// Analysis review phase G: the personal fitness-fatigue spans (kind fitness_fatigue, key hrv). `adopted` is true only when
// they forecast tomorrow's HRV better than 42 and 7 on held-out weeks.
@Serializable
data class LoadSpanPair(val ctl: Double = 42.0, val atl: Double = 7.0)

@Serializable
data class FitnessFatiguePayload(
    // predictive | no_better | unclear | not_yet
    val state: String = "not_yet",
    val standard: LoadSpanPair = LoadSpanPair(),
    val personal: LoadSpanPair? = null,
    val adopted: Boolean = false,
    val heldOutDays: Int = 0,
    val summary: String = "",
)

@Serializable
data class FitnessFatigueRow(val key: String = "hrv", val state: String = "not_yet", val payload: FitnessFatiguePayload = FitnessFatiguePayload())

@Serializable
data class RelationshipEffect(val value: Double = 0.0, val lo: Double = 0.0, val hi: Double = 0.0, val unit: String = "")

// Analysis review phase D: one regression per question; the verdict (state) compares the 95% interval with the
// question's no-effect zone: clear | negligible | unclear | not_yet.
@Serializable
data class RelationshipEValue(val point: Double = 1.0, val ci: Double = 1.0)

@Serializable
data class RelationshipNegativeControl(val value: Double = 0.0, val lo: Double = 0.0, val hi: Double = 0.0, val unit: String = "", val clear: Boolean = false)

@Serializable
data class RelationshipReadiness(val have: Int = 0, val need: Int = 0, val unit: String = "days")

// Analysis review phase E: the interval the verdict used (a repeated confidence interval, wider while information is
// short, because every question is re-checked every night) and the partial-pooling estimate with the question's family.
@Serializable
data class VerdictInterval(val lo: Double = 0.0, val hi: Double = 0.0, val boundary: Double = 1.96, val information: Double = 1.0)

@Serializable
data class PooledEstimate(
    val family: String = "",
    val members: Int = 0,
    val value: Double = 0.0,
    val lo: Double = 0.0,
    val hi: Double = 0.0,
    val unit: String = "",
    val shrinkage: Double = 0.0,
    val probabilityBeyondZone: Double = 0.0,
)

// Analysis review phase E: one row of the append-only evidence ledger (every nightly verdict of every question).
@Serializable
data class LedgerEntry(
    @SerialName("question_id") val questionId: String = "",
    @SerialName("as_of") val asOf: String = "",
    val verdict: String = "not_yet",
    val estimate: Double? = null,
    val lo: Double? = null,
    val hi: Double? = null,
    val unit: String? = null,
)

// Analysis review phase E: a hint registered as a question of its own (registered_questions).
@Serializable
data class NewRegisteredQuestion(
    val exposure: String,
    val outcome: String,
    val lag: Int = 1,
    @SerialName("no_effect") val noEffect: Double,
    @SerialName("registered_on") val registeredOn: String,
    val source: String = "case_crossover",
)

@Serializable
data class RegisteredQuestion(
    val id: Long = 0,
    val exposure: String = "",
    val outcome: String = "",
    val lag: Int = 1,
    @SerialName("registered_on") val registeredOn: String = "",
    @SerialName("withdrawn_at") val withdrawnAt: String? = null,
)

@Serializable
data class RelationshipPayload(
    val id: String = "",
    val label: String = "",
    // hrv | rhr | sleep | energy: the evidence board's group.
    val outcome: String = "",
    val rung: String = "associated",
    val summary: String = "",
    val reason: String? = null,
    val n: Int = 0,
    val nOn: Int? = null,
    val nOff: Int? = null,
    val effect: RelationshipEffect? = null,
    // Half-width of the no-effect zone, in the effect's unit.
    val noEffect: Double? = null,
    val mde: Double? = null,
    val pAdjusted: Double? = null,
    val eValue: RelationshipEValue? = null,
    val negativeControl: RelationshipNegativeControl? = null,
    val readiness: RelationshipReadiness? = null,
    val adjustments: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val catalogueVersion: String = "",
    val verdictInterval: VerdictInterval? = null,
    val pooled: PooledEstimate? = null,
    // Registered questions only (kind registered_question).
    val registeredOn: String? = null,
    val daysSince: Int? = null,
)

// Analysis review phase D: event curves (kind event_curve), case-crossover tables (case_crossover) and lag curves
// (lag_curve), from supabase/functions/_shared/analysis-core/evidence.ts. Every field is defaulted.
@Serializable
data class EventCurveRow(val key: String, val state: String = "not_yet", val payload: EventCurvePayload = EventCurvePayload())

@Serializable
data class EventCurveDay(
    val k: Int = 0,
    // Event days minus comparable days without the event, display units.
    val mean: Double? = null,
    val lo: Double? = null,
    val hi: Double? = null,
    val chanceLo: Double? = null,
    val chanceHi: Double? = null,
    val n: Int = 0,
)

@Serializable
data class EventPeak(val k: Int = 0, val value: Double = 0.0)

@Serializable
data class EventCurvePayload(
    val id: String = "",
    val label: String = "",
    val exposure: String = "",
    val outcome: String = "",
    val unit: String = "",
    val state: String = "not_yet",
    val events: Int = 0,
    val need: Int = 8,
    val days: List<EventCurveDay> = emptyList(),
    val peak: EventPeak? = null,
    val backByDay: Int? = null,
    val differentBefore: Boolean = false,
    val summary: String = "",
)

@Serializable
data class CaseCrossoverResultRow(val key: String, val state: String = "not_yet", val payload: CaseCrossoverPayload = CaseCrossoverPayload())

@Serializable
data class CaseCrossoverEntry(
    val exposure: String = "",
    val label: String = "",
    val cases: Int = 0,
    val need: Int = 15,
    @SerialName("or") val oddsRatio: Double? = null,
    val lo: Double? = null,
    val hi: Double? = null,
    // more_often | less_often | no_clear_difference | not_yet
    val verdict: String = "not_yet",
)

@Serializable
data class CaseCrossoverPayload(
    val outcome: String = "",
    val side: String = "low",
    val text: String = "",
    val caseDays: Int = 0,
    val rows: List<CaseCrossoverEntry> = emptyList(),
)

@Serializable
data class LagCurveRow(val key: String, val state: String = "not_yet", val payload: LagCurvePayload = LagCurvePayload())

@Serializable
data class LagPoint(val lag: Int = 0, val value: Double = 0.0, val lo: Double = 0.0, val hi: Double = 0.0)

@Serializable
data class LagCumulative(val value: Double = 0.0, val lo: Double = 0.0, val hi: Double = 0.0)

@Serializable
data class DosePoint(val group: String = "", val n: Int = 0, val mean: Double = 0.0, val lo: Double = 0.0, val hi: Double = 0.0)

@Serializable
data class LagCurvePayload(
    val id: String = "",
    val label: String = "",
    val exposure: String = "",
    val outcome: String = "",
    val unit: String = "",
    val perText: String = "",
    val state: String = "not_yet",
    val rows: Int = 0,
    val exposedDays: Int = 0,
    val lags: List<LagPoint> = emptyList(),
    val cumulative: LagCumulative? = null,
    val clearLags: List<Int> = emptyList(),
    val lastClearLag: Int? = null,
    val dose: List<DosePoint>? = null,
    val summary: String = "",
)

// Analysis review phase C. Read model for analysis_results rows of kind "expected_band", key = metric (hrv, rhr,
// sleep_hours): the band each recent morning was expected to fall in, and the band for the morning after the run
// (supabase/functions/_shared/analysis-core/expectedBand.ts). Every field is defaulted so a partial payload still decodes.
@Serializable
data class ExpectedBandRow(val key: String, val state: String = "building", val payload: ExpectedBandPayload = ExpectedBandPayload())

@Serializable
data class BandPoint(
    val date: String = "",
    val value: Double? = null,
    val expected: Double? = null,
    val lo80: Double? = null,
    val hi80: Double? = null,
    val lo99: Double? = null,
    val hi99: Double? = null,
    // Model space (log for HRV), so a reading that arrives after the run can be placed.
    val mu: Double? = null,
    val sigma: Double? = null,
    val z: Double? = null,
    // "extreme" | "run" | null, with side "low" | "high".
    val flag: String? = null,
    val side: String? = null,
    val excluded: Boolean = false,
)

@Serializable
data class BandParams(
    val alpha: Double = 0.0,
    val gamma: Double = 0.0,
    val sigma: Double? = null,
    val df: Int = 0,
    val q80: Double? = null,
    val q99: Double? = null,
)

@Serializable
data class BandCalibration(val days: Int = 0, val inside80: Double? = null, val inside99: Double? = null)

@Serializable
data class BandEwma(
    val lambda: Double = 0.3,
    val limit: Double = 1.0,
    val value: Double = 0.0,
    val date: String? = null,
    val runsActive: Boolean = false,
)

@Serializable
data class ExpectedBandPayload(
    val metric: String = "",
    val unit: String = "",
    // "ln" | "none"
    val transform: String = "none",
    // "higher" | "lower" | "range"
    val better: String = "higher",
    // "building" | "provisional" | "ready"
    val state: String = "building",
    val asOf: String = "",
    val regimeStart: String? = null,
    val lastObserved: String? = null,
    val have: Int = 0,
    val need: Int = 21,
    val params: BandParams = BandParams(),
    val calibration: BandCalibration = BandCalibration(),
    val recent: List<BandPoint> = emptyList(),
    val next: BandPoint? = null,
    val ewma: BandEwma = BandEwma(),
)
