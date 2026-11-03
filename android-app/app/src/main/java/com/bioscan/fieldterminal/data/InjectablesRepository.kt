package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.Vial
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

// Injectables (domain/Injectables.kt; migration 20261118_injectables.sql): where recent doses went, and the open vial.

@Serializable
data class VialRow(
    val id: Long,
    @SerialName("supplement_id") val supplementId: Long,
    val amount: Double,
    @SerialName("amount_unit") val amountUnit: String,
    @SerialName("diluent_ml") val diluentMl: Double? = null,
    @SerialName("volume_ml") val volumeMl: Double? = null,
    @SerialName("opened_on") val openedOn: String,
    @SerialName("discard_after_days") val discardAfterDays: Int? = null,
    val spares: Int = 0,
    @SerialName("finished_on") val finishedOn: String? = null,
) {
    fun toVial() = Vial(amount, amountUnit, diluentMl, volumeMl, LocalDate.parse(openedOn), discardAfterDays, spares)
}

@Serializable
private data class SiteRow(
    @SerialName("supplement_id") val supplementId: Long? = null,
    @SerialName("injection_site") val injectionSite: String? = null,
    @SerialName("taken_at") val takenAt: String,
)

private const val VIAL_COLUMNS = "id,supplement_id,amount,amount_unit,diluent_ml,volume_ml,opened_on,discard_after_days,spares,finished_on"

class InjectablesRepository(private val supabase: SupabaseClient) {

    /** Per item, the sites of its doses in the last [days] days, newest first. */
    suspend fun siteHistory(ids: List<Long>, days: Long = 60): Map<Long, List<Pair<String, Instant>>> {
        if (ids.isEmpty()) return emptyMap()
        val since = LocalDate.now().minusDays(days).atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime().toString()
        return supabase.postgrest.from("supplement_log").select(Columns.list("supplement_id,injection_site,taken_at")) {
            filter { isIn("supplement_id", ids); gte("taken_at", since) }
            order("taken_at", Order.DESCENDING)
            limit(1000)
        }.decodeList<SiteRow>()
            .mapNotNull { r ->
                val id = r.supplementId ?: return@mapNotNull null
                val site = r.injectionSite ?: return@mapNotNull null
                val at = runCatching { OffsetDateTime.parse(r.takenAt).toInstant() }.getOrNull() ?: return@mapNotNull null
                id to (site to at)
            }
            .groupBy({ it.first }, { it.second })
    }

    suspend fun openVial(supplementId: Long): VialRow? =
        supabase.postgrest.from("regimen_vials").select(Columns.raw(VIAL_COLUMNS)) {
            filter { eq("supplement_id", supplementId); exact("finished_on", null) }
        }.decodeList<VialRow>().firstOrNull()

    /** Dose amounts logged since [openedOn] (local day, inclusive), in the logs' own unit. */
    suspend fun usedSince(supplementId: Long, openedOn: LocalDate): Double =
        SupplementsRepository(supabase).loadDoses(supplementId, openedOn.atStartOfDay(ZoneId.systemDefault()).toInstant())
            .sumOf { it.doseValue ?: 0.0 }

    /** Edits the open vial, or opens the first one. */
    suspend fun saveVial(supplementId: Long, existingId: Long?, vial: Vial) {
        val body = buildJsonObject {
            put("supplement_id", supplementId)
            put("amount", vial.amount)
            put("amount_unit", vial.amountUnit)
            put("diluent_ml", vial.diluentMl)
            put("volume_ml", vial.volumeMl)
            put("opened_on", vial.openedOn.toString())
            put("discard_after_days", vial.discardAfterDays)
            put("spares", vial.spares)
        }
        if (existingId == null) supabase.postgrest.from("regimen_vials").insert(body)
        else supabase.postgrest.from("regimen_vials").update(body) { filter { eq("id", existingId) } }
    }

    /** Finishes the open vial today and opens the next like it, one spare fewer. */
    suspend fun startNextVial(current: VialRow, today: LocalDate = LocalDate.now()) {
        supabase.postgrest.from("regimen_vials").update(buildJsonObject { put("finished_on", today.toString()) }) { filter { eq("id", current.id) } }
        val v = current.toVial()
        saveVial(current.supplementId, null, v.copy(openedOn = today, spares = (v.spares - 1).coerceAtLeast(0)))
    }
}
