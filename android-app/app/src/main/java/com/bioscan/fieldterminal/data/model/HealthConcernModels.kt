package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Health concerns (docs/health-concerns/01-plan.md): the raw tables the app writes (supabase/migrations/
// 20261101_health_concerns.sql) and the payloads analysis-run publishes into analysis_results
// (supabase/functions/_shared/analysis-core/runHealth.ts). Field names follow those two files; every payload field has a
// default, so a payload from an older or newer engine still decodes.

@Serializable
data class RegionRef(val code: String, val side: String? = null)

@Serializable
data class HealthConcernRow(
    val id: String,
    val title: String,
    val kind: String,
    @SerialName("onset_type") val onsetType: String = "unknown",
    val regions: List<RegionRef> = emptyList(),
    val symptoms: List<String> = emptyList(),
    @SerialName("excludes_from_baselines") val excludesFromBaselines: Boolean = false,
    @SerialName("archived_at") val archivedAt: String? = null,
    @SerialName("legacy_source") val legacySource: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class NewHealthConcernRow(
    val id: String,
    val title: String,
    val kind: String,
    @SerialName("onset_type") val onsetType: String,
    val regions: List<RegionRef>,
    val symptoms: List<String>,
    @SerialName("excludes_from_baselines") val excludesFromBaselines: Boolean,
)

// Insert rows go out in one request, and PostgREST fills a key one row leaves out with NULL (not the column default) when
// another row has it; a default-valued field is left out unless @EncodeDefault, so every non-null default is always sent.
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class HealthObservationInsert(
    val id: String,
    @SerialName("observed_at") val observedAt: String,
    @SerialName("local_date") val localDate: String,
    @SerialName("utc_offset_min") val utcOffsetMin: Int? = null,
    @SerialName("concern_id") val concernId: String? = null,
    val scope: String,
    @SerialName("symptom_free") val symptomFree: Boolean,
    @EncodeDefault val recall: String = "today",
    @SerialName("recall_from") val recallFrom: String? = null,
    @SerialName("function_impact") val functionImpact: Int? = null,
    @SerialName("change_vs_last") val changeVsLast: String? = null,
    @EncodeDefault val onset: Boolean = false,
    val mechanism: String? = null,
    @SerialName("session_id") val sessionId: Long? = null,
    val timing: String? = null,
    val note: String? = null,
    val supersedes: String? = null,
    @EncodeDefault val retracted: Boolean = false,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class HealthSignalInsert(
    @SerialName("observation_id") val observationId: String,
    @SerialName("concern_id") val concernId: String? = null,
    val symptom: String,
    val region: String? = null,
    val side: String? = null,
    val severity: Int? = null,
    @EncodeDefault val details: JsonObject = JsonObject(emptyMap()),
    @SerialName("temperature_c") val temperatureC: Double? = null,
)

@Serializable
data class HealthObservationRow(
    val id: String,
    @SerialName("observed_at") val observedAt: String,
    @SerialName("local_date") val localDate: String,
    @SerialName("concern_id") val concernId: String? = null,
    val scope: String,
    @SerialName("symptom_free") val symptomFree: Boolean,
    val recall: String = "today",
    @SerialName("recall_from") val recallFrom: String? = null,
    @SerialName("function_impact") val functionImpact: Int? = null,
    @SerialName("superseded_at") val supersededAt: String? = null,
    val retracted: Boolean = false,
)

@Serializable
data class EpisodeMarkInsert(
    @SerialName("concern_id") val concernId: String,
    val kind: String,
    val date: String,
    val note: String? = null,
)

// ---- published payloads ----

@Serializable
data class SafetyPromptDto(val code: String = "", val text: String = "")

@Serializable
data class EpisodeBrief(val start: String = "", val peakSeverity: Int? = null)

@Serializable
data class ConcernOverviewDto(
    val concernId: String = "",
    val title: String = "",
    val kind: String = "anatomical",
    val onsetType: String = "unknown",
    val regions: List<RegionRef> = emptyList(),
    val archived: Boolean = false,
    val state: String = "unobserved",
    val stateReason: String = "",
    val lastObserved: String? = null,
    val currentEpisode: EpisodeBrief? = null,
    val lastSeverity: Int? = null,
    val episodes: Int = 0,
    val safety: List<SafetyPromptDto> = emptyList(),
    val dataQuality: String = "THIN",
    val findings: Int = 0,
)

@Serializable
data class UnassignedSignalDto(val date: String = "", val signal: UnassignedSignalBody = UnassignedSignalBody())

@Serializable
data class UnassignedSignalBody(val symptom: String = "", val region: String? = null, val side: String? = null, val severity: Int? = null)

@Serializable
data class HealthOverviewDto(
    val asOf: String = "",
    val concerns: List<ConcernOverviewDto> = emptyList(),
    val unassigned: List<UnassignedSignalDto> = emptyList(),
    val lastCheckin: String? = null,
)

@Serializable
data class HealthOverviewRow(val key: String = "all", val state: String = "", val payload: HealthOverviewDto = HealthOverviewDto())

@Serializable
data class DurationRange(val min: Int = 0, val max: Int = 0)

@Serializable
data class EpisodeDto(
    val id: String = "",
    val start: String = "",
    val startEarliest: String? = null,
    val end: String? = null,
    val endEarliest: String? = null,
    val open: Boolean = false,
    val endedBy: String? = null,
    val gap: Boolean = false,
    val boundaryUncertain: Boolean = false,
    val durationDays: DurationRange = DurationRange(),
    val peakSeverity: Int? = null,
    val peakDate: String? = null,
    val meanSeverity: Double? = null,
    val severityDays: Double = 0.0,
    val impactDays: Int = 0,
    val observedDays: Int = 0,
    val spanDays: Int = 0,
    val coverage: Double = 0.0,
    val daysPeakToEnd: Int? = null,
    val sessionIds: List<Long> = emptyList(),
)

@Serializable
data class RecurrenceDto(
    val episodes: Int = 0,
    val recurrences: Int = 0,
    val episodesLast90: Int = 0,
    val episodesLast365: Int = 0,
    val intervals: List<DurationRange> = emptyList(),
    val medianInterval: Double? = null,
    val within30: Int = 0,
    val within90: Int = 0,
)

@Serializable
data class HealthQualityDto(
    val coverage30: Double = 0.0,
    val coverage90: Double = 0.0,
    val symptomFreeObservedDays90: Int = 0,
    val recalledShare90: Double = 0.0,
    val informativeMissingness: Double? = null,
    val word: String = "THIN",
)

@Serializable
data class DailySeriesDto(
    val dates: List<String> = emptyList(),
    val severity: List<Int?> = emptyList(),
    /** 0 unobserved, 1 checked in, 2 recalled. */
    val observed: List<Int> = emptyList(),
    val present: List<Int> = emptyList(),
    val impact: List<Int?> = emptyList(),
)

@Serializable
data class ContextLanesDto(
    val dates: List<String> = emptyList(),
    val runKm: List<Double> = emptyList(),
    val trailKm: List<Double> = emptyList(),
    val strengthSessions: List<Int> = emptyList(),
    val load7: List<Double?> = emptyList(),
    val sleepHours: List<Double?> = emptyList(),
    val hrvDeviation: List<Double?> = emptyList(),
)

@Serializable
data class BeforeSessionDto(val id: Long = 0, val date: String = "", val type: String = "", val km: Double? = null, val minutes: Double? = null, val flags: List<String> = emptyList())

@Serializable
data class BeforeComparisonDto(val id: String = "", val label: String = "", val value: Double = 0.0, val typical: Double? = null, val rank: String? = null, val unit: String = "")

@Serializable
data class BeforeEpisodeDto(
    val episodeStart: String = "",
    val sessions: List<BeforeSessionDto> = emptyList(),
    val comparisons: List<BeforeComparisonDto> = emptyList(),
    val sentences: List<String> = emptyList(),
)

@Serializable
data class ConcernStateDto(
    val concernId: String = "",
    val title: String = "",
    val kind: String = "anatomical",
    val onsetType: String = "unknown",
    val regions: List<RegionRef> = emptyList(),
    val archived: Boolean = false,
    val asOf: String = "",
    val state: String = "unobserved",
    val stateReason: String = "",
    val lastObserved: String? = null,
    val episodes: List<EpisodeDto> = emptyList(),
    val currentEpisode: EpisodeDto? = null,
    val lastEpisode: EpisodeDto? = null,
    val recurrence: RecurrenceDto = RecurrenceDto(),
    val quality: HealthQualityDto = HealthQualityDto(),
    val safety: List<SafetyPromptDto> = emptyList(),
    val resolutionDays: Int = 7,
    val daily: DailySeriesDto = DailySeriesDto(),
    val lanes: ContextLanesDto = ContextLanesDto(),
    val before: List<BeforeEpisodeDto> = emptyList(),
    val exposuresAsked: List<String> = emptyList(),
)

@Serializable
data class ConcernStateRow(val key: String = "", val state: String = "", val payload: ConcernStateDto = ConcernStateDto())

@Serializable
data class FindingLinksDto(val episodes: List<String> = emptyList(), val sessions: List<Long> = emptyList(), val dates: List<String> = emptyList())

@Serializable
data class FindingSessionDto(
    val exposedSessions: Int = 0,
    val exposedFollowed: Int = 0,
    val otherSessions: Int = 0,
    val otherFollowed: Int = 0,
    val rr: Double? = null,
    val lo: Double? = null,
    val hi: Double? = null,
)

@Serializable
data class FindingEvidenceDto(
    val text: String = "",
    val cases: Int? = null,
    val exposedCases: Int? = null,
    val expectedExposed: Double? = null,
    val referenceDays: Int? = null,
    val exposedReferenceDays: Int? = null,
    @SerialName("or") val oddsRatio: Double? = null,
    val lo: Double? = null,
    val hi: Double? = null,
    val q: Double? = null,
    val window: String? = null,
    val session: FindingSessionDto? = null,
    val links: FindingLinksDto = FindingLinksDto(),
)

@Serializable
data class NextStepDto(val kind: String = "none", val text: String = "")

@Serializable
data class FindingDto(
    val questionId: String = "",
    val concernId: String = "",
    val exposure: String? = null,
    val status: String = "insufficient",
    val weakening: Boolean = false,
    val rung: String = "observed",
    val exploratory: Boolean = true,
    val what: String = "",
    val evidence: FindingEvidenceDto = FindingEvidenceDto(),
    val explanations: List<String> = emptyList(),
    val uncertainty: String = "",
    val nextStep: NextStepDto = NextStepDto(),
    val firstPossible: String? = null,
    val dataQuality: String = "THIN",
)

@Serializable
data class FindingRow(val key: String = "", val state: String = "", val payload: FindingDto = FindingDto())

@Serializable
data class TrialWindowDto(val from: String = "", val to: String = "", val coverage: Double = 0.0, val value: Double? = null, val flares: Int? = null, val exposure: Double? = null)

@Serializable
data class TrialEffectDto(val kind: String = "ratio", val value: Double? = null, val lo: Double = 0.0, val hi: Double? = null, val unit: String = "")

@Serializable
data class TrialAdherenceDto(val days: Int = 0, val yes: Int = 0, val partial: Int = 0, val no: Int = 0, val share: Double? = null)

@Serializable
data class TrialReadDto(
    val interventionId: String = "",
    val concernId: String = "",
    val name: String = "",
    val hypothesis: String = "",
    val state: String = "interim",
    val outcome: String = "",
    val pre: TrialWindowDto = TrialWindowDto(),
    val post: TrialWindowDto = TrialWindowDto(),
    val effect: TrialEffectDto? = null,
    val word: String? = null,
    val favourable: Boolean? = null,
    val adherence: TrialAdherenceDto = TrialAdherenceDto(),
    val explanations: List<String> = emptyList(),
    val summary: String = "",
    val evaluateOn: String = "",
)

@Serializable
data class TrialReadRow(val key: String = "", val state: String = "", val payload: TrialReadDto = TrialReadDto())

@Serializable
data class NewTrialIntervention(
    val id: String,
    val domain: String,
    @SerialName("subject_type") val subjectType: String,
    @SerialName("subject_key") val subjectKey: String,
    val name: String,
    @SerialName("lifecycle_state") val lifecycleState: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("evaluate_on") val evaluateOn: String,
    val source: String = "health_concerns",
)

@Serializable
data class NewInterventionConcern(
    @SerialName("intervention_id") val interventionId: String,
    @SerialName("concern_id") val concernId: String,
    val hypothesis: String,
    val outcome: String,
    @SerialName("exposure_unit") val exposureUnit: String,
    @SerialName("pre_days") val preDays: Int,
)

@Serializable
data class AdherenceUpsert(
    @SerialName("intervention_id") val interventionId: String,
    val date: String,
    val adhered: String,
)

@Serializable
data class RecentSessionRow(
    val id: Long,
    val type: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("duration_min") val durationMin: Double? = null,
    @SerialName("distance_km") val distanceKm: Double? = null,
)
