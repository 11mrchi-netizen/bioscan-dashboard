package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.DsldLabel
import com.bioscan.fieldterminal.data.model.SuppcoProduct
import com.bioscan.fieldterminal.domain.DsldComparison
import com.bioscan.fieldterminal.domain.DsldIngredient
import com.bioscan.fieldterminal.domain.ProductIngredientView
import com.bioscan.fieldterminal.domain.compareToDsld
import com.bioscan.fieldterminal.domain.pickBestListing
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val DSLD_SOURCE_NAME = "nih_dsld"
private const val SUPPCO_SOURCE_NAME = "suppco"

@Serializable
private data class SourceIdRow(val id: Long)

@Serializable
private data class NewSourceRow(val name: String, val url: String)

@Serializable
private data class ProductNameRow(val name: String)

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
    val retrievedAt: String?,
)

data class SuppcoVerification(
    val status: String, // matched | conflict | not_found
    val product: SuppcoProduct?,
    // Listings sharing this UPC; product is the one best matching the user's own name.
    val listings: Int,
    val comparison: DsldComparison?,
    val retrievedAt: String?,
)

// One provider failing is reported next to the other provider's result, never
// instead of it.
data class ProductVerification(
    val dsld: DsldVerification?,
    val dsldError: String?,
    val suppco: SuppcoVerification?,
    val suppcoError: String?,
)

// DAV-359 / DAV-360. Verifies one of the user's own products against NIH DSLD and
// SuppCo by barcode and stores a per-user snapshot for each provider with its
// provenance (source, provider id, retrieval time, version, payload hash,
// match status, conflicts). The user's product and ingredient rows are never
// modified here -- conflicting provider data is recorded, not applied.
class SupplementSourceRepository(private val supabase: SupabaseClient) {

    suspend fun verifyProduct(productId: Long, barcode: String): ProductVerification {
        val lookup = SupplementLookupRepository(supabase).lookupBarcode(barcode)
        val productName = supabase.postgrest.from("supplement_products")
            .select(columns = Columns.list("name")) { filter { eq("id", productId) } }
            .decodeSingle<ProductNameRow>().name
        val product = SupplementsRepository(supabase).loadProductIngredientsFull(productId).map {
            ProductIngredientView(it.name, it.compoundAmount, it.compoundUnit, it.elementalAmount, it.elementalUnit)
        }

        val dsld = lookup.dsld.value?.let { r ->
            // The function already ranks same-UPC labels current-first.
            val label = r.labels.firstOrNull()
            val comparison = label?.let { l ->
                compareToDsld(product, l.ingredients.map { DsldIngredient(it.name, it.category, it.amount, it.unit) })
            }
            val status = statusOf(label != null, comparison)
            saveSnapshot(
                productId, DSLD_SOURCE_NAME, r.source.url ?: "https://dsld.od.nih.gov/", label?.dsldId?.toString(), barcode,
                r.retrievedAt, label?.productVersionCode ?: r.source.apiVersion, r.payloadHash,
                lookup.dsld.raw?.get("labels"), status, comparison,
            )
            DsldVerification(status, label, comparison, r.retrievedAt)
        }

        val suppco = lookup.suppco.value?.let { r ->
            val listing = pickBestListing(r.products, productName, { it.name }, { it.offMarket })
            val comparison = listing?.let { p ->
                compareToDsld(product, p.ingredients.map { DsldIngredient(it.name, it.category, it.amount, it.unit) })
            }
            val status = statusOf(listing != null, comparison)
            saveSnapshot(
                productId, SUPPCO_SOURCE_NAME, r.source.url ?: "https://supp.co", listing?.id, barcode,
                r.retrievedAt, r.source.apiVersion, r.payloadHash,
                lookup.suppco.raw?.get("products"), status, comparison,
            )
            SuppcoVerification(status, listing, r.products.size, comparison, r.retrievedAt)
        }

        return ProductVerification(dsld, lookup.dsld.error, suppco, lookup.suppco.error)
    }

    private fun statusOf(found: Boolean, comparison: DsldComparison?): String = when {
        !found -> "not_found"
        comparison != null && comparison.conflicts.isNotEmpty() -> "conflict"
        else -> "matched"
    }

    private suspend fun saveSnapshot(
        productId: Long,
        sourceName: String,
        sourceUrl: String,
        sourceProductId: String?,
        barcode: String,
        retrievedAt: String?,
        sourceVersion: String?,
        payloadHash: String?,
        payload: JsonElement?,
        status: String,
        comparison: DsldComparison?,
    ) {
        val sourceId = supabase.postgrest.from("supplement_sources")
            .upsert(NewSourceRow(sourceName, sourceUrl)) {
                onConflict = "user_id,name"
                select(Columns.list("id"))
            }
            .decodeSingle<SourceIdRow>().id

        supabase.postgrest.from("supplement_source_snapshots").upsert(
            NewSnapshotRow(
                supplementProductId = productId,
                supplementSourceId = sourceId,
                sourceProductId = sourceProductId,
                barcode = barcode,
                retrievedAt = retrievedAt ?: java.time.Instant.now().toString(),
                sourceVersion = sourceVersion,
                payloadHash = payloadHash ?: "",
                payload = payload ?: JsonArray(emptyList()),
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
    }
}
