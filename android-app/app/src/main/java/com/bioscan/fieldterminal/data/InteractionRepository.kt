package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.SupplementRow
import com.bioscan.fieldterminal.domain.InteractionRecord
import com.bioscan.fieldterminal.domain.RosterItem
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
private data class InteractionRecordRow(
    val id: Long,
    @SerialName("a_keys") val aKeys: List<String> = emptyList(),
    @SerialName("a_aliases") val aAliases: List<String> = emptyList(),
    @SerialName("b_keys") val bKeys: List<String> = emptyList(),
    @SerialName("b_aliases") val bAliases: List<String> = emptyList(),
    @SerialName("trigger_mode") val triggerMode: String = "both_present",
    @SerialName("prefer_together") val preferTogether: Boolean = false,
    val effect: String = "",
    val advice: String = "",
    @SerialName("spacing_hours") val spacingHours: Double? = null,
    val severity: String = "minor",
    val source: String = "",
    @SerialName("pmid_doi") val pmidDoi: String? = null,
    val caveats: String? = null,
    @SerialName("review_status") val reviewStatus: String = "unreviewed",
)

// Analysis Layer 3 / Phase 6. The curated interaction registry (read-only,
// shared) plus the roster reduced to what the pure detector needs.
class InteractionRepository(private val supabase: SupabaseClient) {
    private companion object {
        const val COLUMNS = "id,a_keys,a_aliases,b_keys,b_aliases,trigger_mode,prefer_together,effect,advice," +
            "spacing_hours,severity,source,pmid_doi,caveats,review_status"
    }

    suspend fun loadRecords(): List<InteractionRecord> =
        supabase.postgrest.from("interaction_records")
            .select(columns = Columns.list(COLUMNS)) { order("id", Order.ASCENDING) }
            .decodeList<InteractionRecordRow>()
            .map {
                InteractionRecord(
                    id = it.id,
                    aKeys = it.aKeys.toSet(), aAliases = it.aAliases.toSet(),
                    bKeys = it.bKeys.toSet(), bAliases = it.bAliases.toSet(),
                    triggerMode = it.triggerMode, preferTogether = it.preferTogether,
                    effect = it.effect, advice = it.advice, spacingHours = it.spacingHours,
                    severity = it.severity, source = it.source, pmidDoi = it.pmidDoi, caveats = it.caveats,
                    reviewed = it.reviewStatus == "reviewed",
                )
            }

    suspend fun rosterItems(active: List<SupplementRow>): List<RosterItem> {
        val keysByProduct = EvidenceRepository(supabase)
            .ingredientKeysByProduct(active.mapNotNull { it.productId }.distinct())
        return active.map { s ->
            RosterItem(s.id, s.name, s.timeOfDay, s.everyNDays, s.productId?.let { keysByProduct[it] }.orEmpty())
        }
    }
}
