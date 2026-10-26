package com.bioscan.fieldterminal.ui.components

import androidx.compose.ui.graphics.Color
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// One colour per nutrient, the same on every Nutrition page (bars, donut, legends, trend, plan carb strip and chips) and
// in Setup's goal rows. Identity only: whether a value is on target is always said in words, never by these colours.
// Chosen with the dataviz palette validator as a set on the dark surface (#11161A, all pairs): lightness 0.48-0.67,
// chroma >= 0.10, normal-vision dE >= 16, contrast >= 3:1; the worst colour-blind pair (calories/carbs, dE 7.6) is always
// labelled. None of them is a state colour (emerald, amber, red).
object NutrientColor {
    val Calories = Color(0xFF9085E9) // violet
    val Protein = Color(0xFFD55181) // magenta
    val Carbs = Color(0xFF16A3B8) // cyan
    val Fat = Color(0xFFC98500) // gold
    val Water = FT.DomainFuel
}
