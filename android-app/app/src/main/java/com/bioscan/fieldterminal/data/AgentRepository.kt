package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.WhyContext
import com.bioscan.fieldterminal.domain.agentErrorMessage
import com.bioscan.fieldterminal.domain.question
import com.bioscan.fieldterminal.domain.toJson
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.OffsetDateTime

// Personal health agent, phase 1 (docs/personal-health-agent): the client for the `health-agent` Edge Function (WHY? on
// a card) and the assistant's settings (agent_settings, own row). The function reads with the caller's JWT and writes
// nothing but its own audit row; the assistant answers only once the person turned it on here.
private const val FUNCTIONS_BASE_URL = "https://ugfrglbcoivkprjqvjzz.supabase.co/functions/v1"

class AgentException(message: String) : Exception(message)

/** The groups a person can share beyond the default (agent-core/scope.ts OPT_IN_GROUPS). */
enum class AgentShareGroup(val key: String, val label: String) {
    Notes("notes", "Free-text notes"),
    HealthConcerns("health_concerns", "Health concerns and their check-ins"),
    SexualHealth("sexual_health", "Sexual health"),
    Digestive("digestive", "Digestion (stool log)"),
}

@Serializable
data class AgentSettingsRow(
    val enabled: Boolean = false,
    @SerialName("opt_in") val optIn: List<String> = emptyList(),
)

@Serializable
private data class ExplainRequest(
    val action: String,
    val question: String,
    val context: JsonObject,
    @SerialName("client_turn_id") val clientTurnId: String,
    @SerialName("local_now") val localNow: String,
)

@Serializable
private data class RateRequest(val action: String, @SerialName("turn_id") val turnId: String, val rating: Int)

@Serializable
private data class AgentResponse(
    @SerialName("turn_id") val turnId: String? = null,
    val shown: String? = null,
    val refs: List<String> = emptyList(),
    val regenerated: Boolean = false,
    val violations: Int = 0,
    val triage: String? = null,
    val error: String? = null,
    val message: String? = null,
)

data class AgentReply(val turnId: String, val shown: String, val refs: List<String>, val triage: String?)

class AgentRepository(private val supabase: SupabaseClient) {
    // Ling 3.1 Flash took 10 to 25 s a turn in the golden-set runs; the function itself stops a model call at 40 s.
    companion object { private val client = functionsClient(90_000) }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun loadSettings(): AgentSettingsRow =
        supabase.postgrest.from("agent_settings").select(columns = Columns.list("enabled,opt_in")).decodeSingleOrNull<AgentSettingsRow>()
            ?: AgentSettingsRow()

    suspend fun saveSettings(enabled: Boolean, optIn: Set<AgentShareGroup>) {
        val row = AgentSettingsRow(enabled, optIn.map { it.key }.sorted())
        supabase.postgrest.from("agent_settings").upsert(row) { onConflict = "user_id" }
    }

    /** Deletes every past turn (the questions, replies and their references). */
    suspend fun deleteHistory() {
        supabase.postgrest.from("agent_turns").delete { filter { neq("client_turn_id", "") } }
    }

    // One clientTurnId per tap: a retry of the same tap returns the stored reply instead of calling the model again.
    suspend fun explain(why: WhyContext, clientTurnId: String): AgentReply {
        val now = OffsetDateTime.now().withNano(0)
        val body = ExplainRequest("explain", why.question(), why.toJson(now.toLocalDate().toString()), clientTurnId, now.toString())
        val parsed = post(json.encodeToString(ExplainRequest.serializer(), body))
        return AgentReply(parsed.turnId ?: throw AgentException("No reply returned"), parsed.shown.orEmpty(), parsed.refs, parsed.triage)
    }

    suspend fun rate(turnId: String, rating: Int) {
        post(json.encodeToString(RateRequest.serializer(), RateRequest("rate", turnId, rating)))
    }

    private suspend fun post(requestBody: String): AgentResponse {
        val token = supabase.auth.currentAccessTokenOrNull() ?: throw AgentException("Not signed in")
        val response = client.post("$FUNCTIONS_BASE_URL/health-agent") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        val parsed = try {
            json.decodeFromString<AgentResponse>(response.bodyAsText())
        } catch (e: Exception) {
            throw AgentException("Unreadable response from the assistant")
        }
        if (parsed.error != null) throw AgentException(agentErrorMessage(parsed.error, parsed.message))
        return parsed
    }
}
