package com.bioscan.fieldterminal.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// DAV-83. A one-line "what's this for" blurb, generated once when a
// supplement is first added to the roster -- deliberately the cheapest,
// highest-free-quota model in the current lineup (gemini-3.5-flash-lite,
// confirmed against Google's own model docs rather than assumed) since this
// is a low-stakes, high-frequency call unrelated to the vision/estimation
// accuracy NutritionEstimationRepository's gemini-3.8-flash calls need.
private const val MODEL = "gemini-3.5-flash-lite"

private const val PROMPT_PREFIX = """
In one short factual sentence (under 20 words, no medical advice framing,
no disclaimers), state what this supplement is typically taken for and its
likely main effect.

Supplement: """

private val json = Json { ignoreUnknownKeys = true }

class SupplementImpactException(message: String) : Exception(message)

class SupplementImpactRepository(private val apiKey: String) {
    companion object { private val client = HttpClient(Android) }

    suspend fun describeImpact(name: String, dose: String): String {
        val requestBody = buildJsonObject {
            put(
                "contents",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "parts",
                                buildJsonArray { add(buildJsonObject { put("text", PROMPT_PREFIX.trimStart() + "$name ($dose)") }) },
                            )
                        },
                    )
                },
            )
        }

        val response = client.postGeminiWithFallback(MODEL, apiKey, requestBody.toString())

        val bodyText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw SupplementImpactException(extractErrorMessage(bodyText) ?: "Gemini request failed (${response.status.value})")
        }

        val parsed = json.decodeFromString<GeminiResponse>(bodyText)
        return parsed.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim()
            ?: throw SupplementImpactException("Gemini returned no text")
    }

    private fun extractErrorMessage(body: String): String? =
        try {
            json.decodeFromString<GeminiErrorResponse>(body).error?.message
        } catch (e: Exception) {
            null
        }
}
