package com.bioscan.fieldterminal.data

import android.util.Base64
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
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

// DAV-165, plus label scans. Calls the nutrition-barcode-lookup Edge Function: the app catalog first (a label you
// scanned wins), else Open Food Facts, cached into the catalog server-side (catalog writes stay behind the service-role
// key; foods/food_nutrients are SELECT-only for users). Products no database knows -- most Taiwanese ones -- are added by
// photographing their nutrition label (readLabel), confirming the numbers, and saving them under the barcode (saveLabel).
private const val FUNCTIONS_BASE_URL = "https://ugfrglbcoivkprjqvjzz.supabase.co/functions/v1"

class BarcodeLookupException(message: String) : Exception(message)

@Serializable
data class BarcodeLookupFood(
    val id: Long,
    val name: String,
    @SerialName("name_zh") val nameZh: String? = null,
    val brand: String? = null,
    val barcode: String? = null,
    val category: String? = null,
    @SerialName("beverage_class") val beverageClass: String? = null,
    @SerialName("beverage_subtype") val beverageSubtype: String? = null,
)

// food == null: no database knows the barcode. needsLabel: the product has no calories yet (unknown, or an Open Food
// Facts entry without a nutrition table) -- offer a label scan.
data class BarcodeLookupResult(val food: BarcodeLookupFood?, val needsLabel: Boolean, val source: String?)

@Serializable
data class LabelValues(
    val calories: Double? = null,
    @SerialName("protein_g") val proteinG: Double? = null,
    @SerialName("fat_g") val fatG: Double? = null,
    @SerialName("saturated_fat_g") val saturatedFatG: Double? = null,
    @SerialName("trans_fat_g") val transFatG: Double? = null,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("sugar_g") val sugarG: Double? = null,
    @SerialName("fiber_g") val fiberG: Double? = null,
    @SerialName("sodium_mg") val sodiumMg: Double? = null,
)

// The label as printed (or typed): per serving and/or per 100 g/ml, serving size, servings per package.
@Serializable
data class ScannedLabel(
    @SerialName("product_name") val productName: String? = null,
    @SerialName("serving_size") val servingSize: Double? = null,
    @SerialName("serving_unit") val servingUnit: String? = null,
    @SerialName("servings_per_package") val servingsPerPackage: Double? = null,
    @SerialName("per_serving") val perServing: LabelValues? = null,
    @SerialName("per_100") val per100: LabelValues? = null,
    @SerialName("energy_unit") val energyUnit: String? = null,
)

@Serializable
data class LabelCheck(val field: String, val message: String)

@Serializable
data class NormalizedLabel(
    val per100: Map<String, Double> = emptyMap(),
    val basisUnit: String = "g",
    val serving: FoodMeasure? = null,
    val wholePackage: FoodMeasure? = null,
    val checks: List<LabelCheck> = emptyList(),
)

@Serializable
data class LabelReadResult(
    val label: ScannedLabel,
    val normalized: NormalizedLabel = NormalizedLabel(),
    @SerialName("per_serving") val perServing: Map<String, Double>? = null,
)

@Serializable
private data class BarcodeLookupRequest(val barcode: String)

@Serializable
private data class ReadLabelBody(val images: List<String>, @SerialName("mime_type") val mimeType: String)

@Serializable
private data class ReadLabelRequest(@SerialName("read_label") val readLabel: ReadLabelBody)

@Serializable
private data class SaveLabelBody(val barcode: String, val name: String, val brand: String? = null, val label: ScannedLabel)

@Serializable
private data class SaveLabelRequest(@SerialName("save_label") val saveLabel: SaveLabelBody)

@Serializable
private data class BarcodeLookupResponse(
    val found: Boolean = false,
    val cached: Boolean? = null,
    val food: BarcodeLookupFood? = null,
    val source: String? = null,
    @SerialName("needs_label") val needsLabel: Boolean = false,
    val checks: List<LabelCheck>? = null,
    val error: String? = null,
    val message: String? = null,
)

@Serializable
private data class ErrorResponse(val error: String? = null, val message: String? = null, val checks: List<LabelCheck>? = null)

class NutritionBarcodeLookupRepository(private val supabase: SupabaseClient) {
    companion object {
        private val client = functionsClient(20_000)
        // Reading a label photo is a Gemini vision call (and its fallback tier).
        private val labelClient = functionsClient(75_000)
    }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun lookup(barcode: String): BarcodeLookupResult {
        val parsed = json.decodeFromString<BarcodeLookupResponse>(post(client, json.encodeToString(BarcodeLookupRequest(barcode))))
        if (parsed.error != null) throw BarcodeLookupException(parsed.message ?: parsed.error)
        return BarcodeLookupResult(parsed.food.takeIf { parsed.found }, parsed.needsLabel || !parsed.found, parsed.source)
    }

    // Numbers read from label photos, for the user to confirm; nothing is stored.
    suspend fun readLabel(images: List<ByteArray>, mimeType: String = "image/jpeg"): LabelReadResult {
        require(images.isNotEmpty()) { "At least one photo is required" }
        val body = json.encodeToString(ReadLabelRequest(ReadLabelBody(images.map { Base64.encodeToString(it, Base64.NO_WRAP) }, mimeType)))
        val text = post(labelClient, body)
        val failure = json.decodeFromString<ErrorResponse>(text)
        if (failure.error != null) throw BarcodeLookupException(failure.message ?: failure.error)
        return json.decodeFromString<LabelReadResult>(text)
    }

    // Stores the confirmed label as this barcode's product; the next scan of it is instant.
    suspend fun saveLabel(barcode: String, name: String, brand: String?, label: ScannedLabel): BarcodeLookupResult {
        val parsed = json.decodeFromString<BarcodeLookupResponse>(
            post(client, json.encodeToString(SaveLabelRequest(SaveLabelBody(barcode, name, brand, label)))),
        )
        if (parsed.error != null) {
            val detail = parsed.checks?.joinToString("; ") { it.message }?.takeIf { it.isNotBlank() }
            throw BarcodeLookupException(listOfNotNull(parsed.message ?: parsed.error, detail).joinToString(" "))
        }
        return BarcodeLookupResult(parsed.food, false, parsed.source)
    }

    private suspend fun post(httpClient: io.ktor.client.HttpClient, body: String): String {
        val token = supabase.auth.currentAccessTokenOrNull() ?: throw BarcodeLookupException("Not signed in")
        return httpClient.post("$FUNCTIONS_BASE_URL/nutrition-barcode-lookup") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }.bodyAsText()
    }
}

// A barcode product is a catalog item (label numbers, its servings and package size). A good Cronometer match for the
// same product adds only what the label lacks (vitamins, minerals), per 100 g, so later portion changes stay consistent.
fun withMicronutrients(product: CronometerEnrichment, cronometer: CronometerEnrichment?): CronometerEnrichment {
    val item = product.items.singleOrNull() ?: return product
    val match = cronometer?.items?.singleOrNull()
        ?.takeIf { (it.matchQuality == "exact" || it.matchQuality == "good") && !it.usedEstimate }
        ?: return product
    val extra = match.nutrientsPer100g.filterKeys { it !in item.nutrientsPer100g }
    if (extra.isEmpty() || item.quantityG <= 0) return product
    val merged = item.copy(nutrientsPer100g = JsonObject(item.nutrientsPer100g + extra))
        .withGrams(item.quantityG)
        .copy(servingUsed = item.servingUsed, userEdited = false)
    return product.withItems(listOf(merged), cronometer.nutrientUnits)
}
