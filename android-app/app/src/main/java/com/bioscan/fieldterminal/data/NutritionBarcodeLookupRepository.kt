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
    companion object { private val client = HttpClient(Android) }
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
}
