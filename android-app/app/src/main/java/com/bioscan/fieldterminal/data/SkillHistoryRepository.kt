package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.ExerciseSessionRow
import com.bioscan.fieldterminal.data.model.TrainingBlockSummaryRow
import com.bioscan.fieldterminal.data.model.Vo2MaxRow
import com.bioscan.fieldterminal.domain.LoggedSet
import com.bioscan.fieldterminal.domain.dedupeRunSessions
import com.bioscan.fieldterminal.domain.hasPlausiblePace
import com.bioscan.fieldterminal.domain.lactateThresholdPaceMinPerKmSeries
import com.bioscan.fieldterminal.domain.latestNonNullVo2Max
import com.bioscan.fieldterminal.domain.vo2MaxSeries
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

// Full-history loaders for the User tab's skill pages (Running / Strength / Conditioning). Unlike
// TrainingRepository.loadOverview (newest 200 sessions of every type, built for the Status tile's current
// week), these read each skill's own history so a weekly or per-lift evolution has real depth.

// ---- Running ---------------------------------------------------------------------------------

data class RunPoint(
    val id: Long,
    val date: LocalDate,
    val distanceKm: Double,
    val durationMin: Double,
    val paceMinPerKm: Double,
    val avgHr: Double?,
    val trail: Boolean,
)

// One point per synced Zepp workout; any field the watch did not report is null, never filled in.
data class RunFormPoint(
    val date: LocalDate,
    val cadenceSpm: Double?,
    val strideCm: Double?,
    val groundContactMs: Double?,
    val verticalRatioPct: Double?,
    val gapMinPerKm: Double?,
    val hrDecouplingPct: Double?,
    val efficiencyFactor: Double?,
)

data class RunningHistory(
    val runs: List<RunPoint>,
    val vo2MaxSeries: List<Pair<LocalDate, Double>>,
    val latestVo2Max: Double?,
    val ltPaceSeries: List<Pair<LocalDate, Double>>,
    val ltHrSeries: List<Pair<LocalDate, Double>>,
    val form: List<RunFormPoint>,
) {
    // Road, comparable runs only: no trail (terrain makes pace incomparable), as `runs` already excludes
    // implausible paces.
    val roadRuns: List<RunPoint> get() = runs.filter { !it.trail }
}

// ---- Strength --------------------------------------------------------------------------------

data class StrengthHistory(
    val sets: List<LoggedSet>,
    val bodyWeightKg: Double?,
)

// ---- Conditioning ----------------------------------------------------------------------------

data class ConditioningEntry(
    val id: Long,
    val date: LocalDate,
    val template: String?,
    val drills: List<String>,
    val intensity: String?,
    // From the linked wearable session when one exists (details.wearable_session_id); otherwise null.
    val durationMin: Double?,
    val avgHr: Double?,
    val maxHr: Double?,
)

data class ConditioningHistory(
    val entries: List<ConditioningEntry>,
    val blocks: List<TrainingBlockSummaryRow>,
)

@Serializable
private data class ConditioningRawRow(
    val id: Long,
    @SerialName("start_time") val startTime: String,
    val details: JsonObject? = null,
)

@Serializable
private data class WearableHrRow(
    val id: Long,
    @SerialName("duration_min") val durationMin: Double? = null,
    @SerialName("avg_hr") val avgHr: Double? = null,
    @SerialName("max_hr") val maxHr: Double? = null,
)

class SkillHistoryRepository(private val supabase: SupabaseClient) {

    suspend fun loadRunning(): RunningHistory {
        val rows = supabase.postgrest.from("exercise_sessions")
            .select(columns = Columns.list("id,type,start_time,duration_min,distance_km,avg_hr,avg_speed_kmh,source,elevation_gain_m,details")) {
                filter { eq("type", "run") }
                order("start_time", Order.DESCENDING)
                limit(2000)
            }
            .decodeList<ExerciseSessionRow>()

        val runs = dedupeRunSessions(rows).mapNotNull { r ->
            val d = r.distanceKm ?: return@mapNotNull null
            val m = r.durationMin ?: return@mapNotNull null
            if (d <= 0.0 || m <= 0.0 || !hasPlausiblePace(d, m)) return@mapNotNull null
            RunPoint(r.id, OffsetDateTime.parse(r.startTime).toLocalDate(), d, m, m / d, r.avgHr, r.details.routeType == "trail")
        }.sortedBy { it.date }

        // ~800 daily rows covers more than two years, so the 1Y toggle has a full year behind it.
        val vo2Rows = supabase.postgrest.from("wearable_daily")
            .select(columns = Columns.list("date,vo2max")) {
                order("date", Order.DESCENDING)
                limit(800)
            }
            .decodeList<Vo2MaxRow>()
            .reversed()

        val zepp = supabase.postgrest.from("zepp_workout_detail")
            .select(columns = Columns.raw("start_time,summary:decoded->summary")) {
                order("start_time", Order.ASCENDING)
            }
            .decodeList<ZeppWorkoutDetailSummaryRow>()
        val form = zepp.mapNotNull { z ->
            val s = z.summary ?: return@mapNotNull null
            RunFormPoint(
                OffsetDateTime.parse(z.startTime).toLocalDate(), s.avgCadenceSpm, s.avgStrideLengthCm, s.avgGroundContactMs,
                s.avgVerticalStrideRatioPct, s.gapMinPerKm, s.hrDecouplingPct, s.efficiencyFactor,
            )
        }
        val ltPace = zepp.mapNotNull { z -> z.summary?.lactateThresholdPaceSecPerKm?.let { OffsetDateTime.parse(z.startTime).toLocalDate() to it } }
        val ltHr = zepp.mapNotNull { z -> z.summary?.lactateThresholdHrBpm?.let { OffsetDateTime.parse(z.startTime).toLocalDate() to it } }

        return RunningHistory(
            runs = runs,
            vo2MaxSeries = vo2MaxSeries(vo2Rows),
            latestVo2Max = latestNonNullVo2Max(vo2Rows),
            ltPaceSeries = lactateThresholdPaceMinPerKmSeries(ltPace),
            ltHrSeries = ltHr,
            form = form,
        )
    }

    suspend fun loadStrength(): StrengthHistory {
        // Newest first so a long history is cut at the old end, never the recent end.
        val sessions = supabase.postgrest.from("exercise_sessions")
            .select(columns = Columns.list("id,type,start_time,source,details")) {
                filter { eq("type", "strength") }
                order("start_time", Order.DESCENDING)
                limit(2000)
            }
            .decodeList<ExerciseSessionRow>()
        val sets = sessions.flatMap { s ->
            val date = OffsetDateTime.parse(s.startTime).toLocalDate()
            s.details.exercises.orEmpty().flatMap { ex -> ex.sets.map { LoggedSet(date, ex.name, it.reps, it.weightKg, it.rpe) } }
        }
        return StrengthHistory(sets, AchievementsRepository(supabase).loadBodyWeight())
    }

    suspend fun loadConditioning(): ConditioningHistory {
        val raw = supabase.postgrest.from("exercise_sessions")
            .select(columns = Columns.list("id,start_time,details")) {
                filter {
                    eq("type", "other")
                    eq("source", "notion")
                }
                order("start_time", Order.DESCENDING)
                limit(1000)
            }
            .decodeList<ConditioningRawRow>()

        fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        val wearableIds = raw.mapNotNull { (it.details?.get("wearable_session_id") as? JsonPrimitive)?.longOrNull }.distinct()
        val wearable = if (wearableIds.isEmpty()) emptyMap() else supabase.postgrest.from("exercise_sessions")
            .select(columns = Columns.list("id,duration_min,avg_hr,max_hr")) { filter { isIn("id", wearableIds) } }
            .decodeList<WearableHrRow>()
            .associateBy { it.id }

        val entries = raw.map { r ->
            val d = r.details
            val linked = (d?.get("wearable_session_id") as? JsonPrimitive)?.longOrNull?.let { wearable[it] }
            ConditioningEntry(
                id = r.id,
                date = OffsetDateTime.parse(r.startTime).toLocalDate(),
                template = d?.text("template"),
                drills = (d?.get("drills") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("name") },
                intensity = d?.text("intensity"),
                durationMin = linked?.durationMin,
                avgHr = linked?.avgHr,
                maxHr = linked?.maxHr,
            )
        }.sortedBy { it.date }

        val blocks = runCatching { TrainingCyclesRepository(supabase).loadBlockSummaries() }.getOrDefault(emptyList())
        return ConditioningHistory(entries, blocks)
    }
}
