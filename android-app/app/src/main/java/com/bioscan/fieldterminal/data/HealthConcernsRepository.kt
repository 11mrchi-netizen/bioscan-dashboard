package com.bioscan.fieldterminal.data

import kotlinx.serialization.builtins.nullable
import android.content.Context
import com.bioscan.fieldterminal.data.model.AdherenceUpsert
import com.bioscan.fieldterminal.data.model.ConcernStateDto
import com.bioscan.fieldterminal.data.model.EpisodeMarkInsert
import com.bioscan.fieldterminal.data.model.FindingDto
import com.bioscan.fieldterminal.data.model.HealthConcernRow
import com.bioscan.fieldterminal.data.model.HealthObservationRow
import com.bioscan.fieldterminal.data.model.HealthOverviewDto
import com.bioscan.fieldterminal.data.model.NewInterventionConcern
import com.bioscan.fieldterminal.data.model.NewTrialIntervention
import com.bioscan.fieldterminal.data.model.RecentSessionRow
import com.bioscan.fieldterminal.data.model.TrialReadDto
import com.bioscan.fieldterminal.domain.health.CheckInPlan
import com.bioscan.fieldterminal.domain.health.HealthVocab
import com.bioscan.fieldterminal.domain.health.parseHealthVocab
import com.bioscan.fieldterminal.domain.health.retraction
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.UUID

// Health concerns (docs/health-concerns/01-plan.md): the raw tables the app writes (concerns, append-only check-ins and
// their signals, episode marks, trial links and adherence) and the rows analysis-run publishes about them. Published
// payloads are decoded here with a lenient Json, so a newer engine's extra fields never break the app; before the first
// run (or the migration) every published read is empty rather than an error.
@Serializable
private data class PublishedRow(val key: String = "", val state: String = "", val payload: JsonElement? = null)

private val lenient = Json { ignoreUnknownKeys = true; coerceInputValues = true }

class HealthConcernsRepository(private val supabase: SupabaseClient) {

    // ---- published rows ----

    private suspend fun published(kind: String, keyPrefix: String? = null): List<PublishedRow> = try {
        ReadCache.kept("health.$kind.${keyPrefix ?: "*"}", tables = setOf("analysis_results")) {
            supabase.postgrest.from("analysis_results")
                .select(columns = Columns.list("key,state,payload")) {
                    filter {
                        eq("kind", kind)
                        if (keyPrefix != null) like("key", "$keyPrefix%")
                    }
                    order("as_of", Order.DESCENDING)
                    order("calculated_at", Order.DESCENDING)
                }
                .decodeList<PublishedRow>()
                .distinctBy { it.key }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    private inline fun <reified T> PublishedRow.decode(): T? =
        payload?.let { runCatching { lenient.decodeFromJsonElement<T>(it) }.getOrNull() }

    // Kept on disk with the read cache (data/ReadCache.kt): the page opens from the last copy while this loads.
    suspend fun loadOverview(): HealthOverviewDto? =
        ReadCache.get("health.overview", tables = null, serializer = HealthOverviewDto.serializer().nullable) { fetchLoadOverview() }

    private suspend fun fetchLoadOverview(): HealthOverviewDto? = published("health_overview").firstOrNull()?.decode<HealthOverviewDto>()

    suspend fun loadConcernState(concernId: String): ConcernStateDto? =
        published("health_concern_state", concernId).firstOrNull { it.key == concernId }?.decode<ConcernStateDto>()

    /** A concern's findings, patterns first (the key is "<concern id>:<exposure>"). */
    suspend fun loadFindings(concernId: String): List<FindingDto> =
        published("health_finding", "$concernId:").mapNotNull { it.decode<FindingDto>() }

    // Kept on disk with the read cache (data/ReadCache.kt): the page opens from the last copy while this loads.
    suspend fun loadAllFindings(): List<FindingDto> =
        ReadCache.get("health.findings", tables = null, serializer = kotlinx.serialization.builtins.ListSerializer(FindingDto.serializer())) { fetchLoadAllFindings() }

    private suspend fun fetchLoadAllFindings(): List<FindingDto> = published("health_finding").mapNotNull { it.decode<FindingDto>() }

    suspend fun loadTrialReads(): List<TrialReadDto> = published("health_trial_read").mapNotNull { it.decode<TrialReadDto>() }

    // ---- raw tables ----

    suspend fun loadConcerns(): List<HealthConcernRow> = ReadCache.get("health.concerns.v2", tables = setOf("health_concerns"), serializer = kotlinx.serialization.builtins.ListSerializer(com.bioscan.fieldterminal.data.model.HealthConcernRow.serializer())) {
        supabase.postgrest.from("health_concerns")
            .select(columns = Columns.list("id,title,kind,onset_type,regions,symptoms,excludes_from_baselines,archived_at,legacy_source,created_at,category,illness_type,label_diagnosis")) {
                order("created_at", Order.DESCENDING)
            }
            .decodeList<HealthConcernRow>()
    }

    /** The newest check-in that still counts (for "undo" and for "nothing since <date>"). */
    suspend fun loadLatestObservations(limit: Int = 20): List<HealthObservationRow> =
        supabase.postgrest.from("health_observations")
            .select(columns = Columns.list("id,observed_at,local_date,concern_id,scope,symptom_free,recall,recall_from,function_impact,superseded_at,retracted")) {
                filter { exact("superseded_at", null); eq("retracted", false) }
                order("observed_at", Order.DESCENDING)
                limit(limit.toLong())
            }
            .decodeList<HealthObservationRow>()

    /**
     * The check-ins about one concern since [since], one per day: the highest TRAINING answer and the highest temperature
     * logged that day (scoring: the illness ceiling, domain/score/IllnessRead.kt). Retracted and superseded rows are left out.
     */
    suspend fun loadIllnessCheckIns(concernId: String, since: LocalDate): List<com.bioscan.fieldterminal.domain.score.IllnessCheckIn> =
        ReadCache.get("health.illness_checkins.v2.$concernId.$since", tables = setOf("health_observations", "health_signals"),
            serializer = kotlinx.serialization.builtins.ListSerializer(IllnessDayRow.serializer())) {
            val obs = supabase.postgrest.from("health_observations")
                .select(columns = Columns.list("id,local_date,function_impact")) {
                    filter { eq("concern_id", concernId); gte("local_date", since.toString()); exact("superseded_at", null); eq("retracted", false) }
                }
                .decodeList<IllnessObsRow>()
            val temps = if (obs.isEmpty()) emptyList() else supabase.postgrest.from("health_signals")
                .select(columns = Columns.list("observation_id,temperature_c,severity,symptom")) {
                    filter { eq("concern_id", concernId); isIn("observation_id", obs.map { it.id }) }
                }
                .decodeList<IllnessTempRow>()
            val byObs = temps.groupBy { it.observationId }
            obs.groupBy { it.localDate }.map { (day, rows) ->
                val sigs = rows.flatMap { byObs[it.id].orEmpty() }
                IllnessDayRow(
                    day, rows.mapNotNull { it.functionImpact }.maxOrNull(), sigs.mapNotNull { it.temperatureC }.maxOrNull(),
                    sigs.mapNotNull { it.severity }.maxOrNull(), sigs.map { it.symptom }.toSet(),
                )
            }
        }.mapNotNull { r ->
            runCatching { LocalDate.parse(r.date) }.getOrNull()?.let { com.bioscan.fieldterminal.domain.score.IllnessCheckIn(it, r.impact, r.temperatureC, r.severity, r.symptoms) }
        }

    suspend fun lastCheckinDate(): LocalDate? = loadLatestObservations(1).firstOrNull()?.localDate?.let(LocalDate::parse)

    /** Sessions of the last three days, for "during or after this session". */
    suspend fun loadRecentSessions(days: Long = 3): List<RecentSessionRow> =
        supabase.postgrest.from("exercise_sessions")
            .select(columns = Columns.list("id,type,start_time,duration_min,distance_km")) {
                filter { gte("start_time", Instant.now().minusSeconds(days * 86400).toString()) }
                order("start_time", Order.DESCENDING)
                limit(10)
            }
            .decodeList<RecentSessionRow>()

    /** One check-in: new concerns first, then the check-ins, then their signals (each refers to the one before). */
    suspend fun save(plan: CheckInPlan) {
        if (plan.concerns.isNotEmpty()) supabase.postgrest.from("health_concerns").insert(plan.concerns)
        if (plan.observations.isNotEmpty()) supabase.postgrest.from("health_observations").insert(plan.observations)
        if (plan.signals.isNotEmpty()) supabase.postgrest.from("health_signals").insert(plan.signals)
    }

    /** An illness: its concern, first check-in and signals, and the start mark when it began before today. */
    suspend fun saveIllness(plan: com.bioscan.fieldterminal.domain.health.IllnessPlan) {
        save(plan.checkIn)
        plan.startMark?.let { supabase.postgrest.from("health_episode_marks").insert(it) }
    }

    /** Saves a symptom check; returns its signals' ids (for TRACK AS ILLNESS, which gives them to the new illness). */
    suspend fun saveCheck(plan: CheckInPlan): List<Long> {
        supabase.postgrest.from("health_observations").insert(plan.observations)
        if (plan.signals.isEmpty()) return emptyList()
        return supabase.postgrest.from("health_signals").insert(plan.signals) { select(Columns.list("id,observation_id,symptom")) }
            .decodeList<CheckSignalRow>().map { it.id }
    }

    /** The symptom checks of the last [days] days, newest first, each with its signals. */
    suspend fun loadChecks(days: Long = 14): List<SymptomCheckRow> {
        val since = LocalDate.now().minusDays(days).toString()
        val obs = supabase.postgrest.from("health_observations")
            .select(columns = Columns.list("id,observed_at,local_date,assessment")) {
                filter { eq("scope", "check"); gte("local_date", since); exact("superseded_at", null); eq("retracted", false) }
                order("observed_at", Order.DESCENDING)
                limit(50)
            }
            .decodeList<CheckObsRow>()
        if (obs.isEmpty()) return emptyList()
        val sigs = supabase.postgrest.from("health_signals")
            .select(columns = Columns.list("id,observation_id,concern_id,symptom,severity")) { filter { isIn("observation_id", obs.map { it.id }) } }
            .decodeList<CheckSignalRow>()
            .groupBy { it.observationId }
        return obs.map { SymptomCheckRow(it.id, it.localDate, it.assessment, sigs[it.id].orEmpty()) }
    }

    /** A symptom check's signals become part of [concernId] (TRACK AS ILLNESS): once, as every signal assignment. */
    suspend fun assignSignals(signalIds: List<Long>, concernId: String) {
        if (signalIds.isEmpty()) return
        supabase.postgrest.from("health_signals").update(buildJsonObject { put("concern_id", concernId) }) {
            filter { isIn("id", signalIds); exact("concern_id", null) }
        }
    }

    /** What the symptom check prefills from: last night's sleep and the last three days' recorded training. */
    suspend fun loadCheckContext(today: LocalDate = LocalDate.now()): com.bioscan.fieldterminal.domain.health.CheckContext {
        val sleep = runCatching {
            supabase.postgrest.from("sleep_daily").select(columns = Columns.list("date,hours")) { filter { eq("date", today.toString()) } }
                .decodeList<CheckSleepRow>().mapNotNull { it.hours }.maxOrNull()
        }.getOrNull()
        val sessions = runCatching {
            // Session starts are logged times (local digits, zero offset): the local day string compares as a date.
            supabase.postgrest.from("exercise_sessions").select(columns = Columns.list("type,duration_min")) {
                filter { gte("start_time", today.minusDays(3).toString()); neq("type", "walk") }
            }.decodeList<CheckSessionRow>().mapNotNull { it.durationMin }
        }.getOrNull()
        return com.bioscan.fieldterminal.domain.health.CheckContext(sleep, sessions?.sum(), sessions?.maxOrNull())
    }

    /** Withdraws a check-in by saving a retraction that supersedes it; nothing is deleted. */
    suspend fun retract(original: HealthObservationRow) {
        supabase.postgrest.from("health_observations").insert(retraction(original, ZonedDateTime.now()))
    }

    suspend fun addMark(concernId: String, kind: String, date: LocalDate, note: String? = null) {
        supabase.postgrest.from("health_episode_marks").insert(EpisodeMarkInsert(concernId, kind, date.toString(), note))
    }

    suspend fun rename(concernId: String, title: String) {
        supabase.postgrest.from("health_concerns").update(buildJsonObject { put("title", title.trim().take(80)) }) { filter { eq("id", concernId) } }
    }

    suspend fun setOnsetType(concernId: String, onsetType: String) {
        supabase.postgrest.from("health_concerns").update(buildJsonObject { put("onset_type", onsetType) }) { filter { eq("id", concernId) } }
    }

    suspend fun archive(concernId: String, archived: Boolean) {
        supabase.postgrest.from("health_concerns").update(buildJsonObject {
            if (archived) put("archived_at", Instant.now().toString()) else put("archived_at", null as String?)
        }) { filter { eq("id", concernId) } }
    }

    // ---- trials ----

    /** A deliberate change linked to a concern, registered with its hypothesis and outcome before it starts. */
    suspend fun createTrial(concernId: String, name: String, hypothesis: String, outcome: String, exposureUnit: String, start: LocalDate, weeks: Int, preDays: Int) {
        val id = UUID.randomUUID().toString()
        supabase.postgrest.from("interventions").insert(
            NewTrialIntervention(
                id = id, domain = "training", subjectType = "health_concern", subjectKey = concernId, name = name.trim().take(80),
                lifecycleState = if (start.isAfter(LocalDate.now())) "baseline" else "active",
                startedAt = start.toString(), evaluateOn = start.plusWeeks(weeks.coerceAtLeast(4).toLong()).toString(),
            ),
        )
        supabase.postgrest.from("intervention_concerns").insert(NewInterventionConcern(id, concernId, hypothesis.trim(), outcome, exposureUnit, preDays))
    }

    suspend fun logAdherence(interventionId: String, date: LocalDate, adhered: String) {
        supabase.postgrest.from("intervention_adherence").upsert(AdherenceUpsert(interventionId, date.toString(), adhered)) { onConflict = "intervention_id,date" }
    }

    // ---- recompute ----

    /** Asks analysis-run to recompute this person's concerns now (a few seconds), so a check-in shows at once. A failure
     *  is quiet: the nightly run catches up. */
    suspend fun refresh(): Boolean = try {
        val response = supabase.functions.invoke(function = "analysis-run") {
            contentType(ContentType.Application.Json)
            setBody("""{"action":"health"}""")
        }
        ReadCache.invalidateWrite("analysis_results")
        response.status.value in 200..299
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    companion object {
        @Volatile private var vocab: HealthVocab? = null

        /** The packaged vocabulary (asset health-vocab.json), read once. */
        fun vocab(context: Context): HealthVocab =
            vocab ?: synchronized(this) {
                vocab ?: context.applicationContext.assets.open("health-vocab.json").bufferedReader().use { parseHealthVocab(it.readText()) }.also { vocab = it }
            }
    }
}

@Serializable
private data class IllnessObsRow(
    val id: String,
    @kotlinx.serialization.SerialName("local_date") val localDate: String,
    @kotlinx.serialization.SerialName("function_impact") val functionImpact: Int? = null,
)

@Serializable
private data class IllnessTempRow(
    @kotlinx.serialization.SerialName("observation_id") val observationId: String,
    @kotlinx.serialization.SerialName("temperature_c") val temperatureC: Double? = null,
    val severity: Int? = null,
    val symptom: String = "",
)

@Serializable
internal data class IllnessDayRow(val date: String, val impact: Int?, val temperatureC: Double?, val severity: Int? = null, val symptoms: Set<String> = emptySet())

/** One symptom check as the Health tab lists it. */
data class SymptomCheckRow(val id: String, val date: String, val assessment: kotlinx.serialization.json.JsonObject?, val signals: List<CheckSignalRow>)

@Serializable
private data class CheckObsRow(
    val id: String,
    @kotlinx.serialization.SerialName("observed_at") val observedAt: String,
    @kotlinx.serialization.SerialName("local_date") val localDate: String,
    val assessment: kotlinx.serialization.json.JsonObject? = null,
)

@Serializable
data class CheckSignalRow(
    val id: Long,
    @kotlinx.serialization.SerialName("observation_id") val observationId: String,
    @kotlinx.serialization.SerialName("concern_id") val concernId: String? = null,
    val symptom: String,
    val severity: Int? = null,
)

@Serializable
private data class CheckSleepRow(val date: String, val hours: Double? = null)

@Serializable
private data class CheckSessionRow(val type: String, @kotlinx.serialization.SerialName("duration_min") val durationMin: Double? = null)
