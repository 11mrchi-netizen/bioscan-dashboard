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
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    // A specific Cronometer food (the user picked it from the alternatives); skips the search.
    @SerialName("food_id") val foodId: Long? = null,
)

@Serializable
data class CronometerAlternative(
    @SerialName("food_id") val foodId: Long,
    val name: String,
    val source: String? = null,
    @SerialName("kcal_per_100g") val kcalPer100g: Double? = null,
)

@Serializable
data class CronometerAiEstimate(
    val calories: Double,
    @SerialName("protein_g") val proteinG: Double = 0.0,
    @SerialName("carbs_g") val carbsG: Double = 0.0,
    @SerialName("fat_g") val fatG: Double = 0.0,
)

@Serializable
data class CronometerItemResult(
    val query: String,
    @SerialName("cronometer_food_id") val cronometerFoodId: Long? = null,
    @SerialName("cronometer_food_name") val cronometerFoodName: String? = null,
    @SerialName("cronometer_source") val cronometerSource: String? = null,
    @SerialName("quantity_g") val quantityG: Double,
    @SerialName("serving_used") val servingUsed: String? = null,
    @SerialName("match_quality") val matchQuality: String,
    @SerialName("nutrients_for_portion") val nutrientsForPortion: JsonObject = JsonObject(emptyMap()),
    val alternatives: List<CronometerAlternative> = emptyList(),
    val rejected: List<String> = emptyList(),
    @SerialName("memory_key") val memoryKey: String? = null,
    @SerialName("portion_count") val portionCount: Double? = null,
    @SerialName("portion_unit") val portionUnit: String? = null,
    @SerialName("from_memory") val fromMemory: Boolean = false,
    @SerialName("ai_estimate") val aiEstimate: CronometerAiEstimate? = null,
    // The AI estimate stands in for this item (no trustworthy match, or a loose match that disagreed with it).
    @SerialName("used_estimate") val usedEstimate: Boolean = false,
    val disagreement: Boolean = false,
    // Device-only: the user picked this food or changed its grams on the confirm sheet.
    @Transient val userEdited: Boolean = false,
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
    val confidence: Double? = null,
    @SerialName("ai_estimate_total") val aiEstimateTotal: Double? = null,
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
    val confidence: Double = 0.85,
    // Gemini's own calorie estimate for the whole entry, independent of the database match.
    val aiEstimateTotal: Double? = null,
)

private fun CronometerItemResult.isMatched(): Boolean = matchQuality == "exact" || matchQuality == "good"

// Whether this item contributes to the totals (a database match, or the AI estimate standing in for it).
fun CronometerItemResult.counts(): Boolean = (usedEstimate && aiEstimate != null) || isMatched()

fun CronometerItemResult.portionNutrients(): Map<String, Double> = when {
    usedEstimate && aiEstimate != null -> mapOf(
        "calories" to aiEstimate.calories,
        "protein" to aiEstimate.proteinG,
        "carbs" to aiEstimate.carbsG,
        "fat" to aiEstimate.fatG,
    )
    isMatched() -> nutrientsForPortion.mapValues { (_, v) -> v.jsonPrimitive.double }
    else -> emptyMap()
}

fun CronometerItemResult.calories(): Double? = portionNutrients()["calories"]

// Same item at a different weight: database nutrients and the AI estimate scale together.
fun CronometerItemResult.withGrams(grams: Double): CronometerItemResult {
    if (quantityG <= 0 || grams <= 0) return this
    val f = grams / quantityG
    return copy(
        quantityG = grams,
        servingUsed = "${round2(grams)} g",
        nutrientsForPortion = JsonObject(nutrientsForPortion.mapValues { (_, v) -> JsonPrimitive(round2(v.jsonPrimitive.double * f)) }),
        aiEstimate = aiEstimate?.let {
            it.copy(calories = it.calories * f, proteinG = it.proteinG * f, carbsG = it.carbsG * f, fatG = it.fatG * f)
        },
        userEdited = true,
    )
}

// The user confirmed the database food over the AI estimate.
fun CronometerItemResult.usingDatabaseMatch(): CronometerItemResult =
    copy(usedEstimate = false, matchQuality = "exact", userEdited = true)

private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

private fun primarySourceOf(items: List<CronometerItemResult>): String? = items
    .filter { it.isMatched() && !it.usedEstimate }
    .groupBy { it.cronometerSource }
    .maxByOrNull { it.value.size }
    ?.key

// Mirrors the Edge Function's calorie-weighted confidence; a user-confirmed item counts as well-known.
private fun confidenceOf(items: List<CronometerItemResult>): Double {
    var weight = 0.0
    var sum = 0.0
    for (item in items) {
        val c = when {
            !item.counts() -> continue
            item.userEdited -> 0.9
            item.usedEstimate -> 0.5
            item.disagreement -> 0.6
            item.matchQuality == "exact" -> 0.95
            else -> 0.85
        }
        val w = maxOf(item.calories() ?: 0.0, 1.0)
        weight += w
        sum += c * w
    }
    return if (weight > 0) round2(sum / weight) else 0.3
}

// Totals rebuilt from the items after an edit on the confirm sheet.
fun CronometerEnrichment.withItems(newItems: List<CronometerItemResult>, extraUnits: Map<String, String> = emptyMap()): CronometerEnrichment {
    val totals = mutableMapOf<String, Double>()
    newItems.forEach { item -> item.portionNutrients().forEach { (k, v) -> totals[k] = (totals[k] ?: 0.0) + v } }
    val rounded = totals.mapValues { (_, v) -> round2(v) }
    return copy(
        calories = rounded["calories"],
        proteinG = rounded["protein"],
        carbsG = rounded["carbs"],
        fatG = rounded["fat"],
        fiberG = rounded["fiber"],
        sugarG = rounded["sugar"],
        sodiumMg = rounded["sodium"],
        allNutrients = rounded,
        nutrientUnits = nutrientUnits + extraUnits,
        items = newItems,
        itemsIncluded = newItems.count { it.counts() },
        itemsSkipped = newItems.filterNot { it.counts() }.map { it.query },
        primarySource = primarySourceOf(newItems),
        confidence = confidenceOf(newItems),
    )
}

class NutritionCronometerLookupRepository(private val supabase: SupabaseClient) {
    companion object {
        private val client = functionsClient(90_000)
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
            primarySource = primarySourceOf(items),
            confidence = parsed.confidence ?: confidenceOf(items),
            aiEstimateTotal = parsed.aiEstimateTotal,
        )
    }
}
