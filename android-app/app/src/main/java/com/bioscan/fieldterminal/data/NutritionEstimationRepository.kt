package com.bioscan.fieldterminal.data

import android.util.Base64
import com.bioscan.fieldterminal.domain.FoodEstimate
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Step 13: sends a food photo to Gemini's generateContent endpoint and asks
// for structured JSON back (response_schema) instead of free text, so this
// never has to parse numbers out of prose. Model id and REST shape
// (inline_data/mime_type, response_mime_type/response_schema) confirmed live
// against Google's own API docs rather than assumed, since a wrong model id
// just 404s at runtime with no compile-time warning.
//
// See ROADMAP.md P8 Step 13 for why this pre-fills the Food form instead of
// auto-saving: published 2026 research on LLM food-photo estimation found
// real accuracy comparable to traditional self-reported logging but not
// precise enough to save unreviewed (~35-38% MAPE, worse on mixed/sauced
// dishes). The prompt also tells the model not to read any visible
// nutrition-label text -- the same research found that instruction improves
// reliability when no label is present in the photo.
private const val MODEL = "gemini-3.8-flash"

private const val PHOTO_PROMPT = """
Look at this food photo and estimate its nutritional content as eaten. Give
your best estimate of total calories, protein (g), carbs (g), fat (g),
fiber (g), sugar (g), and sodium (mg) for everything visible in the photo,
plus a short one-line description of the food. Do not attempt to read any
nutrition label text visible in the photo -- base the estimate on visual
inspection of the food itself. If you genuinely cannot estimate a field,
omit it from your response rather than guessing a number.
"""

// DAV-90: same estimate, from a typed description instead of a photo, for
// whenever there's no photo to take. Real accuracy here is a text-reasoning
// task rather than a vision one, but subject to the same caveat as the
// photo path (see below) -- still review-before-save, never auto-saved.
private const val TEXT_PROMPT = """
Estimate the nutritional content of the food described below, as eaten.
Give your best estimate of total calories, protein (g), carbs (g), fat (g),
fiber (g), sugar (g), and sodium (mg) for everything described, plus a
short one-line cleaned-up description of the food. If the description is
too vague to estimate a field, omit it from your response rather than
guessing a number.

Food description: """

private val json = Json { ignoreUnknownKeys = true }

class NutritionEstimationException(message: String) : Exception(message)

class NutritionEstimationRepository(private val apiKey: String) {
    private val client = HttpClient(Android)

    suspend fun estimate(imageBytes: ByteArray, mimeType: String = "image/jpeg"): FoodEstimate {
        val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val parts = buildJsonArray {
            add(buildJsonObject { put("text", PHOTO_PROMPT.trim()) })
            add(
                buildJsonObject {
                    put(
                        "inline_data",
                        buildJsonObject {
                            put("mime_type", mimeType)
                            put("data", base64Image)
                        },
                    )
                },
            )
        }
        return runEstimate(parts)
    }

    // DAV-90.
    suspend fun estimateFromDescription(description: String): FoodEstimate {
        val parts = buildJsonArray {
            add(buildJsonObject { put("text", TEXT_PROMPT.trimStart() + description.trim()) })
        }
        return runEstimate(parts)
    }

    private suspend fun runEstimate(parts: kotlinx.serialization.json.JsonArray): FoodEstimate {
        val requestBody = buildJsonObject {
            put("contents", buildJsonArray { add(buildJsonObject { put("parts", parts) }) })
            put(
                "generationConfig",
                buildJsonObject {
                    put("response_mime_type", "application/json")
                    put("response_schema", RESPONSE_SCHEMA)
                },
            )
        }

        val response = client.postGeminiWithFallback(MODEL, apiKey, requestBody.toString())

        val bodyText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw NutritionEstimationException(extractErrorMessage(bodyText) ?: "Gemini request failed (${response.status.value})")
        }

        val parsed = json.decodeFromString<GeminiResponse>(bodyText)
        val estimateJson = parsed.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
            ?: throw NutritionEstimationException("Gemini returned no estimate")

        val payload = json.decodeFromString<EstimatePayload>(estimateJson)
        return FoodEstimate(
            description = payload.foodDescription,
            calories = payload.calories,
            proteinG = payload.proteinG,
            carbsG = payload.carbsG,
            fatG = payload.fatG,
            fiberG = payload.fiberG,
            sugarG = payload.sugarG,
            sodiumMg = payload.sodiumMg,
        )
    }

    private fun extractErrorMessage(body: String): String? =
        try {
            json.decodeFromString<GeminiErrorResponse>(body).error?.message
        } catch (e: Exception) {
            null
        }
}

private val RESPONSE_SCHEMA = buildJsonObject {
    put("type", "OBJECT")
    put(
        "properties",
        buildJsonObject {
            put("food_description", buildJsonObject { put("type", "STRING") })
            put("calories", buildJsonObject { put("type", "NUMBER") })
            put("protein_g", buildJsonObject { put("type", "NUMBER") })
            put("carbs_g", buildJsonObject { put("type", "NUMBER") })
            put("fat_g", buildJsonObject { put("type", "NUMBER") })
            put("fiber_g", buildJsonObject { put("type", "NUMBER") })
            put("sugar_g", buildJsonObject { put("type", "NUMBER") })
            put("sodium_mg", buildJsonObject { put("type", "NUMBER") })
        },
    )
}

@Serializable
private data class EstimatePayload(
    @SerialName("food_description") val foodDescription: String? = null,
    val calories: Double? = null,
    @SerialName("protein_g") val proteinG: Double? = null,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("fat_g") val fatG: Double? = null,
    @SerialName("fiber_g") val fiberG: Double? = null,
    @SerialName("sugar_g") val sugarG: Double? = null,
    @SerialName("sodium_mg") val sodiumMg: Double? = null,
)

