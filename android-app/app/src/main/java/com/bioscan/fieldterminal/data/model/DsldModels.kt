package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Response of the supplement-dsld-lookup edge function (DAV-359). Every field
// defaulted so a partial / error body always decodes.
@Serializable
data class DsldIngredientRow(
    val name: String = "",
    val category: String? = null,
    val amount: Double? = null,
    val unit: String? = null,
)

@Serializable
data class DsldServingSize(val min: Double? = null, val max: Double? = null, val unit: String? = null)

@Serializable
data class DsldLabel(
    val dsldId: Long = 0,
    val fullName: String? = null,
    val brandName: String? = null,
    val upcSku: String? = null,
    val offMarket: Boolean? = null,
    val entryDate: String? = null,
    val productVersionCode: String? = null,
    val servingsPerContainer: Double? = null,
    val netContents: String? = null,
    val form: String? = null,
    val servingSize: DsldServingSize? = null,
    val ingredients: List<DsldIngredientRow> = emptyList(),
)

@Serializable
data class DsldSourceInfo(val name: String = "NIH DSLD", val url: String? = null, val apiVersion: String? = null)

// A name-search hit: no UPC at this stage; the label is fetched by id on pick.
@Serializable
data class DsldCandidate(
    val dsldId: Long = 0,
    val fullName: String? = null,
    val brandName: String? = null,
    val offMarket: Boolean? = null,
    val entryDate: String? = null,
    val netContents: String? = null,
    val form: String? = null,
)

@Serializable
data class DsldLookupResponse(
    val found: Boolean = false,
    val retrievedAt: String? = null,
    val source: DsldSourceInfo = DsldSourceInfo(),
    val payloadHash: String? = null,
    val labels: List<DsldLabel> = emptyList(),
    val candidates: List<DsldCandidate> = emptyList(),
    val error: String? = null,
    val message: String? = null,
)
