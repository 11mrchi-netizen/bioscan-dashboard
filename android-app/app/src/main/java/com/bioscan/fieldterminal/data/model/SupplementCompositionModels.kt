package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Supplement Intelligence Phase 1 (DAV-328 audit): Product -> Ingredients ->
// Nutrients. Mirrors foods/food_sources' shape, but personal (user_id +
// owner RLS) rather than a shared catalog -- see migration comment.

@Serializable
data class SupplementSourceRow(
    val id: Long,
    val name: String,
    val url: String? = null,
)

@Serializable
data class SupplementIngredientRow(
    val id: Long,
    val name: String,
    val category: String? = null,
    @SerialName("nutrient_key") val nutrientKey: String? = null,
)

@Serializable
data class NewSupplementIngredientRow(
    val name: String,
    val category: String? = null,
    @SerialName("nutrient_key") val nutrientKey: String? = null,
)

@Serializable
data class SupplementProductRow(
    val id: Long,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    @SerialName("serving_size") val servingSize: Double? = null,
    @SerialName("serving_unit") val servingUnit: String? = null,
    @SerialName("serving_form") val servingForm: String? = null,
    @SerialName("match_confidence") val matchConfidence: String? = null,
)

@Serializable
data class NewSupplementProductRow(
    val name: String,
    @SerialName("serving_size") val servingSize: Double? = null,
    @SerialName("serving_unit") val servingUnit: String? = null,
    @SerialName("serving_form") val servingForm: String? = null,
    @SerialName("match_confidence") val matchConfidence: String? = null,
)

// Flat read of one product's ingredient rows -- joined against
// SupplementIngredientRow in Kotlin (SupplementsRepository.loadProductComposition()),
// not via Postgrest relational embedding, matching this project's existing
// "no relational embedding, join in Kotlin" convention (see AnalysisModels.kt).
@Serializable
data class SupplementProductIngredientRow(
    val id: Long,
    @SerialName("supplement_ingredient_id") val supplementIngredientId: Long,
    @SerialName("compound_amount") val compoundAmount: Double,
    @SerialName("compound_unit") val compoundUnit: String,
    @SerialName("elemental_amount") val elementalAmount: Double? = null,
    @SerialName("elemental_unit") val elementalUnit: String? = null,
)

// Result of SupplementsRepository.loadProductComposition()'s Kotlin-side join --
// exactly what domain/SupplementComposition.kt's nutrientContribution() needs.
data class SupplementProductIngredientWithKey(
    val compoundAmount: Double,
    val compoundUnit: String,
    val elementalAmount: Double?,
    val elementalUnit: String?,
    val nutrientKey: String?,
)

// Full ingredient data including name -- used to pre-populate the
// edit form in SupplementFormSheet when existing.productId != null.
data class SupplementProductIngredientFull(
    val name: String,
    val nutrientKey: String?,
    val compoundAmount: Double,
    val compoundUnit: String,
    val elementalAmount: Double?,
    val elementalUnit: String?,
)

@Serializable
data class NewSupplementProductIngredientRow(
    @SerialName("supplement_product_id") val supplementProductId: Long,
    @SerialName("supplement_ingredient_id") val supplementIngredientId: Long,
    @SerialName("compound_amount") val compoundAmount: Double,
    @SerialName("compound_unit") val compoundUnit: String,
    @SerialName("elemental_amount") val elementalAmount: Double? = null,
    @SerialName("elemental_unit") val elementalUnit: String? = null,
)
