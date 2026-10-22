package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.NutritionGoalsStore
import com.bioscan.fieldterminal.data.NutritionOverview
import com.bioscan.fieldterminal.data.TodayFuel
import com.bioscan.fieldterminal.data.hydrationBoundsMl
import com.bioscan.fieldterminal.domain.DayProgressItem
import com.bioscan.fieldterminal.domain.MetricState
import com.bioscan.fieldterminal.domain.OVER_MARGIN
import com.bioscan.fieldterminal.domain.ProgressMark
import com.bioscan.fieldterminal.domain.progressItem
import com.bioscan.fieldterminal.domain.shortfalls
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.NutrientColor
import com.bioscan.fieldterminal.ui.components.drawZonedTrack
import com.bioscan.fieldterminal.ui.components.drawZonedValue
import com.bioscan.fieldterminal.ui.components.zoneEdges
import com.bioscan.fieldterminal.ui.components.zonedFraction
import com.bioscan.fieldterminal.ui.components.stateTreatment
import com.bioscan.fieldterminal.ui.screens.fuel.TodayFuelSummary
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalTime
import java.util.Locale
import kotlin.math.roundToInt

private const val NUTRITION_TODAY_INFO =
    "How close today is to its targets. Each bar is what you have logged against the day's target; the tick is what the " +
        "hour expects by now (nothing before 07:00, all of it by 21:00), so a morning is not short for having eaten a morning's worth."
private const val NUTRITION_TODAY_DETAILS =
    "ON TRACK: at least 80 % of what the hour expects is in. SHORT: behind that pace; the card lists what is still to go. " +
        "OVER: more than 10 % past the day's target (water: past the top of its range).\n\n" +
        "Targets come from today's fuel plan when there is one (its carbohydrate, protein, fat and energy for today's " +
        "training), otherwise from the nutrition goal in Setup. Water reads against the daily range in Setup."

// App restructure, fix 5: one card replacing CALORIES TODAY, HYDRATION TODAY and CAFFEINE TODAY, with today's fuel
// line on top and the meals logged underneath (MEALS TODAY, moved from Nutrition).
@Composable
fun NutritionTodayCard(nut: NutritionOverview, fuel: TodayFuel?, onOpenPlan: () -> Unit, modifier: Modifier = Modifier, now: LocalTime = LocalTime.now()) {
    val goals = NutritionGoalsStore.getGoals(LocalContext.current)
    val plan = fuel?.today
    val today = nut.today
    val waterMl = (nut.todayHydrationMl?.toDouble() ?: 0.0) + nut.todayBeverageItems.mapNotNull { it.effectiveHydrationMl }.sum()
    val water = hydrationBoundsMl(goals, 0.0)
    val caffeineMg = nut.todayBeverageItems.mapNotNull { it.caffeineMg }.takeIf { it.isNotEmpty() }?.sum()
    val items = listOf(
        progressItem("Calories", "kcal", today?.calories ?: 0.0, plan?.kcal ?: goals.caloriesKcal, now),
        progressItem("Protein", "g", today?.proteinG ?: 0.0, plan?.proteinG ?: goals.proteinG, now),
        progressItem("Carbs", "g", today?.carbsG ?: 0.0, plan?.carbG ?: goals.carbsG, now),
        progressItem("Fat", "g", today?.fatG ?: 0.0, plan?.fatG ?: goals.fatG, now),
        progressItem("Water", "ml", waterMl, water.start, now, upper = water.endInclusive),
    )
    val colors = listOf(NutrientColor.Calories, NutrientColor.Protein, NutrientColor.Carbs, NutrientColor.Fat, NutrientColor.Water)
    val uppers = listOf(null, null, null, null, water.endInclusive)

    FTCard(title = "NUTRITION TODAY", info = NUTRITION_TODAY_INFO, infoDetails = NUTRITION_TODAY_DETAILS, modifier = modifier) {
        fuel?.let { TodayFuelSummary(it, onOpen = onOpenPlan) }
        Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.forEachIndexed { i, item -> ProgressRow(item, colors[i], uppers[i]) }
        }
        val short = shortfalls(items)
        if (short.isNotEmpty()) {
            Text(
                short.joinToString(" · ") { "${it.label} ${amount(it.toGo ?: 0.0, it.unit)} to go" },
                style = FTType.BodySmall, color = FT.TextPrimary, modifier = Modifier.padding(top = 4.dp),
            )
        }
        caffeineMg?.let { Text("Caffeine %.0f mg from logged drinks".format(it), style = FTType.Caption, color = FT.TextSecondary) }
        if (nut.todaysMeals.isNotEmpty()) {
            Text("MEALS TODAY", style = FTType.LabelCaps, color = FT.TextMuted, modifier = Modifier.padding(top = 6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { nut.todaysMeals.forEach { MealRowItem(it) } }
        } else {
            Text("No meals logged yet today.", style = FTType.Caption, color = FT.TextSecondary)
        }
    }
}

// Label and amount over a bar of the day's target, the hour's expected share as a tick, the mark as a word. The bar uses
// the nutrition layout (ZonedScale.kt): the target to the OVER line (the water range's top, else target x OVER_MARGIN)
// is the band at 70-90 %; the fill is the nutrient's colour, the word keeps the state colour.
@Composable
private fun ProgressRow(item: DayProgressItem, color: Color, upper: Double?) {
    val state = when (item.mark) {
        ProgressMark.ON_TRACK -> MetricState.Optimal
        ProgressMark.SHORT, ProgressMark.OVER -> MetricState.Warning
        ProgressMark.NO_TARGET -> MetricState.Neutral
    }
    val treatment = stateTreatment(state)
    val target = item.target
    Column(
        Modifier.fillMaxWidth().semantics {
            contentDescription = "${item.label} ${amount(item.have, item.unit)}" + (target?.let { " of ${amount(it, item.unit)}" } ?: "") + ", ${item.mark.word.lowercase()}"
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(item.label, style = FTType.Label, color = FT.TextSecondary, modifier = Modifier.weight(1f))
            Text(
                amount(item.have, item.unit) + (target?.let { " / ${amount(it, item.unit)}" } ?: ""),
                style = FTType.MonoCaption, color = FT.TextPrimary,
            )
            Text("${treatment.symbol} ${item.mark.word}", style = FTType.Label, color = treatment.color, modifier = Modifier.padding(start = 10.dp))
        }
        if (target != null) {
            val top = upper ?: (target * OVER_MARGIN)
            val edges = zoneEdges(target, top)
            if (edges != null) Canvas(Modifier.fillMaxWidth().height(12.dp)) {
                val y = size.height / 2f
                val bar = 8.dp.toPx()
                drawZonedTrack(y, bar, edges, color)
                drawZonedValue(y, bar, zonedFraction(item.have, target, top) ?: 0f, color, 4.dp.toPx())
                // The hour's expected share: a thin tick across the bar.
                item.expectedNow?.let { e ->
                    val x = (zonedFraction(e, target, top) ?: 0f) * size.width
                    drawLine(FT.TextPrimary.copy(alpha = 0.8f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                }
            }
        }
    }
}

private fun amount(v: Double, unit: String): String = when (unit) {
    "ml" -> "%.1f L".format(Locale.ENGLISH, v / 1000.0)
    "kcal" -> "%,d kcal".format(Locale.ENGLISH, v.roundToInt())
    else -> "${v.roundToInt()} $unit"
}
