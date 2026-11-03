package com.bioscan.fieldterminal.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The owner's review of the shared registries (migration 20261116_regimen_references.sql). evidence_records and
// interaction_records are read-only and seeded 'unreviewed'; a verdict here is what makes a row count as reviewed for this
// person. A verdict holds for the row's version it was given on: an edited row asks for a new review.

@Serializable
data class RegistryReviewRow(
    val registry: String,
    @SerialName("record_id") val recordId: Long,
    @SerialName("record_version") val recordVersion: Int? = null,
    val verdict: String,
    val note: String? = null,
    @SerialName("reviewed_at") val reviewedAt: String,
)

/** A verdict as applied to one registry row. */
enum class ReviewState { UNREVIEWED, CONFIRMED, DISPUTED }

/** Reviewed by the seed itself, or confirmed by the owner on this version; disputed only by the owner. */
fun reviewState(seedStatus: String, version: Int?, review: RegistryReviewRow?): ReviewState = when {
    review != null && (review.recordVersion == null || review.recordVersion == version) ->
        if (review.verdict == "confirmed") ReviewState.CONFIRMED else ReviewState.DISPUTED
    seedStatus == "reviewed" -> ReviewState.CONFIRMED
    else -> ReviewState.UNREVIEWED
}

@Serializable
data class EvidenceReviewItem(
    val id: Long,
    @SerialName("subject_key") val subjectKey: String,
    @SerialName("outcome_key") val outcomeKey: String? = null,
    @SerialName("claim_text") val claimText: String,
    @SerialName("effect_direction") val effectDirection: String? = null,
    val population: String? = null,
    @SerialName("dose_range") val doseRange: String? = null,
    @SerialName("study_design") val studyDesign: String? = null,
    @SerialName("evidence_tier") val evidenceTier: String? = null,
    @SerialName("pmid_doi") val pmidDoi: String? = null,
    val source: String? = null,
    val caveats: String? = null,
    @SerialName("review_status") val reviewStatus: String = "unreviewed",
    val version: Int? = null,
)

@Serializable
data class InteractionReviewItem(
    val id: Long,
    @SerialName("a_aliases") val aAliases: List<String> = emptyList(),
    @SerialName("b_aliases") val bAliases: List<String> = emptyList(),
    @SerialName("a_keys") val aKeys: List<String> = emptyList(),
    @SerialName("b_keys") val bKeys: List<String> = emptyList(),
    val mechanism: String? = null,
    val effect: String = "",
    val advice: String = "",
    val severity: String? = null,
    @SerialName("study_design") val studyDesign: String? = null,
    @SerialName("evidence_tier") val evidenceTier: String? = null,
    @SerialName("pmid_doi") val pmidDoi: String? = null,
    val source: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    val caveats: String? = null,
    @SerialName("review_status") val reviewStatus: String = "unreviewed",
    val version: Int? = null,
)

@Serializable
data class LabWatchReviewItem(
    val id: Long,
    @SerialName("substance_aliases") val substanceAliases: List<String> = emptyList(),
    @SerialName("marker_label") val markerLabel: String,
    @SerialName("min_daily_amount") val minDailyAmount: Double? = null,
    @SerialName("amount_unit") val amountUnit: String? = null,
    val reason: String,
    val advice: String,
    val source: String,
    @SerialName("pmid_doi") val pmidDoi: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    val caveats: String? = null,
    @SerialName("review_status") val reviewStatus: String = "unreviewed",
    val version: Int? = null,
)

class RegistryReviewRepository(private val supabase: SupabaseClient) {

    /** (registry, record id) -> the owner's verdict. */
    suspend fun loadReviews(): Map<Pair<String, Long>, RegistryReviewRow> =
        ReadCache.get("registry.reviews", tables = setOf("registry_reviews")) {
            supabase.postgrest.from("registry_reviews")
                .select(Columns.list("registry,record_id,record_version,verdict,note,reviewed_at"))
                .decodeList<RegistryReviewRow>()
        }.associateBy { it.registry to it.recordId }

    suspend fun loadEvidence(): List<EvidenceReviewItem> =
        supabase.postgrest.from("evidence_records").select(
            Columns.list("id,subject_key,outcome_key,claim_text,effect_direction,population,dose_range,study_design,evidence_tier,pmid_doi,source,caveats,review_status,version"),
        ) { order("id", Order.ASCENDING) }.decodeList()

    suspend fun loadInteractions(): List<InteractionReviewItem> =
        supabase.postgrest.from("interaction_records").select(
            Columns.list("id,a_aliases,b_aliases,a_keys,b_keys,mechanism,effect,advice,severity,study_design,evidence_tier,pmid_doi,source,source_url,caveats,review_status,version"),
        ) { order("id", Order.ASCENDING) }.decodeList()

    suspend fun loadLabWatch(): List<LabWatchReviewItem> =
        supabase.postgrest.from("lab_watch_rules").select(
            Columns.list("id,substance_aliases,marker_label,min_daily_amount,amount_unit,reason,advice,source,pmid_doi,source_url,caveats,review_status,version"),
        ) { order("id", Order.ASCENDING) }.decodeList()

    suspend fun setVerdict(registry: String, recordId: Long, version: Int?, verdict: String, note: String?) {
        supabase.postgrest.from("registry_reviews").upsert(buildJsonObject {
            put("registry", registry)
            put("record_id", recordId)
            put("record_version", version)
            put("verdict", verdict)
            put("note", note?.trim()?.takeIf { it.isNotEmpty() }?.take(1000))
            put("reviewed_at", java.time.OffsetDateTime.now().toString())
        }) { onConflict = "user_id,registry,record_id" }
    }

    suspend fun clear(registry: String, recordId: Long) {
        supabase.postgrest.from("registry_reviews").delete {
            filter { eq("registry", registry); eq("record_id", recordId) }
        }
    }
}
