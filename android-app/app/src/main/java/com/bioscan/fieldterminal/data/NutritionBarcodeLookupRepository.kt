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
import com.bioscan.fieldterminal.data.model.FoodServingRow
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive

// DAV-165. Calls the nutrition-barcode-lookup Edge Function (server-side
// Open Food Facts lookup + cache-as-foods-table) instead of talking to Open
// Food Facts directly -- keeps catalog writes behind the service-role key,
// per that function's own RLS-driven design (foods/food_nutrients are
// SELECT-only for authenticated users). A plain Ktor POST + the current
// session's JWT, matching this app's existing Gemini-call shape
// (GeminiClient.kt) rather than adding the functions-kt SDK module for one
// endpoint.
private const val FUNCTIONS_BASE_URL = "https://ugfrglbcoivkprjqvjzz.supabase.co/functions/v1"

class BarcodeLookupException(message: String) : Exception(message)

@Serializable
data class BarcodeLookupFood(
    val id: Long,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    val category: String? = null,
    @SerialName("beverage_class") val beverageClass: String? = null,
    @SerialName("beverage_subtype") val beverageSubtype: String? = null,
)

@Serializable
private data class BarcodeLookupRequest(val barcode: String)

@Serializable
private data class BarcodeLookupResponse(
    val found: Boolean = false,
    val cached: Boolean? = null,
    val food: BarcodeLookupFood? = null,
    val error: String? = null,
    val message: String? = null,
)

class NutritionBarcodeLookupRepository(private val supabase: SupabaseClient) {
    companion object { private val client = functionsClient(20_000) }
    private val json = Json { ignoreUnknownKeys = true }

    // Returns null when the barcode is genuinely unrecognized by Open Food
    // Facts (never a fabricated food) -- the caller falls back to manual
    // search/entry in that case.
    suspend fun lookup(barcode: String): BarcodeLookupFood? {
        val token = supabase.auth.currentAccessTokenOrNull()
            ?: throw BarcodeLookupException("Not signed in")

        val response = client.post("$FUNCTIONS_BASE_URL/nutrition-barcode-lookup") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(BarcodeLookupRequest(barcode)))
        }

        val parsed = json.decodeFromString<BarcodeLookupResponse>(response.bodyAsText())
        if (parsed.error != null) throw BarcodeLookupException(parsed.message ?: parsed.error)
        return parsed.food.takeIf { parsed.found }
    }

    // The package label's own numbers for one serving (or 100 g when the label gives no serving size), read from
    // the catalog rows nutrition-barcode-lookup stored. Null when the label has no calories.
    suspend fun labelPortion(foodId: Long): LabelPortion? {
        val serving = supabase.postgrest.from("food_servings")
            .select(columns = Columns.list("id,food_id,serving_name,grams,ml")) { filter { eq("food_id", foodId) } }
            .decodeList<FoodServingRow>()
            .firstOrNull { (it.grams ?: it.ml ?: 0.0) > 0 }
        val grams = serving?.grams ?: serving?.ml ?: 100.0
        val n = NutritionResolverRepository(supabase).resolveMealItem(foodId, grams, "g").nutrients
        if (n.calories == null) return null
        val nutrients = listOfNotNull(
            n.calories.let { "calories" to it },
            n.proteinG?.let { "protein" to it },
            n.carbsG?.let { "carbs" to it },
            n.fatG?.let { "fat" to it },
            n.fiberG?.let { "fiber" to it },
            n.sugarG?.let { "sugar" to it },
            n.sodiumMg?.let { "sodium" to it },
        ).associate { (k, v) -> k to Math.round(v * 100.0) / 100.0 }
        return LabelPortion(grams, serving?.servingName, nutrients)
    }
}

data class LabelPortion(val grams: Double, val servingName: String?, val nutrients: Map<String, Double>)

private val LABEL_UNITS = mapOf(
    "calories" to "kcal", "protein" to "g", "carbs" to "g", "fat" to "g", "fiber" to "g", "sugar" to "g", "sodium" to "mg",
)

// The label is authoritative for calories and macros. A good Cronometer match for the same product only adds the
// micronutrients the label does not have, at the label's serving weight.
fun labelEnrichment(productName: String, label: LabelPortion, cronometer: CronometerEnrichment?): CronometerEnrichment {
    val serving = label.servingName?.let { "1 x $it" } ?: "${label.grams} g"
    val matched = cronometer?.items?.singleOrNull()?.takeIf { it.matchQuality == "exact" || it.matchQuality == "good" }
    val item = if (matched != null) {
        val merged = matched.nutrientsForPortion.mapValues { (_, v) -> v.jsonPrimitive.double } + label.nutrients
        matched.copy(
            query = productName,
            quantityG = label.grams,
            servingUsed = serving,
            nutrientsForPortion = JsonObject(merged.mapValues { (_, v) -> JsonPrimitive(v) }),
            cronometerSource = "Label + ${matched.cronometerSource ?: "Cronometer"}",
            usedEstimate = false,
            disagreement = false,
        )
    } else {
        CronometerItemResult(
            query = productName,
            cronometerFoodName = productName,
            cronometerSource = "Package label",
            quantityG = label.grams,
            servingUsed = serving,
            matchQuality = "exact",
            nutrientsForPortion = JsonObject(label.nutrients.mapValues { (_, v) -> JsonPrimitive(v) }),
        )
    }
    val base = cronometer ?: CronometerEnrichment(
        calories = null, proteinG = null, carbsG = null, fatG = null, fiberG = null, sugarG = null, sodiumMg = null,
        allNutrients = emptyMap(), nutrientUnits = emptyMap(), items = emptyList(), itemsIncluded = 0,
        itemsSkipped = emptyList(), primarySource = null,
    )
    return base.withItems(listOf(item), LABEL_UNITS).copy(confidence = 0.95, aiEstimateTotal = null)
}
