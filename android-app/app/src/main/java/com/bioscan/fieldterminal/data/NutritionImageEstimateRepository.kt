package com.bioscan.fieldterminal.data

import android.util.Base64
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

// DAV-167. Calls the nutrition-estimate-image Edge Function -- same
// candidates-only contract as NutritionTextEstimateRepository (DAV-166), a
// vision prompt in place of a text one. The image is sent inline as base64
// and never written anywhere by this repository or the function it calls;
// it exists only for the duration of the one request, per this ticket's
// "original images are temporary/not retained permanently by default" rule.
private const val FUNCTIONS_BASE_URL = "https://ugfrglbcoivkprjqvjzz.supabase.co/functions/v1"

class NutritionImageEstimateException(message: String) : Exception(message)

@Serializable
data class NutritionImageCandidate(
    val description: String,
    @SerialName("quantity_low") val quantityLow: Double? = null,
    @SerialName("quantity_high") val quantityHigh: Double? = null,
    @SerialName("quantity_unit") val quantityUnit: String? = null,
    val preparation: String? = null,
    @SerialName("is_beverage") val isBeverage: Boolean = false,
    @SerialName("beverage_class") val beverageClass: String? = null,
    @SerialName("food_confidence") val foodConfidence: Double,
    @SerialName("portion_confidence") val portionConfidence: Double,
    val ambiguous: Boolean = false,
)

@Serializable
private data class ImageEstimateRequest(
    val imageBase64: String,
    val mimeType: String,
)

@Serializable
private data class ImageEstimateResponse(
    val estimateId: Long? = null,
    val items: List<NutritionImageCandidate>? = null,
    val error: String? = null,
    val message: String? = null,
)

data class NutritionImageEstimateResult(val estimateId: Long, val candidates: List<NutritionImageCandidate>)

class NutritionImageEstimateRepository(private val supabase: SupabaseClient) {
    companion object { private val client = HttpClient(Android) }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun estimate(imageBytes: ByteArray, mimeType: String = "image/jpeg"): NutritionImageEstimateResult {
        val token = supabase.auth.currentAccessTokenOrNull()
            ?: throw NutritionImageEstimateException("Not signed in")

        val requestBody = ImageEstimateRequest(
            imageBase64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP),
            mimeType = mimeType,
        )
        val response = client.post("$FUNCTIONS_BASE_URL/nutrition-estimate-image") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(requestBody))
        }

        val parsed = json.decodeFromString<ImageEstimateResponse>(response.bodyAsText())
        if (parsed.error != null) throw NutritionImageEstimateException(parsed.message ?: parsed.error)
        val estimateId = parsed.estimateId ?: throw NutritionImageEstimateException("Gemini returned no estimate id")
        return NutritionImageEstimateResult(estimateId, parsed.items ?: emptyList())
    }
}
