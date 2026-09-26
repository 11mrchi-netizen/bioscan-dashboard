package com.bioscan.fieldterminal.data

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

// DAV-166. Calls the nutrition-estimate-text Edge Function, which holds the
// Gemini key server-side and returns structured logging *candidates* only --
// never authoritative nutrient values (that's DAV-164's resolver, once a
// candidate is matched to a canonical food). This intentionally does not
// reuse NutritionEstimationRepository -- that repository's whole job (asking
// Gemini directly for calories/macros, client-side key) is exactly what this
// ticket replaces for the new canonical-model logging path; AddEntrySheet's
// existing flat-macro Food form is untouched until DAV-168 builds the real
// review UX on top of these candidates.
private const val FUNCTIONS_BASE_URL = "https://ugfrglbcoivkprjqvjzz.supabase.co/functions/v1"

class NutritionEstimateException(message: String) : Exception(message)

@Serializable
data class NutritionCandidate(
    val description: String,
    @SerialName("quantity_value") val quantityValue: Double? = null,
    @SerialName("quantity_unit") val quantityUnit: String? = null,
    @SerialName("quantity_low") val quantityLow: Double? = null,
    @SerialName("quantity_high") val quantityHigh: Double? = null,
    val preparation: String? = null,
    @SerialName("is_beverage") val isBeverage: Boolean = false,
    @SerialName("beverage_class") val beverageClass: String? = null,
    @SerialName("caffeine_mg") val caffeineMg: Double? = null,
    @SerialName("food_confidence") val foodConfidence: Double,
    @SerialName("portion_confidence") val portionConfidence: Double,
    val ambiguous: Boolean = false,
)

@Serializable
private data class TextEstimateRequest(val text: String)

@Serializable
private data class TextEstimateResponse(
    val estimateId: Long? = null,
    val items: List<NutritionCandidate>? = null,
    val error: String? = null,
    val message: String? = null,
)

data class NutritionEstimateResult(val estimateId: Long, val candidates: List<NutritionCandidate>)

class NutritionTextEstimateRepository(private val supabase: SupabaseClient) {
    companion object { private val client = HttpClient(Android) }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun estimate(description: String): NutritionEstimateResult {
        val token = supabase.auth.currentAccessTokenOrNull()
            ?: throw NutritionEstimateException("Not signed in")

        val response = client.post("$FUNCTIONS_BASE_URL/nutrition-estimate-text") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(TextEstimateRequest(description)))
        }

        val parsed = json.decodeFromString<TextEstimateResponse>(response.bodyAsText())
        if (parsed.error != null) throw NutritionEstimateException(parsed.message ?: parsed.error)
        val estimateId = parsed.estimateId ?: throw NutritionEstimateException("Gemini returned no estimate id")
        return NutritionEstimateResult(estimateId, parsed.items ?: emptyList())
    }
}
