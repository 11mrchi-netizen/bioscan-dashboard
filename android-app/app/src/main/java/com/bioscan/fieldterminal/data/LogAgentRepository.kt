package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.LogProposal
import com.bioscan.fieldterminal.domain.UnparsedEntry
import com.bioscan.fieldterminal.domain.isReady
import com.bioscan.fieldterminal.domain.localDate
import com.bioscan.fieldterminal.domain.toAppIso
import com.bioscan.fieldterminal.domain.whenLocal
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
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
import java.time.OffsetDateTime
import kotlin.math.roundToInt

// Analysis Layer 3 / R2. Client for the `log-agent` Edge Function (propose-only: it never writes a health row) and the commit
// dispatch that writes a CONFIRMED proposal through the same repositories the forms use. Food is not committed here: a meal
// proposal hands its text to the existing food form, which owns resolution and review.
private const val FUNCTIONS_BASE_URL = "https://ugfrglbcoivkprjqvjzz.supabase.co/functions/v1"

class LogAgentException(message: String) : Exception(message)

@Serializable
private data class ProposeRequest(
    val action: String,
    val text: String,
    @SerialName("client_turn_id") val clientTurnId: String,
    @SerialName("local_now") val localNow: String,
)

@Serializable
private data class CommittedRequest(val action: String, @SerialName("run_id") val runId: String, val count: Int)

@Serializable
private data class LogAgentResponse(
    @SerialName("run_id") val runId: String? = null,
    val proposals: List<LogProposal> = emptyList(),
    val unparsed: List<UnparsedEntry> = emptyList(),
    val error: String? = null,
    val message: String? = null,
)

data class LogAgentResult(val runId: String, val proposals: List<LogProposal>, val unparsed: List<UnparsedEntry>)

class LogAgentRepository(private val supabase: SupabaseClient) {
    companion object { private val client = HttpClient(Android) }
    private val json = Json { ignoreUnknownKeys = true }

    // One clientTurnId per typed message: a retry of the same turn returns the stored proposals instead of calling the model again.
    suspend fun propose(text: String, clientTurnId: String): LogAgentResult {
        val body = ProposeRequest("propose", text.trim(), clientTurnId, OffsetDateTime.now().withNano(0).toString())
        val parsed = post(json.encodeToString(ProposeRequest.serializer(), body))
        val runId = parsed.runId ?: throw LogAgentException(parsed.message ?: parsed.error ?: "No run id returned")
        return LogAgentResult(runId, parsed.proposals, parsed.unparsed)
    }

    // Audit only: how many confirmed rows the app wrote for this run. A failure here must not affect the person's data.
    suspend fun reportCommitted(runId: String, count: Int) {
        runCatching { post(json.encodeToString(CommittedRequest.serializer(), CommittedRequest("committed", runId, count))) }
    }

    private suspend fun post(requestBody: String): LogAgentResponse {
        val token = supabase.auth.currentAccessTokenOrNull() ?: throw LogAgentException("Not signed in")
        val response = client.post("$FUNCTIONS_BASE_URL/log-agent") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        val parsed = try {
            json.decodeFromString<LogAgentResponse>(response.bodyAsText())
        } catch (e: Exception) {
            throw LogAgentException("Unreadable response from the logging service")
        }
        if (parsed.error != null) throw LogAgentException(parsed.message ?: parsed.error)
        return parsed
    }

    // Writes one ready, confirmed proposal. Throws on any failure so the caller can keep it unconfirmed and say which one failed.
    suspend fun commit(p: LogProposal) {
        require(p.isReady()) { "Proposal ${p.id} is missing ${p.kind} details" }
        val add = AddEntryRepository(supabase)
        when (p.kind) {
            "drink" -> {
                val at = p.whenLocal() ?: throw LogAgentException("No valid time for this drink")
                val ml = p.volumeMl!!
                if (p.beverageClass == "water") {
                    add.addDrink(at.toLocalDate().toString(), ml.roundToInt())
                } else {
                    NutritionMealSaveRepository(supabase).saveBeverageDrink(
                        at.toAppIso(), p.beverageClass!!, ml, p.abvPercent.takeIf { p.beverageClass == "alcohol" },
                    )
                }
            }
            "wellbeing" -> add.mergeWellbeing(p.localDate()!!, p.energy, p.mood, p.stress, p.soreness)
            "supplement_taken" -> add.addSupplementTakenById(
                (p.whenLocal() ?: throw LogAgentException("No valid time for this supplement")).toAppIso(),
                p.supplementId!!,
                p.quantity,
            )
            "recovery" -> add.addRecoverySession(p.localDate()!!, p.name!!.trim(), p.category ?: "other", p.durationMin)
            else -> throw LogAgentException("${p.kind} entries are not written from here")
        }
    }
}
