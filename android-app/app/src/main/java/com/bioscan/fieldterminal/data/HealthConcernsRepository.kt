package com.bioscan.fieldterminal.data

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
        ReadCache.get("health.$kind.${keyPrefix ?: "*"}", tables = setOf("analysis_results")) {
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

    suspend fun loadOverview(): HealthOverviewDto? = published("health_overview").firstOrNull()?.decode<HealthOverviewDto>()

    suspend fun loadConcernState(concernId: String): ConcernStateDto? =
        published("health_concern_state", concernId).firstOrNull { it.key == concernId }?.decode<ConcernStateDto>()

    /** A concern's findings, patterns first (the key is "<concern id>:<exposure>"). */
    suspend fun loadFindings(concernId: String): List<FindingDto> =
        published("health_finding", "$concernId:").mapNotNull { it.decode<FindingDto>() }

    suspend fun loadAllFindings(): List<FindingDto> = published("health_finding").mapNotNull { it.decode<FindingDto>() }

    suspend fun loadTrialReads(): List<TrialReadDto> = published("health_trial_read").mapNotNull { it.decode<TrialReadDto>() }

    // ---- raw tables ----

    suspend fun loadConcerns(): List<HealthConcernRow> = ReadCache.get("health.concerns", tables = setOf("health_concerns")) {
        supabase.postgrest.from("health_concerns")
            .select(columns = Columns.list("id,title,kind,onset_type,regions,symptoms,excludes_from_baselines,archived_at,legacy_source,created_at")) {
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
