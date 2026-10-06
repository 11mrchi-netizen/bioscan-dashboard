package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.CaffeineWindowResult
import com.bioscan.fieldterminal.domain.HydrationIntelligenceResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// DAV-183. Gemini explanation for the hydration + caffeine cards.
// Prompt is built entirely from structured validated outputs (per DAV-183's
// own rule: "not raw model guessing") -- Gemini interprets the numbers,
// never derives them. Low-stakes, cheap model same as SupplementImpact.
private const val MODEL = "gemini-3.5-flash-lite"
private val json = Json { ignoreUnknownKeys = true }

class HydrationExplanationException(message: String) : Exception(message)

class HydrationExplanationRepository(private val apiKey: String) {
    companion object { private val client = HttpClient(Android) }

    suspend fun explain(
        hydration: HydrationIntelligenceResult,
        caffeine: CaffeineWindowResult?,
    ): String {
        val requestBody = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", buildPrompt(hydration, caffeine)) })
                    })
                })
            })
        }
        val response = client.postGeminiWithFallback(MODEL, apiKey, requestBody.toString())
        val bodyText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw HydrationExplanationException(extractErrorMessage(bodyText) ?: "Gemini request failed (${response.status.value})")
        }
        return json.decodeFromString<GeminiResponse>(bodyText)
            .candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim()
            ?: throw HydrationExplanationException("Gemini returned no text")
    }

    private fun buildPrompt(hydration: HydrationIntelligenceResult, caffeine: CaffeineWindowResult?): String =
        buildString {
            appendLine("Validated hydration and caffeine data for today (model confidence: ${hydration.confidence.label}):")
            hydration.measuredIntakeMl?.let { appendLine("- Measured fluid intake: %.0f ml".format(it)) }
            hydration.effectiveHydrationMl?.let { appendLine("- Effective hydration (modelled): %.0f ml".format(it)) }
            hydration.inferredDemandMl?.let { appendLine("- Exercise-derived demand (estimated): %.0f ml".format(it)) }
            if (!hydration.environmentalContextAvailable) {
                appendLine("- Environmental context (heat/humidity): not available.")
            }
            caffeine?.let { cw ->
                appendLine("- Caffeine residual now: %.0f mg".format(cw.residualNowMg))
                cw.residualAtBedtimeMg?.let { appendLine("- Caffeine residual at 11pm: %.0f mg".format(it)) }
                cw.cutoffHour?.let { if (it > 0) appendLine("- Advisory last dose by: %02d:00".format(it)) }
            }
            appendLine()
            append("In 2-3 short sentences, interpret what these numbers mean for today's hydration and caffeine status. Be specific to the numbers. No medical advice. No disclaimers.")
        }

    private fun extractErrorMessage(body: String): String? =
        try { json.decodeFromString<GeminiErrorResponse>(body).error?.message } catch (e: Exception) { null }
}
