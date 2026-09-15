package com.bioscan.fieldterminal.domain

// Step 13: what a Gemini vision call returns for a food photo. All numeric
// fields nullable -- the model is instructed to omit a field it can't
// estimate rather than guess a number, and the Food form must handle that
// (an empty field for the user to fill in, not a fabricated zero).
data class FoodEstimate(
    val description: String?,
    val calories: Double?,
    val proteinG: Double?,
    val carbsG: Double?,
    val fatG: Double?,
)
