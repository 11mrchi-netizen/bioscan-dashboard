package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.DsldLabel
import com.bioscan.fieldterminal.data.model.DsldLookupResponse
import com.bioscan.fieldterminal.domain.DsldComparison
import com.bioscan.fieldterminal.domain.DsldIngredient
import com.bioscan.fieldterminal.domain.ProductIngredientView
import com.bioscan.fieldterminal.domain.compareToDsld
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private const val FUNCTIONS_BASE_URL = "https://ugfrglbcoivkprjqvjzz.supabase.co/functions/v1"
private const val DSLD_SOURCE_NAME = "nih_dsld"

class DsldVerificationException(message: String) : Exception(message)

@Serializable
private data class SourceIdRow(val id: Long)

@Serializable
private data class NewSourceRow(val name: String, val url: String)

@Serializable
private data class NewSnapshotRow(
    @SerialName("supplement_product_id") val supplementProductId: Long,
    @SerialName("supplement_source_id") val supplementSourceId: Long,
    @SerialName("source_product_id") val sourceProductId: String?,
    val barcode: String,
    @SerialName("retrieved_at") val retrievedAt: String,
    @SerialName("source_version") val sourceVersion: String?,
    @SerialName("payload_hash") val payloadHash: String,
    val payload: JsonElement,
    @SerialName("match_status") val matchStatus: String,
    val conflicts: JsonElement,
)

data class DsldVerification(
    val status: String, // matched | conflict | not_found
    val label: DsldLabel?,
    val comparison: DsldComparison?,
)

// DAV-359. Verifies one of the user's own products against NIH DSLD through the
// supplement-dsld-lookup edge function and stores a per-user snapshot with its
// provenance (source, DSLD id, retrieval time, version, payload hash). The
// user's product and ingredient rows are never modified -- conflicting provider
// data is recorded, not applied.
class SupplementSourceRepository(private val supabase: SupabaseClient) {
    companion object { private val client = HttpClient(Android) }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun verifyAgainstDsld(productId: Long, barcode: String): DsldVerification {
        val token = supabase.auth.currentAccessTokenOrNull() ?: throw DsldVerificationException("Not signed in")

        val response = client.post("$FUNCTIONS_BASE_URL/supplement-dsld-lookup") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("barcode", barcode) }.toString())
        }
        val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val parsed = json.decodeFromJsonElement(DsldLookupResponse.serializer(), root)
        if (parsed.error != null) throw DsldVerificationException(parsed.message ?: parsed.error)

        val label = parsed.labels.firstOrNull()
        val comparison = label?.let { l ->
            val product = SupplementsRepository(supabase).loadProductIngredientsFull(productId).map {
                ProductIngredientView(it.name, it.compoundAmount, it.compoundUnit, it.elementalAmount, it.elementalUnit)
            }
            compareToDsld(product, l.ingredients.map { DsldIngredient(it.name, it.category, it.amount, it.unit) })
        }
        val status = when {
            label == null -> "not_found"
            comparison!!.conflicts.isNotEmpty() -> "conflict"
            else -> "matched"
        }

        val sourceId = supabase.postgrest.from("supplement_sources")
            .upsert(NewSourceRow(DSLD_SOURCE_NAME, parsed.source.url ?: "https://dsld.od.nih.gov/")) {
                onConflict = "user_id,name"
                select(Columns.list("id"))
            }
            .decodeSingle<SourceIdRow>().id

        supabase.postgrest.from("supplement_source_snapshots").upsert(
            NewSnapshotRow(
                supplementProductId = productId,
                supplementSourceId = sourceId,
                sourceProductId = label?.dsldId?.toString(),
                barcode = barcode,
                retrievedAt = parsed.retrievedAt ?: java.time.Instant.now().toString(),
                sourceVersion = label?.productVersionCode ?: parsed.source.apiVersion,
                payloadHash = parsed.payloadHash ?: "",
                payload = root["labels"] ?: JsonArray(emptyList()),
                matchStatus = status,
                conflicts = buildJsonArray {
                    comparison?.conflicts?.forEach { c ->
                        add(buildJsonObject {
                            put("kind", c.kind.name)
                            put("ingredient", c.ingredient)
                            put("product", c.productText)
                            put("dsld", c.dsldText)
                        })
                    }
                },
            ),
        ) {
            onConflict = "user_id,supplement_product_id,supplement_source_id,payload_hash"
            ignoreDuplicates = true
        }

        return DsldVerification(status, label, comparison)
    }
}
