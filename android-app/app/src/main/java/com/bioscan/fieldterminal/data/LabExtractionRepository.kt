package com.bioscan.fieldterminal.data

import android.util.Base64
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

// DAV-85. gemini-3.8-flash, not the flash-lite tier DAV-83's low-stakes
// supplement blurb uses -- extracting real values off a scanned/photographed
// report is an accuracy-sensitive vision task, the same bar
// NutritionEstimationRepository's photo path already holds itself to, not a
// high-frequency, low-stakes call.
private const val MODEL = "gemini-3.8-flash"

private val json = Json { ignoreUnknownKeys = true }

class LabExtractionException(message: String) : Exception(message)

data class ExtractedLabMarker(
    val markerName: String,
    val value: Double?,
    val unit: String?,
    val refLow: Double?,
    val refHigh: Double?,
)

class LabExtractionRepository(private val apiKey: String) {
    private val client = HttpClient(Android)

    suspend fun extract(fileBytes: ByteArray, mimeType: String, knownMarkerNames: List<String>): List<ExtractedLabMarker> {
        val prompt = buildString {
            append(
                "Extract every lab marker (test name, result value, unit, and reference range if " +
                    "shown) from this lab report. For each marker, if it is clearly the same analyte " +
                    "as one of these existing names, use that exact name; otherwise use the report's " +
                    "own name:\n",
            )
            append(knownMarkerNames.joinToString(", "))
            append(
                "\n\nOnly include markers with a real numeric result value. If a field genuinely " +
                    "isn't shown for a marker, omit it rather than guessing.",
            )
        }

        val base64Data = Base64.encodeToString(fileBytes, Base64.NO_WRAP)
        val parts = buildJsonArray {
            add(buildJsonObject { put("text", prompt) })
            add(
                buildJsonObject {
                    put(
                        "inline_data",
                        buildJsonObject {
                            put("mime_type", mimeType)
                            put("data", base64Data)
                        },
                    )
                },
            )
        }

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
            throw LabExtractionException(extractErrorMessage(bodyText) ?: "Gemini request failed (${response.status.value})")
        }

        val parsed = json.decodeFromString<GeminiResponse>(bodyText)
        val payloadJson = parsed.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
            ?: throw LabExtractionException("Gemini returned no extraction")

        val markers = json.decodeFromString<List<MarkerPayload>>(payloadJson)
        if (markers.isEmpty()) throw LabExtractionException("No markers found in this file")

        return markers.map {
            ExtractedLabMarker(markerName = it.markerName, value = it.value, unit = it.unit, refLow = it.refLow, refHigh = it.refHigh)
        }
    }

    private fun extractErrorMessage(body: String): String? =
        try {
            json.decodeFromString<GeminiErrorResponse>(body).error?.message
        } catch (e: Exception) {
            null
        }
}

private val RESPONSE_SCHEMA = buildJsonObject {
    put("type", "ARRAY")
    put(
        "items",
        buildJsonObject {
            put("type", "OBJECT")
            put(
                "properties",
                buildJsonObject {
                    put("marker_name", buildJsonObject { put("type", "STRING") })
                    put("value", buildJsonObject { put("type", "NUMBER") })
                    put("unit", buildJsonObject { put("type", "STRING") })
                    put("ref_low", buildJsonObject { put("type", "NUMBER") })
                    put("ref_high", buildJsonObject { put("type", "NUMBER") })
                },
            )
            put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("marker_name")) })
        },
    )
}

@Serializable
private data class MarkerPayload(
    @SerialName("marker_name") val markerName: String,
    val value: Double? = null,
    val unit: String? = null,
    @SerialName("ref_low") val refLow: Double? = null,
    @SerialName("ref_high") val refHigh: Double? = null,
)
