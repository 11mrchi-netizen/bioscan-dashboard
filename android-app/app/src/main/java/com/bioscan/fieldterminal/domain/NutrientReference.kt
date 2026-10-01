package com.bioscan.fieldterminal.domain

data class NutrientInfo(
    val key: String,
    val displayName: String,
    val unit: String,
    val rda: Double?,
    val upperLimit: Double?,
    val category: NutrientCategory,
)

enum class NutrientCategory(val label: String, val sortOrder: Int) {
    Vitamin("VITAMINS", 0),
    Mineral("MINERALS", 1),
    DetailedFat("DETAILED FATS", 2),
    DetailedCarb("DETAILED CARBS", 3),
    AminoAcid("AMINO ACIDS", 4),
    Other("OTHER", 5),
}

// Adult male RDA/AI values from NASEM Dietary Reference Intakes (2019-2024).
// UL (tolerable upper intake level) where established. Nutrients without a
// well-defined population RDA get null — the bar renders without a reference
// line so the user still sees intake but no "% of target" claim.
val NUTRIENT_REFERENCE: Map<String, NutrientInfo> = listOf(
    // Vitamins
    NutrientInfo("vitamin_a", "Vitamin A", "IU", 3000.0, 10000.0, NutrientCategory.Vitamin),
    NutrientInfo("vitamin_c", "Vitamin C", "mg", 90.0, 2000.0, NutrientCategory.Vitamin),
    NutrientInfo("vitamin_d", "Vitamin D", "IU", 600.0, 4000.0, NutrientCategory.Vitamin),
    NutrientInfo("vitamin_e", "Vitamin E", "mg", 15.0, 1000.0, NutrientCategory.Vitamin),
    NutrientInfo("vitamin_k", "Vitamin K", "mcg", 120.0, null, NutrientCategory.Vitamin),
    NutrientInfo("thiamin_b1", "Thiamin (B1)", "mg", 1.2, null, NutrientCategory.Vitamin),
    NutrientInfo("riboflavin_b2", "Riboflavin (B2)", "mg", 1.3, null, NutrientCategory.Vitamin),
    NutrientInfo("niacin_b3", "Niacin (B3)", "mg", 16.0, 35.0, NutrientCategory.Vitamin),
    NutrientInfo("pantothenic_acid_b5", "Pantothenic acid (B5)", "mg", 5.0, null, NutrientCategory.Vitamin),
    NutrientInfo("vitamin_b6", "Vitamin B6", "mg", 1.3, 100.0, NutrientCategory.Vitamin),
    NutrientInfo("folate_b9", "Folate (B9)", "mcg", 400.0, 1000.0, NutrientCategory.Vitamin),
    NutrientInfo("vitamin_b12", "Vitamin B12", "mcg", 2.4, null, NutrientCategory.Vitamin),
    NutrientInfo("choline", "Choline", "mg", 550.0, 3500.0, NutrientCategory.Vitamin),

    // Minerals
    NutrientInfo("calcium", "Calcium", "mg", 1000.0, 2500.0, NutrientCategory.Mineral),
    NutrientInfo("iron", "Iron", "mg", 8.0, 45.0, NutrientCategory.Mineral),
    NutrientInfo("magnesium", "Magnesium", "mg", 420.0, null, NutrientCategory.Mineral),
    NutrientInfo("phosphorus", "Phosphorus", "mg", 700.0, 4000.0, NutrientCategory.Mineral),
    NutrientInfo("potassium", "Potassium", "mg", 2600.0, null, NutrientCategory.Mineral),
    NutrientInfo("zinc", "Zinc", "mg", 11.0, 40.0, NutrientCategory.Mineral),
    NutrientInfo("copper", "Copper", "mg", 0.9, 10.0, NutrientCategory.Mineral),
    NutrientInfo("manganese", "Manganese", "mg", 2.3, 11.0, NutrientCategory.Mineral),
    NutrientInfo("selenium", "Selenium", "mcg", 55.0, 400.0, NutrientCategory.Mineral),
    NutrientInfo("sodium", "Sodium", "mg", null, 2300.0, NutrientCategory.Mineral),

    // Detailed fats
    NutrientInfo("saturated_fat", "Saturated fat", "g", null, null, NutrientCategory.DetailedFat),
    NutrientInfo("monounsaturated_fat", "Monounsaturated fat", "g", null, null, NutrientCategory.DetailedFat),
    NutrientInfo("polyunsaturated_fat", "Polyunsaturated fat", "g", null, null, NutrientCategory.DetailedFat),
    NutrientInfo("trans_fat", "Trans fat", "g", null, null, NutrientCategory.DetailedFat),
    NutrientInfo("cholesterol", "Cholesterol", "mg", null, 300.0, NutrientCategory.DetailedFat),
    NutrientInfo("omega_3", "Omega-3", "g", 1.6, null, NutrientCategory.DetailedFat),
    NutrientInfo("omega_6", "Omega-6", "g", 17.0, null, NutrientCategory.DetailedFat),
    NutrientInfo("dha", "DHA", "g", null, null, NutrientCategory.DetailedFat),
    NutrientInfo("epa", "EPA", "g", null, null, NutrientCategory.DetailedFat),
    NutrientInfo("ala", "ALA", "g", 1.6, null, NutrientCategory.DetailedFat),

    // Detailed carbs
    NutrientInfo("fiber", "Fiber", "g", 38.0, null, NutrientCategory.DetailedCarb),
    NutrientInfo("sugar", "Sugar", "g", null, null, NutrientCategory.DetailedCarb),
    NutrientInfo("starch", "Starch", "g", null, null, NutrientCategory.DetailedCarb),

    // Amino acids
    NutrientInfo("tryptophan", "Tryptophan", "g", null, null, NutrientCategory.AminoAcid),
    NutrientInfo("threonine", "Threonine", "g", null, null, NutrientCategory.AminoAcid),
    NutrientInfo("isoleucine", "Isoleucine", "g", null, null, NutrientCategory.AminoAcid),
    NutrientInfo("leucine", "Leucine", "g", null, null, NutrientCategory.AminoAcid),
    NutrientInfo("lysine", "Lysine", "g", null, null, NutrientCategory.AminoAcid),
    NutrientInfo("methionine", "Methionine", "g", null, null, NutrientCategory.AminoAcid),
    NutrientInfo("cystine", "Cystine", "g", null, null, NutrientCategory.AminoAcid),
    NutrientInfo("phenylalanine", "Phenylalanine", "g", null, null, NutrientCategory.AminoAcid),
    NutrientInfo("tyrosine", "Tyrosine", "g", null, null, NutrientCategory.AminoAcid),
    NutrientInfo("valine", "Valine", "g", null, null, NutrientCategory.AminoAcid),
    NutrientInfo("arginine", "Arginine", "g", null, null, NutrientCategory.AminoAcid),
    NutrientInfo("histidine", "Histidine", "g", null, null, NutrientCategory.AminoAcid),
    NutrientInfo("alanine", "Alanine", "g", null, null, NutrientCategory.AminoAcid),
    NutrientInfo("aspartic_acid", "Aspartic acid", "g", null, null, NutrientCategory.AminoAcid),
    NutrientInfo("glutamic_acid", "Glutamic acid", "g", null, null, NutrientCategory.AminoAcid),
    NutrientInfo("glycine", "Glycine", "g", null, null, NutrientCategory.AminoAcid),
    NutrientInfo("proline", "Proline", "g", null, null, NutrientCategory.AminoAcid),
    NutrientInfo("serine", "Serine", "g", null, null, NutrientCategory.AminoAcid),

    // Other
    NutrientInfo("caffeine", "Caffeine", "mg", null, 400.0, NutrientCategory.Other),
    NutrientInfo("alcohol", "Alcohol", "g", null, null, NutrientCategory.Other),
    NutrientInfo("water", "Water", "g", null, null, NutrientCategory.Other),
).associateBy { it.key }

fun nutrientDisplayName(key: String): String =
    NUTRIENT_REFERENCE[key]?.displayName
        ?: key.replace('_', ' ').replaceFirstChar { it.uppercase() }
