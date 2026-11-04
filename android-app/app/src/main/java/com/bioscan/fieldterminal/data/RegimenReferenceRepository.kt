package com.bioscan.fieldterminal.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

// Reference cards (docs/regimen/README.md, phase 1b; migration 20261116_regimen_references.sql). The lookup runs only when
// the person asks (regimen-reference-lookup: RxNorm, openFDA labels, PubMed); the one card picked is kept per item, so the
// app never holds a catalogue, only what the roster uses. Refreshing replaces the card.

private const val FUNCTIONS_BASE_URL = "https://ugfrglbcoivkprjqvjzz.supabase.co/functions/v1"

@Serializable
data class RxConceptDto(val rxcui: String, val name: String, val tty: String? = null)

@Serializable
data class RxNormDto(val rxcui: String, val name: String, val tty: String? = null, val ingredients: List<RxConceptDto> = emptyList())

@Serializable
data class LabelCandidateDto(
    val setId: String,
    val effectiveTime: String? = null,
    val brand: String? = null,
    val generic: String? = null,
    val manufacturer: String? = null,
    val route: List<String> = emptyList(),
    val productType: String? = null,
)

@Serializable
data class HalfLifeDto(val hours: Double, val text: String)

@Serializable
data class CompactLabelDto(
    val setId: String,
    val effectiveTime: String? = null,
    val brand: String? = null,
    val generic: String? = null,
    val manufacturer: String? = null,
    val route: List<String> = emptyList(),
    val productType: String? = null,
    val version: String? = null,
    val url: String,
    val sections: Map<String, String> = emptyMap(),
    val halfLife: HalfLifeDto? = null,
)

@Serializable
data class PubmedItemDto(val pmid: String, val title: String, val journal: String? = null, val year: String? = null, val url: String)

@Serializable
data class ReferenceSearchDto(
    val retrievedAt: String,
    val term: String,
    val rxnorm: RxNormDto? = null,
    val labels: List<LabelCandidateDto> = emptyList(),
    val pubmed: List<PubmedItemDto> = emptyList(),
)

@Serializable
private data class LabelFetchDto(val found: Boolean, val retrievedAt: String, val label: CompactLabelDto? = null)

/** What the card keeps for PubMed: the term and the studies found. */
@Serializable
data class PubmedPayload(val term: String, val items: List<PubmedItemDto>)

@Serializable
data class RegimenReferenceRow(
    val id: Long,
    @SerialName("supplement_id") val supplementId: Long,
    val source: String,
    @SerialName("external_id") val externalId: String,
    val title: String,
    val url: String? = null,
    val rxcui: String? = null,
    val ingredient: String? = null,
    @SerialName("half_life_hours") val halfLifeHours: Double? = null,
    @SerialName("half_life_text") val halfLifeText: String? = null,
    val payload: JsonObject,
    @SerialName("retrieved_at") val retrievedAt: String,
) {
    private val json get() = Json { ignoreUnknownKeys = true }
    val label: CompactLabelDto? get() = if (source == "fda_label") runCatching { json.decodeFromJsonElement(CompactLabelDto.serializer(), payload) }.getOrNull() else null
    val studies: PubmedPayload? get() = if (source == "pubmed") runCatching { json.decodeFromJsonElement(PubmedPayload.serializer(), payload) }.getOrNull() else null
}

private const val REFERENCE_COLUMNS = "id,supplement_id,source,external_id,title,url,rxcui,ingredient,half_life_hours,half_life_text,payload,retrieved_at"

class RegimenReferenceRepository(private val supabase: SupabaseClient) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private suspend fun call(body: JsonObject): JsonObject {
        val token = supabase.auth.currentAccessTokenOrNull() ?: throw IllegalStateException("Not signed in")
        val response = client.post("$FUNCTIONS_BASE_URL/regimen-reference-lookup") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val obj = json.parseToJsonElement(response.bodyAsText()).jsonObject
        obj["error"]?.let { throw IllegalStateException((obj["detail"] ?: obj["message"] ?: it).toString().trim('"')) }
        return obj
    }

    /** RxNorm's id, the FDA labels found (prescription first, newest first) and, without a label, PubMed studies. */
    suspend fun search(name: String): ReferenceSearchDto =
        json.decodeFromJsonElement(ReferenceSearchDto.serializer(), call(buildJsonObject { put("query", name) }))

    /** Fetches one label and keeps it as [supplementId]'s card (replacing any earlier one). */
    suspend fun saveLabel(supplementId: Long, setId: String, rxnorm: RxNormDto?): RegimenReferenceRow {
        val fetched = json.decodeFromJsonElement(LabelFetchDto.serializer(), call(buildJsonObject { put("labelSetId", setId) }))
        val label = fetched.label ?: throw IllegalStateException("The FDA no longer serves this label.")
        val title = listOfNotNull(label.brand ?: label.generic, label.manufacturer).joinToString(" · ").ifBlank { "FDA label" }.take(300)
        return upsert(buildJsonObject {
            put("supplement_id", supplementId)
            put("source", "fda_label")
            put("external_id", label.setId)
            put("title", title)
            put("url", label.url)
            put("rxcui", rxnorm?.rxcui)
            put("ingredient", rxnorm?.ingredients?.firstOrNull()?.name ?: label.generic)
            put("half_life_hours", label.halfLife?.hours)
            put("half_life_text", label.halfLife?.text?.take(600))
            put("payload", json.encodeToJsonElement(CompactLabelDto.serializer(), label))
            put("retrieved_at", fetched.retrievedAt)
        })
    }

    /** Keeps the PubMed studies found as [supplementId]'s card, for substances with no FDA label. */
    suspend fun saveStudies(supplementId: Long, search: ReferenceSearchDto): RegimenReferenceRow = upsert(buildJsonObject {
        put("supplement_id", supplementId)
        put("source", "pubmed")
        put("external_id", search.term.take(200))
        put("title", "PubMed: ${search.term}".take(300))
        put("url", "https://pubmed.ncbi.nlm.nih.gov/?term=${java.net.URLEncoder.encode(search.term, "UTF-8")}")
        put("payload", json.encodeToJsonElement(PubmedPayload.serializer(), PubmedPayload(search.term, search.pubmed)))
        put("retrieved_at", search.retrievedAt)
    })

    private suspend fun upsert(row: JsonObject): RegimenReferenceRow =
        supabase.postgrest.from("regimen_references").upsert(row) { onConflict = "supplement_id"; select(Columns.raw(REFERENCE_COLUMNS)) }
            .decodeSingle<RegimenReferenceRow>()

    suspend fun load(supplementId: Long): RegimenReferenceRow? =
        supabase.postgrest.from("regimen_references").select(Columns.raw(REFERENCE_COLUMNS)) { filter { eq("supplement_id", supplementId) } }
            .decodeList<RegimenReferenceRow>().firstOrNull()

    /** Every card, for the list's label mentions. Kept with the read cache like the roster. */
    suspend fun loadAll(): List<RegimenReferenceRow> =
        ReadCache.get("regimen.references", tables = setOf("regimen_references")) {
            supabase.postgrest.from("regimen_references").select(Columns.raw(REFERENCE_COLUMNS)).decodeList<RegimenReferenceRow>()
        }

    suspend fun remove(supplementId: Long) {
        supabase.postgrest.from("regimen_references").delete { filter { eq("supplement_id", supplementId) } }
    }

    private companion object {
        val client = HttpClient(OkHttp)
    }
}
