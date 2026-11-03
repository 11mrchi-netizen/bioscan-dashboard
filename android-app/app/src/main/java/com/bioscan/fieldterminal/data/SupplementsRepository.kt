@file:UseSerializers(com.bioscan.fieldterminal.domain.LocalDateSerializer::class, com.bioscan.fieldterminal.domain.InstantSerializer::class, com.bioscan.fieldterminal.domain.LocalTimeSerializer::class, com.bioscan.fieldterminal.domain.LocalDateTimeSerializer::class, com.bioscan.fieldterminal.domain.ZonedDateTimeSerializer::class, com.bioscan.fieldterminal.domain.OffsetDateTimeSerializer::class, com.bioscan.fieldterminal.domain.DurationSerializer::class, com.bioscan.fieldterminal.domain.YearMonthSerializer::class, com.bioscan.fieldterminal.domain.ZoneIdSerializer::class)
package com.bioscan.fieldterminal.data

import kotlinx.serialization.UseSerializers
import com.bioscan.fieldterminal.data.model.NewSupplementIngredientRow
import com.bioscan.fieldterminal.data.model.NewSupplementProductIngredientRow
import com.bioscan.fieldterminal.data.model.NewSupplementProductRow
import com.bioscan.fieldterminal.data.model.NewSupplementRosterRow
import com.bioscan.fieldterminal.data.model.SupplementIngredientRow
import com.bioscan.fieldterminal.data.model.SupplementLogDateRow
import com.bioscan.fieldterminal.data.model.SupplementProductIngredientRow
import com.bioscan.fieldterminal.data.model.SupplementProductIngredientFull
import com.bioscan.fieldterminal.data.model.SupplementProductIngredientWithKey
import com.bioscan.fieldterminal.data.model.SupplementRow
import com.bioscan.fieldterminal.domain.isSupplementActive
import com.bioscan.fieldterminal.domain.doseTextOn
import com.bioscan.fieldterminal.domain.legacyColumns
import com.bioscan.fieldterminal.domain.spec
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

@Serializable
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

/** What the regimen form saves. [kind], [form], [route] and [unit] are the stored keys (domain/Regimen.kt). */
data class RegimenDraft(
    val name: String,
    val kind: String,
    val form: String?,
    val route: String?,
    val amount: Double,
    val unit: String,
    val slots: List<String>,
    val schedule: com.bioscan.fieldterminal.domain.ScheduleJson,
    val steps: com.bioscan.fieldterminal.domain.DoseStepsJson?,
)

@Serializable
data class SupplementChangeRow(
    val id: Long,
    @SerialName("supplement_id") val supplementId: Long,
    @SerialName("changed_at") val changedAt: String,
    val changes: List<String>,
    val before: kotlinx.serialization.json.JsonObject? = null,
    val after: kotlinx.serialization.json.JsonObject,
)

/** [lastTaken]: the newest local day each item was taken; [takesToday]: doses logged today per item. */
data class RecentTakes(val lastTaken: Map<Long, LocalDate>, val takesToday: Map<Long, Int>)

class SupplementsRepository(private val supabase: SupabaseClient) {

    // Kept on disk with the read cache (data/ReadCache.kt): the page opens from the last copy while this loads.
    suspend fun loadOverview(): SupplementsOverview =
        ReadCache.get("supplements.overview.v2.${java.time.LocalDate.now()}", tables = null, serializer = SupplementsOverview.serializer()) { fetchLoadOverview() }

    private suspend fun fetchLoadOverview(): SupplementsOverview {
        val rows = supabase.postgrest.from("supplements")
            .select(columns = Columns.raw(com.bioscan.fieldterminal.data.model.SUPPLEMENT_ROW_COLUMNS))
            .decodeList<SupplementRow>()

        val today = LocalDate.now()
        advanceDoseSteps(rows.filter { it.status == "active" }, today)
        val (active, ended) = rows.partition { row ->
            isSupplementActive(row.status, row.endDate?.let(LocalDate::parse), today)
        }

        return SupplementsOverview(
            active = active.sortedBy { it.name },
            ended = ended.sortedByDescending { it.endDate },
        )
    }

    // DAV-81 / regimen model: one save for add and edit. The structured columns are the truth; the old ones (dose text,
    // time_of_day, every_n_days) are written from them so the web dashboard, the log agent and the analysis functions,
    // which read only those, keep working. The history trigger records what changed.
    suspend fun saveRegimen(id: Long?, draft: RegimenDraft, productId: Long? = null, aiNote: String? = null) {
        val today = LocalDate.now()
        val spec = com.bioscan.fieldterminal.domain.regimenSpec(
            timeOfDay = draft.slots.firstOrNull() ?: "as-needed", everyNDays = draft.schedule.n, dose = "",
            kind = draft.kind, doseAmount = draft.amount, doseUnit = draft.unit, slots = draft.slots,
            schedule = draft.schedule, doseSteps = draft.steps,
        )
        val legacy = spec.legacyColumns(today)
        val json = kotlinx.serialization.json.Json { encodeDefaults = false; explicitNulls = true }
        val body = buildJsonObject {
            put("name", draft.name)
            put("kind", draft.kind)
            put("form", draft.form)
            put("route", draft.route)
            put("dose_amount", draft.amount)
            put("dose_unit", draft.unit)
            put("slots", kotlinx.serialization.json.JsonArray(draft.slots.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("schedule", json.encodeToJsonElement(com.bioscan.fieldterminal.domain.ScheduleJson.serializer(), draft.schedule))
            put("dose_steps", draft.steps?.let { json.encodeToJsonElement(com.bioscan.fieldterminal.domain.DoseStepsJson.serializer(), it) } ?: kotlinx.serialization.json.JsonNull)
            put("dose", legacy.dose)
            put("time_of_day", legacy.timeOfDay)
            put("every_n_days", legacy.everyNDays)
            if (productId != null) put("product_id", productId)
            if (id == null) {
                put("status", "active")
                put("start_date", today.toString())
                if (aiNote != null) put("ai_note", aiNote)
            }
        }
        if (id == null) supabase.postgrest.from("supplements").insert(body)
        else supabase.postgrest.from("supplements").update(body) { filter { eq("id", id) } }
    }

    /** Takes an ended item up again from today; the history trigger records the restart. */
    suspend fun restartSupplement(id: Long, today: LocalDate = LocalDate.now()) {
        supabase.postgrest.from("supplements").update(
            buildJsonObject {
                put("status", "active")
                put("start_date", today.toString())
                put("end_date", kotlinx.serialization.json.JsonNull)
            }
        ) { filter { eq("id", id) } }
    }

    /** Every recorded start, stop, restart and dose or schedule change, newest first (all items, or one). */
    suspend fun loadChanges(supplementId: Long? = null): List<SupplementChangeRow> =
        ReadCache.get("supplements.changes.${supplementId ?: "all"}", tables = setOf("supplements", "supplement_changes")) {
            supabase.postgrest.from("supplement_changes")
                .select(columns = Columns.list("id,supplement_id,changed_at,changes,before,after")) {
                    if (supplementId != null) filter { eq("supplement_id", supplementId) }
                    order("changed_at", Order.DESCENDING)
                    limit(500)
                }
                .decodeList<SupplementChangeRow>()
        }

    /**
     * Items on planned dose steps whose stored dose text is behind today's step: the text is moved on (the old-column
     * readers see the dose actually taken), which the trigger records as the day the dose changed.
     */
    private suspend fun advanceDoseSteps(rows: List<SupplementRow>, today: LocalDate) {
        for (row in rows) {
            val spec = row.spec()
            if (spec.steps == null) continue
            val now = spec.doseTextOn(today)
            if (now == row.dose) continue
            runCatching {
                supabase.postgrest.from("supplements").update(buildJsonObject { put("dose", now) }) { filter { eq("id", row.id) } }
            }
        }
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

    // Same join as loadProductComposition but includes ingredient name -- used
    // to pre-populate SupplementFormSheet when editing a supplement with an
    // existing product.
    suspend fun loadProductIngredientsFull(productId: Long): List<SupplementProductIngredientFull> {
        val productIngredients = supabase.postgrest.from("supplement_product_ingredients")
            .select(columns = Columns.list("id,supplement_ingredient_id,compound_amount,compound_unit,elemental_amount,elemental_unit")) {
                filter { eq("supplement_product_id", productId) }
            }
            .decodeList<SupplementProductIngredientRow>()
        if (productIngredients.isEmpty()) return emptyList()

        val ingredientIds = productIngredients.map { it.supplementIngredientId }.distinct()
        val ingredientsById = supabase.postgrest.from("supplement_ingredients")
            .select(columns = Columns.list("id,name,nutrient_key")) {
                filter { isIn("id", ingredientIds) }
            }
            .decodeList<SupplementIngredientRow>()
            .associateBy { it.id }

        return productIngredients.mapNotNull { pi ->
            val ingredient = ingredientsById[pi.supplementIngredientId] ?: return@mapNotNull null
            SupplementProductIngredientFull(
                name = ingredient.name,
                nutrientKey = ingredient.nutrientKey,
                compoundAmount = pi.compoundAmount,
                compoundUnit = pi.compoundUnit,
                elementalAmount = pi.elementalAmount,
                elementalUnit = pi.elementalUnit,
            )
        }
    }

    // Delete-then-reinsert ingredient rows for an existing product -- used
    // when the user edits ingredient composition after initial save.
    suspend fun replaceProductIngredients(productId: Long, ingredients: List<IngredientInput>) {
        supabase.postgrest.from("supplement_product_ingredients")
            .delete { filter { eq("supplement_product_id", productId) } }

        val ingredientIds = ingredients.map { ingredient ->
            supabase.postgrest.from("supplement_ingredients")
                .upsert(
                    NewSupplementIngredientRow(name = ingredient.name, category = ingredient.category, nutrientKey = ingredient.nutrientKey),
                ) { onConflict = "user_id,name"; select(Columns.list("id")) }
                .decodeSingle<IdRow>()
                .id
        }

        val rows = ingredients.zip(ingredientIds).map { (ingredient, ingredientId) ->
            NewSupplementProductIngredientRow(
                supplementProductId = productId,
                supplementIngredientId = ingredientId,
                compoundAmount = ingredient.compoundAmount,
                compoundUnit = ingredient.compoundUnit,
                elementalAmount = ingredient.elementalAmount,
                elementalUnit = ingredient.elementalUnit,
            )
        }
        if (rows.isNotEmpty()) {
            supabase.postgrest.from("supplement_product_ingredients").insert(rows)
        }
    }

    // The most recent taken day per supplement id, looking back 30 days (enough for any interval item). Days are the phone's
    // local days: the stored timestamp comes back in UTC, so its first ten characters would put a dose taken before 08:00
    // in Taipei on the day before.
    suspend fun loadRecentTakenDates(supplementIds: List<Long>): Map<Long, LocalDate> = loadRecentTakes(supplementIds).lastTaken

    /** The last taken day per item and how many doses each has logged today (a twice-a-day item's slots). */
    suspend fun loadRecentTakes(supplementIds: List<Long>, today: LocalDate = LocalDate.now()): RecentTakes {
        if (supplementIds.isEmpty()) return RecentTakes(emptyMap(), emptyMap())
        val since = today.minusDays(31).atStartOfDay(java.time.ZoneId.systemDefault()).toOffsetDateTime().toString()
        val rows = supabase.postgrest.from("supplement_log")
            .select(columns = Columns.list("supplement_id,taken_at")) {
                filter {
                    isIn("supplement_id", supplementIds)
                    gte("taken_at", since)
                }
                order("taken_at", Order.DESCENDING)
                limit(1000)
            }
            .decodeList<SupplementLogDateRow>()
            .mapNotNull { r -> r.supplementId?.let { id -> localDay(r.takenAt)?.let { id to it } } }
        return RecentTakes(
            lastTaken = rows.groupBy({ it.first }, { it.second }).mapValues { (_, days) -> days.max() },
            takesToday = rows.filter { it.second == today }.groupingBy { it.first }.eachCount(),
        )
    }

    private fun localDay(ts: String): LocalDate? = runCatching {
        java.time.OffsetDateTime.parse(ts).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate()
    }.getOrElse { runCatching { LocalDate.parse(ts.take(10)) }.getOrNull() }

    // DAV-362. Active roster items whose product carries this (normalized) barcode.
    suspend fun activeSupplementsForBarcode(barcode: String): List<SupplementRow> {
        val productIds = supabase.postgrest.from("supplement_products")
            .select(columns = Columns.list("id")) { filter { eq("barcode", barcode) } }
            .decodeList<IdRow>().map { it.id }.toSet()
        if (productIds.isEmpty()) return emptyList()
        return loadOverview().active.filter { it.productId in productIds }
    }

    // DAV-362. Remembers that this roster item is the bottle with `barcode`. Only the
    // barcode is written: no provider data is applied here (that is the reviewed
    // verify-and-apply flow). A roster item without a product gets a minimal manual one.
    suspend fun linkBarcode(supplement: SupplementRow, barcode: String) {
        val productId = supplement.productId
        if (productId != null) {
            supabase.postgrest.from("supplement_products")
                .update(buildJsonObject { put("barcode", barcode) }) { filter { eq("id", productId) } }
            return
        }
        val created = supabase.postgrest.from("supplement_products")
            .insert(NewSupplementProductRow(name = supplement.name, matchConfidence = "manual", barcode = barcode)) { select(Columns.list("id")) }
            .decodeSingle<IdRow>()
        supabase.postgrest.from("supplements")
            .update(buildJsonObject { put("product_id", created.id) }) { filter { eq("id", supplement.id) } }
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
