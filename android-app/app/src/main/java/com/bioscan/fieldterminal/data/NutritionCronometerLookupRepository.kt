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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive

private const val FUNCTIONS_BASE_URL = "https://ugfrglbcoivkprjqvjzz.supabase.co/functions/v1"

class NutritionCronometerLookupException(message: String) : Exception(message)

@Serializable
private data class CronometerRequest(val description: String)

@Serializable
private data class CronometerItemsRequest(val items: List<CronometerItemInput>)

@Serializable
data class CronometerItemInput(
    val description: String,
    @SerialName("quantity_g") val quantityG: Double? = null,
)

@Serializable
data class CronometerItemResult(
    val query: String,
    @SerialName("cronometer_food_name") val cronometerFoodName: String? = null,
    @SerialName("cronometer_source") val cronometerSource: String? = null,
    @SerialName("quantity_g") val quantityG: Double,
    @SerialName("match_quality") val matchQuality: String,
    @SerialName("nutrients_for_portion") val nutrientsForPortion: JsonObject = JsonObject(emptyMap()),
)

@Serializable
private data class CronometerResponse(
    val mode: String? = null,
    @SerialName("original_description") val originalDescription: String? = null,
    val results: List<CronometerItemResult>? = null,
    val totals: JsonObject? = null,
    @SerialName("nutrient_units") val nutrientUnits: Map<String, String>? = null,
    @SerialName("items_included") val itemsIncluded: Int? = null,
    @SerialName("items_skipped") val itemsSkipped: List<String>? = null,
    val error: String? = null,
    val message: String? = null,
)

data class CronometerEnrichment(
    val calories: Double?,
    val proteinG: Double?,
    val carbsG: Double?,
    val fatG: Double?,
    val fiberG: Double?,
    val sugarG: Double?,
    val sodiumMg: Double?,
    val allNutrients: Map<String, Double>,
    val nutrientUnits: Map<String, String>,
    val items: List<CronometerItemResult>,
    val itemsIncluded: Int,
    val itemsSkipped: List<String>,
    val primarySource: String?,
)

class NutritionCronometerLookupRepository(private val supabase: SupabaseClient) {
    companion object {
        private val client = HttpClient(Android)
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun enrich(mealDescription: String): CronometerEnrichment =
        callLookup(json.encodeToString(CronometerRequest(mealDescription)))

    suspend fun enrichItems(items: List<CronometerItemInput>): CronometerEnrichment =
        callLookup(json.encodeToString(CronometerItemsRequest(items)))

    private suspend fun callLookup(requestBody: String): CronometerEnrichment {
        val token = supabase.auth.currentAccessTokenOrNull()
            ?: throw NutritionCronometerLookupException("Not signed in")

        val response = client.post("$FUNCTIONS_BASE_URL/nutrition-cronometer-lookup") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        val body = response.bodyAsText()
        val parsed = json.decodeFromString<CronometerResponse>(body)

        if (parsed.error != null) {
            throw NutritionCronometerLookupException(parsed.message ?: parsed.error)
        }

        val totals = parsed.totals ?: throw NutritionCronometerLookupException("No totals returned")
        val allNutrients = totals.mapValues { (_, v) -> v.jsonPrimitive.double }
        val items = parsed.results ?: emptyList()

        val primarySource = items
            .filter { it.matchQuality == "exact" || it.matchQuality == "good" }
            .groupBy { it.cronometerSource }
            .maxByOrNull { it.value.size }
            ?.key

        return CronometerEnrichment(
            calories = allNutrients["calories"],
            proteinG = allNutrients["protein"],
            carbsG = allNutrients["carbs"],
            fatG = allNutrients["fat"],
            fiberG = allNutrients["fiber"],
            sugarG = allNutrients["sugar"],
            sodiumMg = allNutrients["sodium"],
            allNutrients = allNutrients,
            nutrientUnits = parsed.nutrientUnits ?: emptyMap(),
            items = items,
            itemsIncluded = parsed.itemsIncluded ?: 0,
            itemsSkipped = parsed.itemsSkipped ?: emptyList(),
            primarySource = primarySource,
        )
    }
}
