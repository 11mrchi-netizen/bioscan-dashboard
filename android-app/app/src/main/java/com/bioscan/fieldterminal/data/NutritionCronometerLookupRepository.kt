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
    // A specific food from the app's own catalog (barcode product, scanned label); a separate id space from food_id.
    @SerialName("catalog_food_id") val catalogFoodId: Long? = null,
)

@Serializable
data class FoodMeasure(val name: String, val grams: Double)

@Serializable
data class FoodSuggestionRef(val kind: String, val id: Long)

// A food to pick while typing a replacement or a new ingredient: the user's remembered choice, an app catalog food or a
// Cronometer food. kcal and measures may be missing for lower-ranked Cronometer hits (fetched on pick).
@Serializable
data class FoodSuggestion(
    val ref: FoodSuggestionRef,
    val name: String,
    val source: String? = null,
    @SerialName("kcal_per_100g") val kcalPer100g: Double? = null,
    val measures: List<FoodMeasure> = emptyList(),
    @SerialName("default_grams") val defaultGrams: Double = 100.0,
    val remembered: Boolean = false,
)

@Serializable
private data class SuggestQuery(val query: String, val limit: Int)

@Serializable
private data class SuggestRequest(val suggest: SuggestQuery)

@Serializable
private data class SuggestResponse(
    val suggestions: List<FoodSuggestion>? = null,
    val error: String? = null,
    val message: String? = null,
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
    @SerialName("catalog_food_id") val catalogFoodId: Long? = null,
    @SerialName("cronometer_food_name") val cronometerFoodName: String? = null,
    @SerialName("cronometer_source") val cronometerSource: String? = null,
    @SerialName("quantity_g") val quantityG: Double,
    @SerialName("serving_used") val servingUsed: String? = null,
    @SerialName("match_quality") val matchQuality: String,
    @SerialName("nutrients_for_portion") val nutrientsForPortion: JsonObject = JsonObject(emptyMap()),
    @SerialName("nutrients_per_100g") val nutrientsPer100g: JsonObject = JsonObject(emptyMap()),
    @SerialName("all_measures") val allMeasures: List<FoodMeasure> = emptyList(),
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

// Same item at a different weight: database nutrients come from its per-100 g values (so even a 0 g item can be fixed),
// and the AI estimate scales with them when it can.
fun CronometerItemResult.withGrams(grams: Double): CronometerItemResult {
    if (grams <= 0) return this
    val ratio = if (quantityG > 0) grams / quantityG else null
    val per100 = nutrientsPer100g.mapValues { (_, v) -> v.jsonPrimitive.double }
    val portion = when {
        per100.isNotEmpty() -> per100.mapValues { (_, v) -> round2(v * grams / 100.0) }
        ratio != null -> nutrientsForPortion.mapValues { (_, v) -> round2(v.jsonPrimitive.double * ratio) }
        else -> null
    }
    return copy(
        quantityG = grams,
        servingUsed = "${formatAmount(grams)} g",
        nutrientsForPortion = portion?.let { p -> JsonObject(p.mapValues { (_, v) -> JsonPrimitive(v) }) } ?: nutrientsForPortion,
        aiEstimate = if (ratio == null) aiEstimate else aiEstimate?.let {
            it.copy(calories = it.calories * ratio, proteinG = it.proteinG * ratio, carbsG = it.carbsG * ratio, fatG = it.fatG * ratio)
        },
        userEdited = true,
    )
}

private val VOLUME_OR_CUT = Regex("""\b(cups?|tbsp|tsp|tablespoons?|teaspoons?|fl\.?\s?oz|oz|ml|g|slices?|chopped|diced|sliced)\b""", RegexOption.IGNORE_CASE)

// A count of one of the food's own servings ("1.5 x medium"). A whole unit is remembered per unit, so "2 apples" next
// time is twice the grams picked here; volumes and cuts are not.
fun CronometerItemResult.withMeasure(measure: FoodMeasure, count: Double): CronometerItemResult {
    if (count <= 0 || measure.grams <= 0) return this
    val wholeUnit = !VOLUME_OR_CUT.containsMatchIn(measure.name)
    return withGrams(measure.grams * count).copy(
        servingUsed = "${formatAmount(count)} x ${measure.name}",
        portionCount = if (wholeUnit) count else null,
        portionUnit = null,
    )
}

fun formatAmount(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else "%.1f".format(v)

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

fun emptyEnrichment(): CronometerEnrichment = CronometerEnrichment(
    calories = null, proteinG = null, carbsG = null, fatG = null, fiberG = null, sugarG = null, sodiumMg = null,
    allNutrients = emptyMap(), nutrientUnits = emptyMap(), items = emptyList(), itemsIncluded = 0,
    itemsSkipped = emptyList(), primarySource = null,
)

class NutritionCronometerLookupRepository(private val supabase: SupabaseClient) {
    companion object {
        private val client = functionsClient(90_000)
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun enrich(mealDescription: String): CronometerEnrichment =
        callLookup(json.encodeToString(CronometerRequest(mealDescription)))

    suspend fun enrichItems(items: List<CronometerItemInput>): CronometerEnrichment =
        callLookup(json.encodeToString(CronometerItemsRequest(items)))

    // Foods matching what the user is typing. Throws on a failed call (the caller keeps the previous suggestions).
    suspend fun suggest(query: String, limit: Int = 8): List<FoodSuggestion> {
        val response = client.post("$FUNCTIONS_BASE_URL/nutrition-cronometer-lookup") {
            header("Authorization", "Bearer ${token()}")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(SuggestRequest(SuggestQuery(query, limit))))
        }
        val parsed = json.decodeFromString<SuggestResponse>(response.bodyAsText())
        if (parsed.error != null) throw NutritionCronometerLookupException(parsed.message ?: parsed.error)
        return parsed.suggestions ?: emptyList()
    }

    // The picked suggestion as a full item (nutrients for the given grams, or for its default serving).
    suspend fun resolve(query: String, suggestion: FoodSuggestion, grams: Double?): CronometerEnrichment =
        enrichItems(
            listOf(
                CronometerItemInput(
                    description = query,
                    quantityG = grams,
                    foodId = suggestion.ref.id.takeIf { suggestion.ref.kind == "cronometer" },
                    catalogFoodId = suggestion.ref.id.takeIf { suggestion.ref.kind == "catalog" },
                ),
            ),
        )

    private fun token(): String = supabase.auth.currentAccessTokenOrNull()
        ?: throw NutritionCronometerLookupException("Not signed in")

    private suspend fun callLookup(requestBody: String): CronometerEnrichment {
        val response = client.post("$FUNCTIONS_BASE_URL/nutrition-cronometer-lookup") {
            header("Authorization", "Bearer ${token()}")
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
