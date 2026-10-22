package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.form.FormMetrics
import com.bioscan.fieldterminal.domain.form.FormReport
import com.bioscan.fieldterminal.domain.form.FormSurface
import com.bioscan.fieldterminal.domain.form.MetricSummary
import com.bioscan.fieldterminal.domain.form.RunChoice
import com.bioscan.fieldterminal.domain.form.StepMetrics
import com.bioscan.fieldterminal.domain.form.WatchRunValues
import com.bioscan.fieldterminal.domain.form.formMetricValues
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.math.roundToInt

// Running form analyses (migration 20261030_running_form_analyses.sql): save a report, list past ones for "last time"
// comparisons, and find the run a clip was filmed during with the watch's values for it.

/** A saved analysis, as the history and the "last time" lines read it. */
data class SavedFormAnalysis(
    val id: Long,
    val date: LocalDate,
    val surface: FormSurface,
    val fps: Double,
    val steps: Int,
    val quality: String,
    val values: Map<String, MetricSummary>,
    val footStrike: String?,
    val sessionId: Long?,
)

@Serializable
private data class RunningFormInsert(
    @SerialName("recorded_on") val recordedOn: String,
    @SerialName("exercise_session_id") val exerciseSessionId: Long?,
    val view: String,
    val surface: String,
    @SerialName("side_facing_camera") val sideFacingCamera: String?,
    @SerialName("treadmill_speed_kmh") val treadmillSpeedKmh: Double?,
    val fps: Double,
    @SerialName("steps_measured") val stepsMeasured: Int,
    val quality: String,
    @SerialName("quality_issues") val qualityIssues: List<String>,
    val metrics: JsonObject,
    val steps: JsonArray,
    @SerialName("analyser_version") val analyserVersion: String,
    val notes: String?,
)

@Serializable
private data class RunningFormRow(
    val id: Long,
    @SerialName("recorded_on") val recordedOn: String,
    @SerialName("exercise_session_id") val exerciseSessionId: Long? = null,
    val surface: String,
    val fps: Double,
    @SerialName("steps_measured") val stepsMeasured: Int,
    val quality: String,
    val metrics: JsonObject,
)

@Serializable
private data class RunSessionRow(
    val id: Long,
    @SerialName("start_time") val startTime: String,
    @SerialName("duration_min") val durationMin: Double? = null,
    @SerialName("distance_km") val distanceKm: Double? = null,
)

@Serializable
private data class SummaryOnlyRow(val summary: ZeppWorkoutSummary? = null)

private const val FOOT_STRIKE_PATTERN = "foot_strike_pattern"

private fun Double.r(d: Int = 1): Double { val k = Math.pow(10.0, d.toDouble()); return (this * k).roundToInt() / k }

private fun metricsJson(m: FormMetrics): JsonObject = buildJsonObject {
    formMetricValues(m).forEach { (key, s) ->
        put(key, buildJsonObject { put("median", s.median.r(3)); put("spread", s.spread.r(3)); put("n", s.n) })
    }
    m.footStrike?.let { put(FOOT_STRIKE_PATTERN, it.name) }
}

private fun stepsJson(steps: List<StepMetrics>): JsonArray = buildJsonArray {
    steps.forEach { s ->
        add(buildJsonObject {
            put("landing_ms", s.landingMs.r(1))
            s.contactMs?.let { put("contact_ms", it.r(1)) }
            s.kneeFlexionAtLandingDeg?.let { put("knee_landing", it.r(1)) }
            s.peakKneeFlexionDeg?.let { put("knee_peak", it.r(1)) }
            s.shinAngleAtLandingDeg?.let { put("shin_angle", it.r(1)) }
            s.ankleAheadOfHip?.let { put("ankle_ahead", it.r(3)) }
            s.footStrikeAngleDeg?.let { put("foot_strike", it.r(1)) }
            s.hipExtensionAtToeOffDeg?.let { put("hip_extension", it.r(1)) }
        })
    }
}

private fun valuesOf(metrics: JsonObject): Map<String, MetricSummary> = metrics.mapNotNull { (key, v) ->
    val o = runCatching { v.jsonObject }.getOrNull() ?: return@mapNotNull null
    val median = o["median"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
    key to MetricSummary(median, o["spread"]?.jsonPrimitive?.doubleOrNull ?: 0.0, o["n"]?.jsonPrimitive?.intOrNull ?: 0)
}.toMap()

class RunningFormRepository(private val supabase: SupabaseClient) {

    suspend fun save(report: FormReport, recordedOn: LocalDate, sessionId: Long?, notes: String? = null) {
        val m = report.metrics ?: throw IllegalStateException("A clip with no reading is not saved.")
        supabase.postgrest.from("running_form_analyses").insert(
            RunningFormInsert(
                recordedOn = recordedOn.toString(),
                exerciseSessionId = sessionId,
                view = report.setup.view.name.lowercase(),
                surface = report.setup.surface.name.lowercase(),
                sideFacingCamera = report.setup.sideFacingCamera?.name?.lowercase(),
                treadmillSpeedKmh = report.setup.treadmillSpeedKmh,
                fps = report.quality.fps.r(1),
                stepsMeasured = report.quality.measuredSteps,
                quality = report.quality.level.name.lowercase(),
                qualityIssues = report.quality.issues.map { it.name },
                metrics = metricsJson(m),
                steps = stepsJson(m.steps),
                analyserVersion = report.analyserVersion,
                notes = notes?.trim()?.ifBlank { null },
            ),
        )
    }

    /** Saved analyses, newest first. */
    suspend fun loadAll(): List<SavedFormAnalysis> = ReadCache.get("form.analyses", tables = setOf("running_form_analyses")) {
        supabase.postgrest.from("running_form_analyses")
            .select(columns = Columns.list("id,recorded_on,exercise_session_id,surface,fps,steps_measured,quality,metrics")) {
                order("recorded_on", Order.DESCENDING)
                order("id", Order.DESCENDING)
                limit(200)
            }
            .decodeList<RunningFormRow>()
            .map { r ->
                SavedFormAnalysis(
                    id = r.id,
                    date = LocalDate.parse(r.recordedOn),
                    surface = if (r.surface == "overground") FormSurface.OVERGROUND else FormSurface.TREADMILL,
                    fps = r.fps,
                    steps = r.stepsMeasured,
                    quality = r.quality,
                    values = valuesOf(r.metrics),
                    footStrike = r.metrics[FOOT_STRIKE_PATTERN]?.jsonPrimitive?.content,
                    sessionId = r.exerciseSessionId,
                )
            }
    }

    suspend fun delete(id: Long) {
        supabase.postgrest.from("running_form_analyses").delete { filter { eq("id", id) } }
    }

    /** The runs recorded on a day (wall clock, as sessions store it), for "which run was this?". */
    suspend fun runsOn(day: LocalDate): List<RunChoice> =
        supabase.postgrest.from("exercise_sessions")
            .select(columns = Columns.list("id,start_time,duration_min,distance_km")) {
                filter {
                    eq("type", "run")
                    gte("start_time", "${day}T00:00:00Z")
                    lt("start_time", "${day.plusDays(1)}T00:00:00Z")
                }
                order("start_time", Order.ASCENDING)
            }
            .decodeList<RunSessionRow>()
            .mapNotNull { r ->
                val start = runCatching { OffsetDateTime.parse(r.startTime).toLocalDateTime() }.getOrNull() ?: return@mapNotNull null
                val km = r.distanceKm?.let { " · %.1f km".format(it) }.orEmpty()
                RunChoice(r.id, start, r.durationMin, "%02d:%02d".format(start.hour, start.minute) + km)
            }

    /** The watch's averages for a run, from its Zepp workout; null when the run has none. */
    suspend fun watchValues(sessionId: Long): WatchRunValues? {
        val s = supabase.postgrest.from("zepp_workout_detail")
            .select(columns = Columns.list("summary")) {
                filter { eq("exercise_session_id", sessionId) }
                limit(1)
            }
            .decodeList<SummaryOnlyRow>()
            .firstOrNull()?.summary ?: return null
        return WatchRunValues(s.avgCadenceSpm, s.avgGroundContactMs, s.avgVerticalStrideRatioPct)
    }
}
