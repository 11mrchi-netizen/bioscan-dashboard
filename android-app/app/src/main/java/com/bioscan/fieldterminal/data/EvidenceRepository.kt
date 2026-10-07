package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.SupplementRow
import com.bioscan.fieldterminal.domain.EvidenceClaim
import com.bioscan.fieldterminal.domain.expectedOutcomeText
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
private data class ProductIngredientLink(
    @SerialName("supplement_product_id") val productId: Long,
    @SerialName("supplement_ingredient_id") val ingredientId: Long,
)

@Serializable
private data class IngredientKeyRow(
    val id: Long,
    @SerialName("canonical_key") val canonicalKey: String? = null,
)

@Serializable
private data class EvidenceClaimRow(
    @SerialName("subject_key") val subjectKey: String,
    @SerialName("claim_text") val claimText: String,
    @SerialName("review_status") val reviewStatus: String = "unreviewed",
)

// Outcome claims come from the shared evidence_records registry, matched to a
// supplement through its product's ingredients' canonical_key (Kotlin-side
// joins, this project's convention -- no Postgrest embedding).
class EvidenceRepository(private val supabase: SupabaseClient) {

    // supplement id -> expected-outcome text. A supplement with no linked
    // product, no keyed ingredient or no matching evidence is simply absent.
    suspend fun outcomeTextBySupplement(supplements: List<SupplementRow>): Map<Long, String> {
        val productIds = supplements.mapNotNull { it.productId }.distinct()
        if (productIds.isEmpty()) return emptyMap()

        val links = supabase.postgrest.from("supplement_product_ingredients")
            .select(columns = Columns.list("supplement_product_id,supplement_ingredient_id")) {
                filter { isIn("supplement_product_id", productIds) }
            }
            .decodeList<ProductIngredientLink>()
        if (links.isEmpty()) return emptyMap()

        val keyByIngredient = supabase.postgrest.from("supplement_ingredients")
            .select(columns = Columns.list("id,canonical_key")) {
                filter { isIn("id", links.map { it.ingredientId }.distinct()) }
            }
            .decodeList<IngredientKeyRow>()
            .mapNotNull { row -> row.canonicalKey?.let { row.id to it } }
            .toMap()

        val keysByProduct = links.groupBy { it.productId }
            .mapValues { (_, rows) -> rows.mapNotNull { keyByIngredient[it.ingredientId] }.toSet() }
        val allKeys = keysByProduct.values.flatten().toSet()
        if (allKeys.isEmpty()) return emptyMap()

        val claims = supabase.postgrest.from("evidence_records")
            .select(columns = Columns.list("subject_key,claim_text,review_status")) {
                filter {
                    eq("subject_type", "ingredient")
                    isIn("subject_key", allKeys.toList())
                }
                order("id", Order.ASCENDING)
            }
            .decodeList<EvidenceClaimRow>()
            .map { EvidenceClaim(it.subjectKey, it.claimText, reviewed = it.reviewStatus == "reviewed") }

        return supplements.mapNotNull { s ->
            val keys = s.productId?.let { keysByProduct[it] } ?: return@mapNotNull null
            expectedOutcomeText(keys, claims).takeIf { it.isNotEmpty() }?.let { s.id to it }
        }.toMap()
    }
}
