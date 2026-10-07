package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.Serializable

// Response of the supplement-suppco-lookup edge function (DAV-360). Every field
// defaulted so a partial / error body always decodes.
@Serializable
data class SuppcoServingSize(val raw: String? = null, val quantity: Double? = null, val unit: String? = null)

@Serializable
data class SuppcoRank(val category: String = "", val rank: String = "")

@Serializable
data class SuppcoTrust(
    val product: Double? = null,
    val brand: Double? = null,
    val status: String? = null,
    val percentileInCategory: Double? = null,
    val details: List<SuppcoRank> = emptyList(),
)

@Serializable
data class SuppcoSafety(
    val activeFdaRecall: Boolean = false,
    val recallUrl: String? = null,
    val recallBody: String? = null,
    val brandActiveFdaRecall: Boolean = false,
    val fdaWarningLetter: Boolean = false,
    val failedAnyTests: Boolean = false,
)

@Serializable
data class SuppcoTesting(
    val testedBySuppco: Boolean = false,
    val heavyMetals: String? = null,
    val identityPotency: String? = null,
    val certifications: List<String> = emptyList(),
)

@Serializable
data class SuppcoIngredient(
    val name: String = "",
    val formOf: String? = null,
    val category: String? = null,
    val nutrientId: String? = null,
    val amount: Double? = null,
    val unit: String? = null,
)

@Serializable
data class SuppcoProduct(
    val id: String? = null,
    val slug: String? = null,
    val name: String? = null,
    val brand: String? = null,
    val upc: String? = null,
    val category: String? = null,
    val format: String? = null,
    val servingSize: SuppcoServingSize = SuppcoServingSize(),
    val servingsPerContainer: Double? = null,
    val suggestedUse: String? = null,
    val validated: Boolean? = null,
    val offMarket: Boolean? = null,
    val containsProprietaryBlend: Boolean? = null,
    val trust: SuppcoTrust = SuppcoTrust(),
    val safety: SuppcoSafety = SuppcoSafety(),
    val testing: SuppcoTesting = SuppcoTesting(),
    val price: Double? = null,
    val pricePerServing: Double? = null,
    val labelUrl: String? = null,
    val ingredients: List<SuppcoIngredient> = emptyList(),
)

@Serializable
data class SuppcoLookupResponse(
    val found: Boolean = false,
    val retrievedAt: String? = null,
    val source: DsldSourceInfo = DsldSourceInfo(name = "SuppCo", url = "https://supp.co"),
    val payloadHash: String? = null,
    val products: List<SuppcoProduct> = emptyList(),
    val error: String? = null,
    val message: String? = null,
)

// Safety facts stated in words (never by color alone). Shared by the verify screen
// and the pantry so both read the same.
fun SuppcoProduct.flagTexts(): List<String> = buildList {
    if (safety.activeFdaRecall) add("ACTIVE FDA RECALL${safety.recallBody?.let { ": $it" } ?: ""}")
    if (safety.brandActiveFdaRecall) add("ACTIVE FDA RECALL ON ANOTHER PRODUCT FROM THIS BRAND")
    if (safety.fdaWarningLetter) add("FDA WARNING LETTER RECEIVED")
    if (safety.failedAnyTests) add("FAILED INDEPENDENT TESTS")
    if (offMarket == true) add("OFF MARKET")
}
