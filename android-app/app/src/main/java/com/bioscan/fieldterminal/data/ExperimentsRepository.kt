package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.ExperimentProtocol
import com.bioscan.fieldterminal.domain.MdePreviewRow
import com.bioscan.fieldterminal.domain.PROTOCOL_VERSION_RANDOMISED
import com.bioscan.fieldterminal.domain.scheduleSpan
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

// Analysis Layer 3 / Phase 5. Registers supplement N-of-1 experiments and reads their published results.
private const val FUNCTIONS_BASE_URL = "https://ugfrglbcoivkprjqvjzz.supabase.co/functions/v1"

class ExperimentException(message: String) : Exception(message)

@Serializable
private data class PreviewRequest(val action: String, @SerialName("planned_a") val plannedA: Int, @SerialName("planned_b") val plannedB: Int)

@Serializable
private data class PreviewResponse(val previews: List<MdePreviewRow> = emptyList(), val error: String? = null, val message: String? = null)

@Serializable
private data class NewExperimentRow(
    @SerialName("supplement_id") val supplementId: Long? = null,
    @SerialName("intervention_id") val interventionId: String? = null,
    val name: String,
    @SerialName("primary_metric") val primaryMetric: String,
    @SerialName("secondary_metrics") val secondaryMetrics: List<String>,
    val design: String,
    val protocol: ExperimentProtocol,
    @SerialName("mde_preview") val mdePreview: List<MdePreviewRow>,
    // Analysis review phase F: the database requires '2' for the randomised designs.
    @SerialName("protocol_version") val protocolVersion: String,
)

// Read models. Every field is defaulted so an older or partial payload still decodes.
@Serializable
data class ExperimentRow(
    val id: String,
    val name: String = "",
    @SerialName("supplement_id") val supplementId: Long? = null,
    @SerialName("intervention_id") val interventionId: String? = null,
    @SerialName("primary_metric") val primaryMetric: String = "",
    val design: String = "AB",
    val status: String = "planned",
    val protocol: ExperimentProtocol? = null,
    // Analysis review phase F: deletion is soft.
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class ExperimentDiff(val value: Double = 0.0, val lo: Double = 0.0, val hi: Double = 0.0, val unit: String = "")

@Serializable
data class ExperimentMetricResult(
    val metric: String = "",
    val label: String = "",
    val role: String = "primary",
    val verdict: String = "insufficient",
    val n0: Int = 0,
    val n1: Int = 0,
    val diff: ExperimentDiff? = null,
    val mde: Double? = null,
    val summary: String = "",
    val notes: List<String> = emptyList(),
    // Analysis review phase F: the zone a randomised design was judged against, and its exact randomisation p.
    val zone: Double? = null,
    val randomizationP: Double? = null,
)

@Serializable
data class ExperimentAdherence(val interventionDays: Int = 0, val interventionLogged: Int = 0, val rate: Double? = null, val baselineLogged: Int = 0, val ok: Boolean? = null)

@Serializable
data class ExperimentPayload(val stage: String = "before", val adherence: ExperimentAdherence = ExperimentAdherence(), val metrics: List<ExperimentMetricResult> = emptyList())

@Serializable
data class ExperimentResultRow(val key: String, val state: String = "before", val payload: ExperimentPayload = ExperimentPayload())

class ExperimentsRepository(private val supabase: SupabaseClient) {
    companion object { private val client = HttpClient(Android) }
    private val json = Json { ignoreUnknownKeys = true }

    // The smallest difference a design with these phase lengths could detect on this person's own data, per outcome.
    suspend fun previewMde(plannedA: Int, plannedB: Int): List<MdePreviewRow> {
        val token = supabase.auth.currentAccessTokenOrNull() ?: throw ExperimentException("Not signed in")
        val response = client.post("$FUNCTIONS_BASE_URL/analysis-run") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(PreviewRequest.serializer(), PreviewRequest("experiment_preview", plannedA, plannedB)))
        }
        val parsed = try {
            json.decodeFromString<PreviewResponse>(response.bodyAsText())
        } catch (e: Exception) {
            throw ExperimentException("Unreadable response from the analysis service")
        }
        if (parsed.error != null) throw ExperimentException(parsed.message ?: parsed.error)
        return parsed.previews
    }

    // Registers the experiment. The database locks every field written here, so this is the pre-registration. The subject is
    // a supplement, an intervention (a fueling experiment) or, for a daily randomised design, the behaviour in the protocol;
    // the database requires one of the three.
    suspend fun register(
        supplementId: Long? = null,
        interventionId: String? = null,
        name: String,
        primary: String,
        secondary: List<String>,
        protocol: ExperimentProtocol,
        preview: List<MdePreviewRow>,
    ) {
        require(supplementId != null || interventionId != null || protocol.behaviour != null) { "An experiment needs a supplement, an intervention or a behaviour" }
        supabase.postgrest.from("experiments").insert(
            NewExperimentRow(
                supplementId, interventionId, name, primary, secondary.take(3), protocol.design, protocol, preview,
                protocolVersion = if (protocol.version == PROTOCOL_VERSION_RANDOMISED) "2" else "1",
            ),
        )
    }

    // Every column, so the read keeps working before the phase F migration adds deleted_at; deleted ones are left out here.
    suspend fun loadExperiments(): List<ExperimentRow> =
        try {
            supabase.postgrest.from("experiments")
                .select(columns = Columns.ALL)
                .decodeList<ExperimentRow>()
                .filter { it.deletedAt == null }
                .sortedByDescending { it.protocol?.let(::scheduleSpan)?.first }
        } catch (e: Exception) {
            emptyList()
        }

    suspend fun loadResults(): Map<String, ExperimentResultRow> =
        try {
            supabase.postgrest.from("analysis_results")
                .select(columns = Columns.list("key,state,payload")) { filter { eq("kind", "experiment") } }
                .decodeList<ExperimentResultRow>()
                .associateBy { it.key }
        } catch (e: Exception) {
            emptyMap()
        }

    suspend fun abandon(id: String) {
        supabase.postgrest.from("experiments").update(mapOf("status" to "abandoned")) { filter { eq("id", id) } }
    }

    // Analysis review phase F: deletion is soft (the database has no delete policy); the analysis skips a deleted experiment.
    suspend fun delete(id: String) {
        supabase.postgrest.from("experiments").update(mapOf("deleted_at" to Instant.now().toString())) { filter { eq("id", id) } }
    }
}
