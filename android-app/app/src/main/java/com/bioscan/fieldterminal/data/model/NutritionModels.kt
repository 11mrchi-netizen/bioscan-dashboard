package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MealRow(
    val id: Long? = null,
    @SerialName("logged_at") val loggedAt: String,
    val description: String? = null,
    val calories: Double? = null,
    @SerialName("protein_g") val proteinG: Double? = null,
    @SerialName("fat_g") val fatG: Double? = null,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("fiber_g") val fiberG: Double? = null,
    @SerialName("sugar_g") val sugarG: Double? = null,
    @SerialName("sodium_mg") val sodiumMg: Double? = null,
    val source: String? = null,
)

@Serializable
data class HydrationDailyRow(
    val date: String,
    val ml: Int? = null,
)

@Serializable
data class FoodSourceRow(
    val id: Long,
    val name: String,
    val version: String? = null,
    val attribution: String? = null,
    val country: String? = null,
    val language: String? = null,
)

@Serializable
data class FoodRow(
    val id: Long? = null,
    @SerialName("food_source_id") val foodSourceId: Long,
    @SerialName("source_food_id") val sourceFoodId: String? = null,
    val name: String,
    @SerialName("name_zh") val nameZh: String? = null,
    val brand: String? = null,
    val barcode: String? = null,
    val category: String? = null,
    val subcategory: String? = null,
    val preparation: String? = null,
    @SerialName("is_composite") val isComposite: Boolean = false,
    @SerialName("beverage_class") val beverageClass: String? = null,
    @SerialName("beverage_subtype") val beverageSubtype: String? = null,
)

// DAV-181. Versioned beverage-class -> effective-hydration retention factor
// lookup -- kept out of app code so factors can be revised without a release.
@Serializable
data class HydrationFactorModelRow(
    val id: Long? = null,
    @SerialName("model_version") val modelVersion: String,
    @SerialName("beverage_class") val beverageClass: String,
    @SerialName("retention_factor") val retentionFactor: Double,
    val notes: String? = null,
)

@Serializable
data class FoodNutrientRow(
    val id: Long? = null,
    @SerialName("food_id") val foodId: Long,
    val nutrient: String,
    val amount: Double,
    val unit: String,
    @SerialName("basis_qty") val basisQty: Double = 100.0,
    @SerialName("basis_unit") val basisUnit: String = "g",
)

@Serializable
data class FoodServingRow(
    val id: Long? = null,
    @SerialName("food_id") val foodId: Long,
    @SerialName("serving_name") val servingName: String,
    val grams: Double? = null,
    val ml: Double? = null,
)

@Serializable
data class MealItemRow(
    val id: Long? = null,
    @SerialName("meal_id") val mealId: Long,
    @SerialName("food_id") val foodId: Long? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
    val description: String? = null,
    val quantity: Double? = null,
    @SerialName("quantity_unit") val quantityUnit: String? = "g",
    @SerialName("quantity_low") val quantityLow: Double? = null,
    @SerialName("quantity_high") val quantityHigh: Double? = null,
    @SerialName("serving_id") val servingId: Long? = null,
    @SerialName("serving_count") val servingCount: Double? = null,
    val preparation: String? = null,
    val calories: Double? = null,
    @SerialName("protein_g") val proteinG: Double? = null,
    @SerialName("fat_g") val fatG: Double? = null,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("fiber_g") val fiberG: Double? = null,
    @SerialName("sugar_g") val sugarG: Double? = null,
    @SerialName("sodium_mg") val sodiumMg: Double? = null,
    @SerialName("is_estimated") val isEstimated: Boolean = false,
    val confidence: Double? = null,
    val source: String = "manual",
    @SerialName("is_beverage") val isBeverage: Boolean = false,
    @SerialName("water_ml") val waterMl: Double? = null,
    @SerialName("caffeine_mg") val caffeineMg: Double? = null,
    @SerialName("alcohol_g") val alcoholG: Double? = null,
    @SerialName("effective_hydration_ml") val effectiveHydrationMl: Double? = null,
    @SerialName("hydration_model_version") val hydrationModelVersion: String? = null,
    @SerialName("hydration_confidence") val hydrationConfidence: Double? = null,
)

@Serializable
data class MealInputRow(
    val id: Long? = null,
    @SerialName("meal_id") val mealId: Long,
    @SerialName("input_type") val inputType: String,
    @SerialName("raw_text") val rawText: String? = null,
    @SerialName("image_path") val imagePath: String? = null,
    @SerialName("barcode_value") val barcodeValue: String? = null,
    @SerialName("barcode_format") val barcodeFormat: String? = null,
)

@Serializable
data class AiEstimateRow(
    val id: Long? = null,
    @SerialName("meal_id") val mealId: Long? = null,
    @SerialName("meal_item_id") val mealItemId: Long? = null,
    @SerialName("meal_input_id") val mealInputId: Long? = null,
    val model: String,
    @SerialName("model_version") val modelVersion: String? = null,
    @SerialName("prompt_text") val promptText: String? = null,
    @SerialName("prompt_image_path") val promptImagePath: String? = null,
    @SerialName("raw_response") val rawResponse: String? = null,
    @SerialName("parsed_output") val parsedOutput: String? = null,
    @SerialName("latency_ms") val latencyMs: Int? = null,
    val accepted: Boolean? = null,
)
