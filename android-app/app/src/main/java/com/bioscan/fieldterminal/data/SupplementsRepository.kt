package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.NewSupplementIngredientRow
import com.bioscan.fieldterminal.data.model.NewSupplementProductIngredientRow
import com.bioscan.fieldterminal.data.model.NewSupplementProductRow
import com.bioscan.fieldterminal.data.model.NewSupplementRosterRow
import com.bioscan.fieldterminal.data.model.SupplementIngredientRow
import com.bioscan.fieldterminal.data.model.SupplementLogDateRow
import com.bioscan.fieldterminal.data.model.SupplementProductIngredientRow
import com.bioscan.fieldterminal.data.model.SupplementProductIngredientWithKey
import com.bioscan.fieldterminal.data.model.SupplementRow
import com.bioscan.fieldterminal.domain.isSupplementActive
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

data class SupplementsOverview(
    val active: List<SupplementRow>,
    val ended: List<SupplementRow>, // recently-ended only (within the 7-day cutoff) never appears
                                     // here -- it's still counted "active" by isSupplementActive,
                                     // matching the web dashboard's own display-filter semantics.
)

// Supplement Intelligence Phase 1: one ingredient as entered in
// SupplementFormSheet's optional "INGREDIENTS" section -- a plain input
// holder for createProductWithIngredients(), not a domain computation type.
data class IngredientInput(
    val name: String,
    val category: String?,
    val nutrientKey: String?,
    val compoundAmount: Double,
    val compoundUnit: String,
    val elementalAmount: Double?,
    val elementalUnit: String?,
)

@Serializable
private data class IdRow(val id: Long)

class SupplementsRepository(private val supabase: SupabaseClient) {

    suspend fun loadOverview(): SupplementsOverview {
        val rows = supabase.postgrest.from("supplements")
            .select(columns = Columns.list("id,name,dose,time_of_day,status,end_date,ai_note,every_n_days,product_id"))
            .decodeList<SupplementRow>()

        val today = LocalDate.now()
        val (active, ended) = rows.partition { row ->
            isSupplementActive(row.status, row.endDate?.let(LocalDate::parse), today)
        }

        return SupplementsOverview(
            active = active.sortedBy { it.name },
            ended = ended.sortedByDescending { it.endDate },
        )
    }

    // DAV-81: the roster itself, previously only ever readable (loadOverview()
    // above) -- AddEntrySheet.kt's logging flow already assumed this roster
    // existed, with no UI anywhere to actually build or edit it.
    suspend fun addSupplement(name: String, dose: String, timeOfDay: String, startDate: LocalDate, aiNote: String? = null, everyNDays: Int? = null, productId: Long? = null) {
        supabase.postgrest.from("supplements").insert(
            NewSupplementRosterRow(name = name, dose = dose, timeOfDay = timeOfDay, status = "active", startDate = startDate.toString(), aiNote = aiNote, everyNDays = everyNDays, productId = productId)
        )
    }

    suspend fun updateSupplement(id: Long, name: String, dose: String, timeOfDay: String, everyNDays: Int? = null, productId: Long? = null) {
        supabase.postgrest.from("supplements").update(
            buildJsonObject {
                put("name", name)
                put("dose", dose)
                put("time_of_day", timeOfDay)
                put("every_n_days", everyNDays)
                if (productId != null) put("product_id", productId)
            }
        ) { filter { eq("id", id) } }
    }

    // Supplement Intelligence Phase 1: get-or-create each named ingredient
    // (unique per user_id,name -- see migration), then one supplement_products
    // row + one supplement_product_ingredients row per ingredient. Called
    // from SupplementFormSheet's optional ingredients section; returns the
    // new product's id to link onto the roster row via addSupplement/
    // updateSupplement's productId param.
    suspend fun createProductWithIngredients(name: String, servingSize: Double?, servingUnit: String?, ingredients: List<IngredientInput>): Long {
        val product = supabase.postgrest.from("supplement_products")
            .insert(
                NewSupplementProductRow(name = name, servingSize = servingSize, servingUnit = servingUnit, matchConfidence = "manual"),
            ) { select(Columns.list("id")) }
            .decodeSingle<IdRow>()

        val ingredientIds = ingredients.map { ingredient ->
            supabase.postgrest.from("supplement_ingredients")
                .upsert(
                    NewSupplementIngredientRow(name = ingredient.name, category = ingredient.category, nutrientKey = ingredient.nutrientKey),
                ) { onConflict = "user_id,name"; select(Columns.list("id")) }
                .decodeSingle<IdRow>()
                .id
        }

        val productIngredientRows = ingredients.zip(ingredientIds).map { (ingredient, ingredientId) ->
            NewSupplementProductIngredientRow(
                supplementProductId = product.id,
                supplementIngredientId = ingredientId,
                compoundAmount = ingredient.compoundAmount,
                compoundUnit = ingredient.compoundUnit,
                elementalAmount = ingredient.elementalAmount,
                elementalUnit = ingredient.elementalUnit,
            )
        }
        if (productIngredientRows.isNotEmpty()) {
            supabase.postgrest.from("supplement_product_ingredients").insert(productIngredientRows)
        }
        return product.id
    }

    // Kotlin-side join (this project's convention -- no Postgrest relational
    // embedding, see AnalysisModels.kt) between a product's ingredient rows
    // and their canonical ingredients, for domain/SupplementComposition.kt's
    // nutrientContribution().
    suspend fun loadProductComposition(productId: Long): List<SupplementProductIngredientWithKey> {
        val productIngredients = supabase.postgrest.from("supplement_product_ingredients")
            .select(columns = Columns.list("id,supplement_ingredient_id,compound_amount,compound_unit,elemental_amount,elemental_unit")) {
                filter { eq("supplement_product_id", productId) }
            }
            .decodeList<SupplementProductIngredientRow>()
        if (productIngredients.isEmpty()) return emptyList()

        val ingredientIds = productIngredients.map { it.supplementIngredientId }.distinct()
        val ingredientsById = supabase.postgrest.from("supplement_ingredients")
            .select(columns = Columns.list("id,name,category,nutrient_key")) {
                filter { isIn("id", ingredientIds) }
            }
            .decodeList<SupplementIngredientRow>()
            .associateBy { it.id }

        return productIngredients.mapNotNull { pi ->
            val ingredient = ingredientsById[pi.supplementIngredientId] ?: return@mapNotNull null
            SupplementProductIngredientWithKey(
                compoundAmount = pi.compoundAmount,
                compoundUnit = pi.compoundUnit,
                elementalAmount = pi.elementalAmount,
                elementalUnit = pi.elementalUnit,
                nutrientKey = ingredient.nutrientKey,
            )
        }
    }

    // Returns the most recent taken date per supplement id for the given ids,
    // looking back up to 30 days -- enough for any interval supplement.
    suspend fun loadRecentTakenDates(supplementIds: List<Long>): Map<Long, LocalDate> {
        if (supplementIds.isEmpty()) return emptyMap()
        val since = LocalDate.now().minusDays(30).toString()
        return supabase.postgrest.from("supplement_log")
            .select(columns = Columns.list("supplement_id,taken_at")) {
                filter {
                    isIn("supplement_id", supplementIds)
                    gte("taken_at", since)
                }
                order("taken_at", Order.DESCENDING)
                limit(500)
            }
            .decodeList<SupplementLogDateRow>()
            .filter { it.supplementId != null }
            .groupBy { it.supplementId!! }
            .mapValues { (_, rows) -> LocalDate.parse(rows.first().takenAt.take(10)) }
    }

    // A supplement is "ended," never deleted -- its past supplement_log rows
    // (Log tab history) stay meaningful either way, and the ENDED section
    // already exists in the UI to hold it, matching how this table's own
    // status/end_date columns are meant to be used.
    suspend fun endSupplement(id: Long, endDate: LocalDate = LocalDate.now()) {
        supabase.postgrest.from("supplements").update(
            buildJsonObject {
                put("status", "ended")
                put("end_date", endDate.toString())
            }
        ) { filter { eq("id", id) } }
    }
}
