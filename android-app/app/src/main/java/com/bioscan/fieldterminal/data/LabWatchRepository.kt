package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.LabReading
import com.bioscan.fieldterminal.domain.LabWatch
import com.bioscan.fieldterminal.domain.LabWatchRule
import com.bioscan.fieldterminal.domain.WatchItem
import com.bioscan.fieldterminal.domain.matchLabWatch
import com.bioscan.fieldterminal.domain.spec
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

// Lab watch (domain/LabWatch.kt): the shared rules with the owner's verdicts applied, the person's lab results across all
// draws, and the active roster with its ingredient keys.

@Serializable
private data class LabWatchRuleRow(
    val id: Long,
    val version: Int? = null,
    @SerialName("substance_keys") val substanceKeys: List<String> = emptyList(),
    @SerialName("substance_aliases") val substanceAliases: List<String> = emptyList(),
    @SerialName("min_daily_amount") val minDailyAmount: Double? = null,
    @SerialName("amount_unit") val amountUnit: String? = null,
    @SerialName("marker_label") val markerLabel: String,
    @SerialName("marker_aliases") val markerAliases: List<String>,
    val reason: String,
    val advice: String,
    val source: String,
    @SerialName("pmid_doi") val pmidDoi: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    val caveats: String? = null,
    @SerialName("review_status") val reviewStatus: String = "unreviewed",
)

@Serializable
private data class WatchResultRow(
    @SerialName("draw_id") val drawId: Long,
    @SerialName("marker_name") val markerName: String,
    val value: Double? = null,
    @SerialName("value_text") val valueText: String? = null,
    val unit: String? = null,
    val flag: String? = null,
)

@Serializable
private data class WatchDrawRow(val id: Long, @SerialName("draw_date") val drawDate: String)

class LabWatchRepository(private val supabase: SupabaseClient) {

    suspend fun loadRules(): List<LabWatchRule> {
        val reviews = runCatching { RegistryReviewRepository(supabase).loadReviews() }.getOrDefault(emptyMap())
        return ReadCache.kept("labwatch.rules", tables = setOf("registry_reviews")) {
            supabase.postgrest.from("lab_watch_rules").select(
                Columns.list("id,version,substance_keys,substance_aliases,min_daily_amount,amount_unit,marker_label,marker_aliases,reason,advice,source,pmid_doi,source_url,caveats,review_status"),
            ) { order("id", Order.ASCENDING) }.decodeList<LabWatchRuleRow>()
        }.map { r ->
            val review = reviews["lab_watch" to r.id]
            val state = reviewState(r.reviewStatus, r.version, review)
            LabWatchRule(
                r.id, r.version, r.substanceKeys.toSet(), r.substanceAliases, r.minDailyAmount, r.amountUnit, r.markerLabel,
                r.markerAliases, r.reason, r.advice, r.source, r.pmidDoi, r.sourceUrl, r.caveats,
                reviewed = state == ReviewState.CONFIRMED,
                disputedNote = if (state == ReviewState.DISPUTED) review?.note ?: "" else null,
            )
        }
    }

    suspend fun loadReadings(): List<LabReading> =
        ReadCache.kept("labwatch.readings", tables = setOf("lab_draws", "lab_results")) {
            val draws = supabase.postgrest.from("lab_draws").select(Columns.list("id,draw_date")).decodeList<WatchDrawRow>()
                .associate { it.id to it.drawDate }
            supabase.postgrest.from("lab_results").select(Columns.list("draw_id,marker_name,value,value_text,unit,flag"))
                .decodeList<WatchResultRow>()
                .mapNotNull { r ->
                    val day = draws[r.drawId]?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: return@mapNotNull null
                    LabReading(r.markerName, r.value, r.valueText, r.unit, r.flag, day)
                }
        }

    /** Every rule that applies to an active item, with the latest results of its markers. */
    suspend fun watches(today: LocalDate = LocalDate.now()): List<LabWatch> {
        val active = SupplementsRepository(supabase).loadOverview().active.filter { it.status == "active" }
        val roster = runCatching { InteractionRepository(supabase).rosterItems(active) }.getOrDefault(emptyList()).associateBy { it.id }
        val doses = runCatching { SupplementsRepository(supabase).loadDoseIngredients(active) }.getOrDefault(emptyMap())
        val items = active.map { WatchItem(it.id, it.name, roster[it.id]?.ingredientKeys.orEmpty(), it.spec(), doses[it.id].orEmpty()) }
        return matchLabWatch(loadRules(), items, loadReadings(), today)
    }
}
