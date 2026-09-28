package com.bioscan.fieldterminal.data

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.delay

// Gemini's flash models intermittently answer generateContent with a plain
// 503 ("model overloaded") that has nothing to do with the request itself --
// retrying a moment later usually succeeds. Every Gemini call in this app
// (NutritionEstimationRepository, LabExtractionRepository,
// SupplementImpactRepository) now goes through here instead of posting to
// its own model's endpoint directly, so they all get the same retry-then-
// fall-back-to-a-second-model behavior instead of surfacing a 503 to the
// user for what's usually a transient condition.
// DAV-220: retargeted from gemini-3.5-flash-lite to gemini-3.1-flash-lite so
// SupplementImpactRepository (primaryModel already gemini-3.5-flash-lite)
// gets a real second model on fallback instead of a no-op (primaryModel ==
// old FALLBACK_MODEL meant the fallback branch below was always skipped for
// it). Also now triggers on 429 (daily quota exhausted), not just 503 --
// retrying the same model on a 429 can't succeed until tomorrow, so that
// case skips straight to the fallback model's own separate quota.
private const val FALLBACK_MODEL = "gemini-3.1-flash-lite"
private val RETRY_DELAYS_MS = listOf(1_000L, 2_000L)

internal suspend fun HttpClient.postGeminiWithFallback(
    primaryModel: String,
    apiKey: String,
    requestBody: String,
): HttpResponse {
    suspend fun attempt(model: String): HttpResponse =
        post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent") {
            url { parameters.append("key", apiKey) }
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

    var response = attempt(primaryModel)

    if (response.status == HttpStatusCode.TooManyRequests && primaryModel != FALLBACK_MODEL) {
        return attempt(FALLBACK_MODEL)
    }

    for (delayMs in RETRY_DELAYS_MS) {
        if (response.status != HttpStatusCode.ServiceUnavailable) return response
        delay(delayMs)
        response = attempt(primaryModel)
    }

    if ((response.status == HttpStatusCode.ServiceUnavailable || response.status == HttpStatusCode.TooManyRequests) &&
        primaryModel != FALLBACK_MODEL
    ) {
        response = attempt(FALLBACK_MODEL)
    }

    return response
}
