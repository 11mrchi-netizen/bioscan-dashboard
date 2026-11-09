package com.bioscan.fieldterminal.ui.components

import androidx.compose.ui.graphics.Color
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// One colour per nutrient, the same on every Nutrition page (bars, donut, legends, trend, plan carb strip and chips) and
// in Setup's goal rows. Identity only: whether a value is on target is always said in words, never by these colours.
// v1: the fixed metric hues (FT.Metric, design/tokens/tokens.css --m-calories / protein / carbs / fat / hydration).
// Carbs and fat sit close to the warn hue, so every use carries its label; colour is never the only signal.
object NutrientColor {
    val Calories: Color = FT.Metric.Calories
    val Protein: Color = FT.Metric.Protein
    val Carbs: Color = FT.Metric.Carbs
    val Fat: Color = FT.Metric.Fat
    val Water: Color = FT.Metric.Hydration
    val Fiber: Color = FT.Metric.Fiber
    val Sugar: Color = FT.Metric.Sugar
    val Sodium: Color = FT.Metric.Sodium
    val Caffeine: Color = FT.Metric.Caffeine
}
