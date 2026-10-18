package com.bioscan.fieldterminal.ui.screens.fuel

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.bioscan.fieldterminal.data.FuelPlanRepository
import com.bioscan.fieldterminal.data.NutritionOverview
import com.bioscan.fieldterminal.data.PeriodizationView
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.fuel.CPI_MIN_DAYS
import com.bioscan.fieldterminal.domain.fuel.periodizationWords
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlin.math.roundToInt

// Fuel › Nutrition: how well the last weeks' logged carbohydrate followed the training (the Carbohydrate Periodization
// Index) and how the meal codes were kept (domain/fuel/Periodization.kt).
@Composable
fun PeriodizationCard(overview: NutritionOverview, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var view by remember { mutableStateOf<PeriodizationView?>(null) }
    LaunchedEffect(overview) { view = runCatching { FuelPlanRepository(SupabaseClientProvider.client).loadPeriodization(context, overview) }.getOrNull() }
    val v = view ?: return
    FTCard(
        title = "PERIODISATION · LAST 4 WEEKS",
        modifier = modifier,
        info = "How closely your logged carbohydrate rose and fell with your training, and how often meals stayed within their code's range.",
        infoDetails = "Carbohydrate Periodization Index (Rothschild et al. 2022, preprint): the correlation of daily carbohydrate (g/kg) " +
            "with the day's training energy, times the range of daily carbohydrate, divided by its monotony (mean / SD). Only days with " +
            "at least 3 meals logged count; $CPI_MIN_DAYS of the last 28 are needed. Code adherence covers the last 14 days, meals not " +
            "logged left out.",
    ) {
        val p = v.index
        if (p == null) {
            Text("${v.fullyLoggedDays} of $CPI_MIN_DAYS fully logged days needed (3 or more meals a day).", style = FTType.BodySmall, color = FT.TextSecondary)
        } else {
            Text("INDEX %.2f · CORRELATION %.2f · RANGE %.1f G/KG".format(p.index, p.correlation, p.rangeGPerKg), style = FTType.LabelCaps, color = FT.TextPrimary)
            Text(periodizationWords(p), style = FTType.BodySmall, color = FT.TextSecondary)
            Text("${p.days} fully logged days.", style = FTType.Caption, color = FT.TextSecondary)
        }
        v.adherence.filter { it.logged > 0 }.forEach { a ->
            Text(
                "${a.code.label} MEALS WITHIN RANGE: ${a.within} OF ${a.logged} (${((a.withinShare ?: 0.0) * 100).roundToInt()} %) · UNDER ${a.under} · OVER ${a.over}",
                style = FTType.MonoCaption, color = FT.TextSecondary,
            )
        }
    }
}
