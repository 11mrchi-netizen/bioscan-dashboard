package com.bioscan.fieldterminal.ui.screens.fuel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.fuel.CarbCode
import com.bioscan.fieldterminal.domain.fuel.DayClass
import com.bioscan.fieldterminal.ui.components.NutrientColor
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlin.math.roundToInt

// The carbohydrate code as a chip: one hue (Fuel's domain accent) in three steps plus the word, never red / amber /
// green, which the contract keeps for status. The word carries the meaning; the fill only repeats it.

private fun stepAlpha(step: Int): Float = when (step) {
    0 -> 0.18f
    1 -> 0.42f
    else -> 0.75f
}

@Composable
fun CodeChip(code: CarbCode, modifier: Modifier = Modifier) {
    // A carbohydrate level, so the carbs colour (ui/components/NutritionColors.kt).
    StepChip(code.label, code.ordinal, modifier, NutrientColor.Carbs)
}

@Composable
fun DayClassChip(dayClass: DayClass, modifier: Modifier = Modifier) {
    StepChip("${dayClass.label} DAY", if (dayClass == DayClass.LOADING) 2 else dayClass.ordinal, modifier)
}

@Composable
private fun StepChip(label: String, step: Int, modifier: Modifier, color: Color = FT.DomainFuel) {
    val shape = RoundedCornerShape(FT.RadiusSmall)
    Box(
        modifier
            .background(color.copy(alpha = stepAlpha(step)), shape)
            .border(FT.BorderWidth, color.copy(alpha = 0.6f), shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(label, style = FTType.LabelCaps, color = FT.TextPrimary)
    }
}

fun gRange(lo: Double, hi: Double): String = "${(lo / 5).roundToInt() * 5}-${(hi / 5).roundToInt() * 5} G"

fun hm(minutes: Double): String {
    val t = minutes.roundToInt()
    return "%d:%02d".format(t / 60, t % 60)
}
