package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.NutrientBreakdownRepository
import com.bioscan.fieldterminal.data.NutritionGoalsStore
import com.bioscan.fieldterminal.data.NutritionOverview
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.hydrationBoundsMl
import com.bioscan.fieldterminal.domain.DailyNutrition
import com.bioscan.fieldterminal.domain.EvalState
import com.bioscan.fieldterminal.domain.MacroMove
import com.bioscan.fieldterminal.domain.MetricState
import com.bioscan.fieldterminal.domain.NUTRIENT_REFERENCE
import com.bioscan.fieldterminal.domain.NutrientCategory
import com.bioscan.fieldterminal.domain.StateWord
import com.bioscan.fieldterminal.domain.TdeeEvaluation
import com.bioscan.fieldterminal.domain.TimeRange
import com.bioscan.fieldterminal.domain.aggregate
import com.bioscan.fieldterminal.domain.evaluateNutrition
import com.bioscan.fieldterminal.domain.evaluateTdee
import com.bioscan.fieldterminal.domain.evaluateWeightTrend
import com.bioscan.fieldterminal.domain.macroMove
import com.bioscan.fieldterminal.domain.perKg
import com.bioscan.fieldterminal.domain.proteinByMeal
import com.bioscan.fieldterminal.domain.windowed
import com.bioscan.fieldterminal.ui.components.AxisLine
import com.bioscan.fieldterminal.ui.components.BaselineShiftBand
import com.bioscan.fieldterminal.ui.components.DualAxisChart
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTScoreRow
import com.bioscan.fieldterminal.ui.components.FTStatePill
import com.bioscan.fieldterminal.ui.components.GlobalTimeRange
import com.bioscan.fieldterminal.ui.components.MetricCardHeader
import com.bioscan.fieldterminal.ui.components.NutrientColor
import com.bioscan.fieldterminal.ui.components.RedesignCard
import com.bioscan.fieldterminal.ui.components.StackedColumn
import com.bioscan.fieldterminal.ui.components.StackedColumns
import com.bioscan.fieldterminal.ui.components.TrendBaselineCard
import com.bioscan.fieldterminal.ui.components.WindowBaseline
import com.bioscan.fieldterminal.ui.components.windowBaseline
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import kotlin.math.roundToInt

// Redesign (spec §5): the Nutrition and Hydration pages. Every chart follows the global range (GlobalTimeRange).

@Composable
internal fun NutritionPage(overview: NutritionOverview, onOpenNutrientBreakdown: () -> Unit) {
    val range = GlobalTimeRange.selected
    var weights by remember { mutableStateOf<List<Pair<LocalDate, Double>>>(emptyList()) }
    var tdee by remember { mutableStateOf<TdeeEvaluation?>(null) }
    LaunchedEffect(overview) {
        // A failed weight read leaves the TDEE band and the per-kg card on their empty state rather than failing the tab.
        runCatching { AnalysisRepository(SupabaseClientProvider.client).loadBodyMetrics() }.getOrNull()?.let { rows ->
            weights = rows.mapNotNull { r -> r.weightKg?.let { LocalDate.parse(r.date) to it } }
            tdee = evaluateTdee(evaluateNutrition(overview.allDays), evaluateWeightTrend(weights))
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        CaloriesMacrosCard(overview, tdee, range)
        MacrosPerKgCard(overview, weights, range)
        ProteinPerMealCard(overview, range)
        MicrosCard(range, onOpenNutrientBreakdown)
    }
}

private fun series(days: List<DailyNutrition>, pick: (DailyNutrition) -> Double) = days.map { LocalDate.parse(it.date) to pick(it) }

@Composable
private fun CaloriesMacrosCard(overview: NutritionOverview, tdee: TdeeEvaluation?, range: TimeRange) {
    val goals = NutritionGoalsStore.getGoals(LocalContext.current)
    val days = overview.allDays
    val calories = series(days) { it.calories }
    val latest = calories.maxByOrNull { it.first }?.second
    // The baseline is centred on the saved calorie target (limits 10 % either side, as GOAL ADHERENCE used); without a target, on the range's own mean.
    val band: WindowBaseline? = goals.caloriesKcal?.let { WindowBaseline(it, it * 0.9, it * 1.1) } ?: windowBaseline(windowed(calories, range).map { it.second })
    val tdeeBand = tdee?.takeIf { it.state == EvalState.Stable && it.rangeLowKcal != null && it.rangeHighKcal != null }?.let { it.rangeLowKcal!!..it.rangeHighKcal!! }
    RedesignCard {
        MetricCardHeader(
            title = "CALORIES & MACROS", latest = latest?.roundToInt()?.toString(), unit = "kcal",
            helpBody = "Daily calories against your estimated expenditure (the shaded TDEE range, once your logging and weight trend are enough to work it out), " +
                "with protein, carbs and fat in grams on the right-hand axis. The bar under the title is centred on your calorie target (Setup), or on this range's average if none is set.",
        )
        band?.let { BaselineShiftBand(it, latest, format = { v -> "%.0f".format(v) }, accent = NutrientColor.Calories) }
        DualAxisChart(
            left = listOf(AxisLine("Calories", aggregate(calories, range), NutrientColor.Calories)),
            right = listOf(
                AxisLine("Protein", aggregate(series(days) { it.proteinG }, range), NutrientColor.Protein),
                AxisLine("Carbs", aggregate(series(days) { it.carbsG }, range), NutrientColor.Carbs),
                AxisLine("Fat", aggregate(series(days) { it.fatG }, range), NutrientColor.Fat),
            ),
            leftBand = tdeeBand, bandLabel = "TDEE RANGE", leftUnit = "kcal", rightUnit = "g",
        )
    }
}

@Composable
private fun MacrosPerKgCard(overview: NutritionOverview, weights: List<Pair<LocalDate, Double>>, range: TimeRange) {
    val protein = aggregate(perKg(overview.allDays, weights) { it.proteinG }, range)
    val carbs = aggregate(perKg(overview.allDays, weights) { it.carbsG }, range)
    val fat = aggregate(perKg(overview.allDays, weights) { it.fatG }, range)
    val dates = (protein + carbs + fat).map { it.first }.distinct().sorted()
    val pm = protein.toMap()
    val cm = carbs.toMap()
    val fm = fat.toMap()
    RedesignCard {
        MetricCardHeader(
            title = "MACROS PER KG", latest = null,
            helpBody = "Grams of protein, carbs and fat per kilogram of your body weight, each day stacked in one column. The pills say whether each macro " +
                "is growing or shrinking across the range (second half against first half, beyond 5 %).",
        )
        if (dates.size < 2) {
            Text("Needs logged days and a weigh-in in this range.", style = FTType.Caption, color = FT.TextMuted)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("PROTEIN" to protein, "CARBS" to carbs, "FAT" to fat).forEach { (name, s) ->
                    macroMove(s.map { it.second })?.let { FTStatePill(StateWord(MetricState.Neutral, "$name ${moveWord(it)}")) }
                }
            }
            StackedColumns(
                columns = dates.map { StackedColumn(it, listOf(pm[it] ?: 0.0, cm[it] ?: 0.0, fm[it] ?: 0.0)) },
                colors = listOf(NutrientColor.Protein, NutrientColor.Carbs, NutrientColor.Fat),
                labels = listOf("Protein", "Carbs", "Fat"), unit = "g / kg",
            )
        }
    }
}

private fun moveWord(m: MacroMove) = when (m) {
    MacroMove.Growing -> "▲ GROWING"
    MacroMove.Shrinking -> "▼ SHRINKING"
    MacroMove.Steady -> "● STEADY"
}

@Composable
private fun ProteinPerMealCard(overview: NutritionOverview, range: TimeRange) {
    val byDay = proteinByMeal(overview.meals)
    val slots = byDay.values.maxOfOrNull { it.size }?.coerceAtMost(6) ?: 0
    val perSlot = (0 until slots).map { i -> aggregate(byDay.mapNotNull { (d, l) -> l.getOrNull(i)?.let { d to it } }, range).toMap() }
    val dates = perSlot.flatMap { it.keys }.distinct().sorted()
    RedesignCard {
        MetricCardHeader(
            title = "PROTEIN PER MEAL", latest = null,
            helpBody = "Each column is a day (or the average of a week or month), split by the meals eaten in order. Even segments mean protein is spread across the day; " +
                "a few large segments mean it is bunched into one or two meals. Meals beyond the sixth are left out.",
        )
        if (dates.size < 2) {
            Text("Not enough logged meals in this range yet.", style = FTType.Caption, color = FT.TextMuted)
        } else {
            StackedColumns(
                columns = dates.map { d -> StackedColumn(d, perSlot.map { it[d] ?: 0.0 }) },
                colors = (0 until slots).map { NutrientColor.Protein.copy(alpha = 1f - it * 0.13f) },
                labels = (1..slots).map { "Meal $it" }, unit = "g protein",
            )
        }
    }
}

@Composable
private fun MicrosCard(range: TimeRange, onOpen: () -> Unit) {
    var lowest by remember { mutableStateOf<List<Pair<String, Double>>?>(null) }
    LaunchedEffect(range) {
        lowest = runCatching {
            val data = NutrientBreakdownRepository(SupabaseClientProvider.client).loadNutrients((range.days ?: 365).coerceAtMost(365).toInt())
            NUTRIENT_REFERENCE.values
                .filter { (it.category == NutrientCategory.Vitamin || it.category == NutrientCategory.Mineral) && it.rda != null }
                .mapNotNull { info -> data.nutrients[info.key]?.let { info.displayName to (it / data.daysCovered / info.rda!!) * 100.0 } }
                .sortedBy { it.second }.take(8)
        }.getOrNull()
    }
    RedesignCard {
        MetricCardHeader(
            title = "MICRONUTRIENTS", latest = null,
            helpBody = "The eight vitamins and minerals furthest below their reference intake, as a daily average over this range (100 % = the adult reference intake, " +
                "a general guide, not a personal target). The full list is one tap away.",
        )
        lowest?.forEach { (name, pct) -> FTScoreRow("$name · ${pct.roundToInt()}%", pct.coerceAtMost(100.0), NutrientColor.Carbs) }
        if (lowest != null && lowest!!.isEmpty()) Text("No micronutrient data logged in this range.", style = FTType.Caption, color = FT.TextMuted)
        FTButton("FULL MICRONUTRIENT BREAKDOWN", emphasis = FTButtonEmphasis.Primary, modifier = Modifier.fillMaxWidth(), onClick = onOpen)
    }
}

@Composable
internal fun HydrationCharts(overview: NutritionOverview, range: TimeRange = GlobalTimeRange.selected) {
    val water = overview.hydrationHistory.mapNotNull { r -> r.ml?.let { LocalDate.parse(r.date) to it / 1000.0 } }
    val goals = NutritionGoalsStore.getGoals(LocalContext.current)
    TrendBaselineCard(
        "HYDRATION", water, "L", color = NutrientColor.Water,
        info = "Water logged each day, in litres. Drinks logged as meals count towards today's total on Today but are not in this history yet.",
    )
    val bounds = hydrationBoundsMl(goals, 0.0)
    RedesignCard {
        MetricCardHeader(
            title = "HYDRATION ACCOUNTING", latest = null,
            helpBody = "Daily water intake against your daily range (Setup; a generic 2.5 to 3.5 L if none is set). Hydration is a range, not a single goal. " +
                "Demand that rises with training and heat is worked out for today only so far; a per-day demand band is planned. Intake under or over the range is not a dehydration test.",
        )
        DualAxisChart(
            left = listOf(AxisLine("Water", aggregate(water, range), NutrientColor.Water)),
            right = emptyList(),
            leftBand = (bounds.start / 1000.0)..(bounds.endInclusive / 1000.0), bandLabel = "DAILY RANGE", leftUnit = "L", rightUnit = "",
        )
    }
}
