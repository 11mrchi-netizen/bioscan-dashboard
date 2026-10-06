package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.DsldLookupResponse
import com.bioscan.fieldterminal.data.model.SuppcoLookupResponse
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

private const val FUNCTIONS_BASE_URL = "https://ugfrglbcoivkprjqvjzz.supabase.co/functions/v1"
private const val CACHE_TTL_MS = 5 * 60 * 1000L // matches SuppCo's own max-age=300

class ProviderResult<T>(val value: T?, val error: String?, val raw: JsonObject? = null)

data class ProductLookup(
    val dsld: ProviderResult<DsldLookupResponse>,
    val suppco: ProviderResult<SuppcoLookupResponse>,
)

// DAV-360. Calls the two read-only provider edge functions (NIH DSLD, SuppCo).
// Both run concurrently and fail independently: one provider being down never
// blocks the other. Lookups happen only on explicit user action; repeat calls for
// the same request inside the providers' 5-minute cache window are answered from
// memory so a double tap does not hit them twice. Successful results only.
class SupplementLookupRepository(private val supabase: SupabaseClient) {
    companion object {
        private val client = HttpClient(Android)
        private val cache = ConcurrentHashMap<String, Pair<Long, ProviderResult<*>>>()
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun lookupBarcode(barcode: String): ProductLookup = both(buildJsonObject { put("barcode", barcode) })

    suspend fun searchByName(query: String): ProductLookup = both(buildJsonObject { put("query", query) })

    suspend fun dsldLabelById(id: Long): ProviderResult<DsldLookupResponse> =
        provider("supplement-dsld-lookup", buildJsonObject { put("labelId", id) }, DsldLookupResponse.serializer())

    private suspend fun both(body: JsonObject): ProductLookup = coroutineScope {
        val dsld = async { provider("supplement-dsld-lookup", body, DsldLookupResponse.serializer()) }
        val suppco = async { provider("supplement-suppco-lookup", body, SuppcoLookupResponse.serializer()) }
        ProductLookup(dsld.await(), suppco.await())
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> provider(function: String, body: JsonObject, serializer: DeserializationStrategy<T>): ProviderResult<T> {
        val key = "$function|$body"
        cache[key]?.let { (at, result) ->
            if (System.currentTimeMillis() - at < CACHE_TTL_MS) return result as ProviderResult<T>
        }
        return try {
            val token = supabase.auth.currentAccessTokenOrNull() ?: throw IllegalStateException("Not signed in")
            val response = client.post("$FUNCTIONS_BASE_URL/$function") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            val root: JsonElement = json.parseToJsonElement(response.bodyAsText())
            val obj = root.jsonObject
            val error = obj["error"]?.let { (obj["message"] ?: it).toString().trim('"') }
            if (error != null) throw IllegalStateException(error)
            ProviderResult(json.decodeFromJsonElement(serializer, obj), null, obj).also {
                cache[key] = System.currentTimeMillis() to it
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProviderResult(null, e.message ?: "Lookup failed")
        }
    }
}
