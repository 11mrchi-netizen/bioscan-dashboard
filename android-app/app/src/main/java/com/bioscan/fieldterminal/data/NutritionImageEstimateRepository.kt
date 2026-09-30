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

// DAV-167, reworked 2026-09-30 (user request): one or more photos of the
// same meal in, one approximate but directly usable whole-meal estimate out
// -- no per-item database-matching step. Images are sent inline as base64
// and never written anywhere by this repository or the function it calls;
// they exist only for the duration of the one request.
private const val FUNCTIONS_BASE_URL = "https://ugfrglbcoivkprjqvjzz.supabase.co/functions/v1"

class NutritionImageEstimateException(message: String) : Exception(message)

@Serializable
data class NutritionMealEstimate(
    val description: String,
    val calories: Double,
    @SerialName("protein_g") val proteinG: Double,
    @SerialName("carbs_g") val carbsG: Double,
    @SerialName("fat_g") val fatG: Double,
    @SerialName("fiber_g") val fiberG: Double? = null,
    @SerialName("sugar_g") val sugarG: Double? = null,
    @SerialName("sodium_mg") val sodiumMg: Double? = null,
    val confidence: Double,
)

@Serializable
private data class ImageEstimateRequest(
    val images: List<String>,
    val mimeType: String,
)

@Serializable
private data class ImageEstimateResponse(
    val estimateId: Long? = null,
    val estimate: NutritionMealEstimate? = null,
    val error: String? = null,
    val message: String? = null,
)

data class NutritionImageEstimateResult(val estimateId: Long, val estimate: NutritionMealEstimate)

class NutritionImageEstimateRepository(private val supabase: SupabaseClient) {
    companion object { private val client = HttpClient(Android) }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun estimate(images: List<ByteArray>, mimeType: String = "image/jpeg"): NutritionImageEstimateResult {
        require(images.isNotEmpty()) { "At least one photo is required" }
        val token = supabase.auth.currentAccessTokenOrNull()
            ?: throw NutritionImageEstimateException("Not signed in")

        val requestBody = ImageEstimateRequest(
            images = images.map { Base64.encodeToString(it, Base64.NO_WRAP) },
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
        val estimate = parsed.estimate ?: throw NutritionImageEstimateException("Gemini returned no usable estimate")
        return NutritionImageEstimateResult(estimateId, estimate)
    }
}
