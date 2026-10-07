package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.SuppcoProduct
import com.bioscan.fieldterminal.data.model.flagTexts
import com.bioscan.fieldterminal.domain.PantryAssessment
import com.bioscan.fieldterminal.domain.StockEvent
import com.bioscan.fieldterminal.domain.StockEventKind
import com.bioscan.fieldterminal.domain.TakenServings
import com.bioscan.fieldterminal.domain.assessPantry
import com.bioscan.fieldterminal.domain.isSupplementActive
import com.bioscan.fieldterminal.domain.pickBestListing
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

@Serializable
data class StockEventRow(
    val id: Long,
    @SerialName("supplement_product_id") val productId: Long,
    val kind: String,
    val servings: Double,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("expires_on") val expiresOn: String? = null,
    val price: Double? = null,
    val note: String? = null,
)

@Serializable
private data class NewStockEventRow(
    @SerialName("supplement_product_id") val productId: Long,
    val kind: String,
    val servings: Double,
    @SerialName("expires_on") val expiresOn: String? = null,
    val price: Double? = null,
)

@Serializable
private data class PantrySupplementRow(
    val id: Long,
    @SerialName("time_of_day") val timeOfDay: String,
    val status: String,
    @SerialName("end_date") val endDate: String? = null,
    @SerialName("every_n_days") val everyNDays: Int? = null,
    @SerialName("product_id") val productId: Long? = null,
    @SerialName("servings_per_dose") val servingsPerDose: Double = 1.0,
)

@Serializable
private data class PantryProductRow(
    val id: Long,
    val name: String,
    val brand: String? = null,
    @SerialName("servings_per_container") val servingsPerContainer: Double? = null,
    @SerialName("reorder_lead_days") val reorderLeadDays: Int? = null,
)

@Serializable
private data class PantryLogRow(
    @SerialName("supplement_id") val supplementId: Long? = null,
    @SerialName("product_id") val productId: Long? = null,
    @SerialName("taken_at") val takenAt: String,
    @SerialName("serving_count") val servingCount: Double? = null,
)

@Serializable
private data class PantrySnapshotRow(
    @SerialName("supplement_product_id") val productId: Long,
    @SerialName("retrieved_at") val retrievedAt: String,
    val payload: JsonElement,
)

@Serializable
private data class PantrySourceRow(val id: Long)

// The provider facts shown on the product page: always carried with their source and
// retrieval date.
data class PantryProviderFacts(val product: SuppcoProduct, val retrievedAt: String)

data class PantryItem(
    val productId: Long,
    val name: String,
    val brand: String?,
    val servingsPerContainer: Double?,
    val leadDays: Int?,
    val scheduledPerDay: Double?,
    val events: List<StockEventRow>,
    val assessment: PantryAssessment,
    val facts: PantryProviderFacts?,
)

// DAV-363. Pantry = the user's products on the active roster (plus any with recorded
// stock). Stock is derived (domain/SupplementPantry.kt); this class only gathers
// the inputs and writes stock events.
class PantryRepository(private val supabase: SupabaseClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(today: LocalDate = LocalDate.now()): List<PantryItem> {
        val roster = supabase.postgrest.from("supplements")
            .select(columns = Columns.list("id,time_of_day,status,end_date,every_n_days,product_id,servings_per_dose"))
            .decodeList<PantrySupplementRow>()
        val active = roster.filter { it.productId != null && isSupplementActive(it.status, it.endDate?.let(LocalDate::parse), today) }
        val events = supabase.postgrest.from("supplement_stock_events")
            .select { order("occurred_at", Order.ASCENDING) }
            .decodeList<StockEventRow>()
        val productIds = (active.mapNotNull { it.productId } + events.map { it.productId }).distinct()
        if (productIds.isEmpty()) return emptyList()

        val products = supabase.postgrest.from("supplement_products")
            .select(columns = Columns.list("id,name,brand,servings_per_container,reorder_lead_days")) { filter { isIn("id", productIds) } }
            .decodeList<PantryProductRow>()

        // Consumption only matters for products that have a purchase on record.
        val earliest = events.minOfOrNull { Instant.parse(isoInstant(it.occurredAt)) }
        val productBySupplement = roster.mapNotNull { r -> r.productId?.let { r.id to it } }.toMap()
        val logs = if (earliest == null) emptyList() else supabase.postgrest.from("supplement_log")
            .select(columns = Columns.list("supplement_id,product_id,taken_at,serving_count")) { filter { gte("taken_at", earliest.toString()) } }
            .decodeList<PantryLogRow>()
        val takenByProduct = logs.mapNotNull { l ->
            val pid = l.productId ?: l.supplementId?.let(productBySupplement::get) ?: return@mapNotNull null
            pid to TakenServings(OffsetDateTime.parse(l.takenAt).toInstant(), l.servingCount ?: 1.0)
        }.groupBy({ it.first }, { it.second })

        val facts = loadLatestSuppco(productIds, products.associate { it.id to it.name })

        return products.map { p ->
            val rosterItems = active.filter { it.productId == p.id && !it.timeOfDay.equals("as-needed", ignoreCase = true) }
            val scheduled = if (rosterItems.isEmpty()) null else rosterItems.sumOf { it.servingsPerDose / (it.everyNDays?.takeIf { n -> n > 1 } ?: 1) }
            val rows = events.filter { it.productId == p.id }
            val f = facts[p.id]
            val assessment = assessPantry(
                events = rows.map { StockEvent(kindOf(it.kind), it.servings, OffsetDateTime.parse(it.occurredAt).toInstant(), it.expiresOn?.let(LocalDate::parse)) },
                taken = takenByProduct[p.id].orEmpty(),
                scheduledPerDay = scheduled,
                leadDays = p.reorderLeadDays,
                providerFlags = f?.product?.flagTexts().orEmpty(),
                today = today,
            )
            PantryItem(p.id, p.name, p.brand, p.servingsPerContainer, p.reorderLeadDays, scheduled, rows, assessment, f)
        }.sortedBy { it.name.lowercase() }
    }

    // Latest SuppCo snapshot per product; the stored payload holds every listing for
    // the barcode, so pick the one matching the user's own product name.
    private suspend fun loadLatestSuppco(productIds: List<Long>, names: Map<Long, String>): Map<Long, PantryProviderFacts> {
        val sourceId = supabase.postgrest.from("supplement_sources")
            .select(columns = Columns.list("id")) { filter { eq("name", SUPPCO_SOURCE_NAME) } }
            .decodeList<PantrySourceRow>().firstOrNull()?.id ?: return emptyMap()
        val snaps = supabase.postgrest.from("supplement_source_snapshots")
            .select(columns = Columns.list("supplement_product_id,retrieved_at,payload")) {
                filter { isIn("supplement_product_id", productIds); eq("supplement_source_id", sourceId) }
                order("retrieved_at", Order.DESCENDING)
            }
            .decodeList<PantrySnapshotRow>()
        return snaps.distinctBy { it.productId }.mapNotNull { s ->
            val listings = runCatching { json.decodeFromJsonElement(ListSerializer(SuppcoProduct.serializer()), s.payload) }.getOrNull().orEmpty()
            pickBestListing(listings, names[s.productId].orEmpty(), { it.name }, { it.offMarket })
                ?.let { s.productId to PantryProviderFacts(it, s.retrievedAt) }
        }.toMap()
    }

    suspend fun addEvent(productId: Long, kind: StockEventKind, servings: Double, expiresOn: LocalDate? = null, price: Double? = null) {
        supabase.postgrest.from("supplement_stock_events").insert(
            NewStockEventRow(productId, kind.name.lowercase(), servings, expiresOn?.toString(), price),
        )
    }

    suspend fun deleteEvent(id: Long) {
        supabase.postgrest.from("supplement_stock_events").delete { filter { eq("id", id) } }
    }

    // A first purchase teaches the product its container size; later ones reuse it.
    suspend fun setServingsPerContainer(productId: Long, value: Double) {
        supabase.postgrest.from("supplement_products")
            .update(buildJsonObject { put("servings_per_container", value) }) { filter { eq("id", productId) } }
    }

    private fun kindOf(s: String) = when (s) {
        "purchase" -> StockEventKind.Purchase
        "discard" -> StockEventKind.Discard
        else -> StockEventKind.Adjustment
    }

    private fun isoInstant(s: String) = OffsetDateTime.parse(s).toInstant().toString()
}
