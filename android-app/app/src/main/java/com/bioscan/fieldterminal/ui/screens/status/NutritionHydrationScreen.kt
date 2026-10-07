package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.DEFAULT_HYDRATION_MAX_ML
import com.bioscan.fieldterminal.data.DEFAULT_HYDRATION_MIN_ML
import com.bioscan.fieldterminal.data.NutritionGoals
import com.bioscan.fieldterminal.data.NutritionGoalsStore
import com.bioscan.fieldterminal.data.NutritionOverview
import com.bioscan.fieldterminal.data.NutritionRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.domain.computeHydrationIntelligence
import com.bioscan.fieldterminal.domain.HydrationIntelligenceResult
import com.bioscan.fieldterminal.data.model.MealRow
import com.bioscan.fieldterminal.domain.DailyNutrition
import com.bioscan.fieldterminal.domain.DataAvailability
import com.bioscan.fieldterminal.domain.DisplayValue
import com.bioscan.fieldterminal.domain.PersonalRange
import com.bioscan.fieldterminal.domain.RangeComparison
import com.bioscan.fieldterminal.domain.RangeKind
import com.bioscan.fieldterminal.domain.TotalsPeriod
import com.bioscan.fieldterminal.domain.sumNutritionSince
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTDataState
import com.bioscan.fieldterminal.ui.components.FTMetricValue
import com.bioscan.fieldterminal.ui.components.FTRangeIndicator
import com.bioscan.fieldterminal.ui.components.MacroDonutChart
import com.bioscan.fieldterminal.ui.components.PeriodToggle
import com.bioscan.fieldterminal.ui.components.FTMetricRow
import com.bioscan.fieldterminal.ui.components.FTConfidenceChip
import com.bioscan.fieldterminal.ui.components.confidenceLevel
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

// Step 6 (Phase C). Real data from `meals`/`hydration_daily`. "Today" means
// the real calendar-today's totals -- null (rendered as "No meals logged
// yet.") when nothing's been logged yet today, not silently carrying over
// yesterday's full totals (the original "most recent day with data"
// convention, ported from index.html's nutrition.cal[length-1], was a real
// on-device bug: calories/macros never appeared to reset at the new day).
//
// Calories and macros are shown against the targets saved in Settings
// (NutritionGoalsStore, RangeKind.TargetRange); only when a target isn't set
// does a bar fall back to a generic RangeKind.ReferenceRange (0-3500 kcal,
// 0-220g protein, 0-450g carbs, 0-180g fat), labelled REFERENCE so it never
// reads as a personal goal. Hydration is a min-max range (set in Settings,
// documented default otherwise), never a single hard-coded volume.
// DAV-72 (First feedback fixes): split into separate public
// NutritionTabContent/HydrationTabContent -- the Fuel tile page now hosts
// Nutrition and Hydration as two switchable tabs sharing one
// NutritionOverview load, instead of one combined screen.
// DAV-100: calories headline -> macros -> meals logged -> 7-day trend,
// migrated onto the shared Futuristic Material primitives (DAV-105).
@Composable
fun NutritionTabContent(overview: NutritionOverview, onOpenNutrientBreakdown: () -> Unit = {}) {
    val goals = NutritionGoalsStore.getGoals(LocalContext.current)
    val today = overview.today
    if (today == null) {
        Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
            Text("No meals logged yet.", style = FTType.Body, color = FT.TextSecondary)
        }
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // Calories headline
        Column {
            FTMetricValue(DisplayValue(primary = today.calories.roundToInt().toString(), unit = "KCAL"))
            FTRangeIndicator(dailyRange("CALORIES", today.calories, goals.caloriesKcal, 3500.0))
            Text(
                text = "${today.mealCount} MEAL${if (today.mealCount == 1) "" else "S"} LOGGED — ${today.date}",
                style = FTType.LabelCaps,
                color = FT.TextSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        FTCard(title = "MACROS") {
            FTRangeIndicator(dailyRange("PROTEIN (G)", today.proteinG, goals.proteinG, 220.0))
            FTRangeIndicator(dailyRange("CARBS (G)", today.carbsG, goals.carbsG, 450.0))
            FTRangeIndicator(dailyRange("FAT (G)", today.fatG, goals.fatG, 180.0))
            NutrientBreakdownButton(onClick = onOpenNutrientBreakdown)
        }

        if (overview.todaysMeals.isNotEmpty()) {
            FTCard(title = "MEALS TODAY") {
                overview.todaysMeals.forEach { meal -> MealRowItem(meal) }
            }
        }

        NutritionAveragesCard(overview.allDays)
        NutritionGoalAdherenceCard(overview.allDays)

        // 7-day trend -- relative to the week's own max, not a fixed target
        // (matches the "TRAIN tile" histogram motif already used in the
        // Status launch-screen mockups).
        if (overview.last7Days.size > 1) {
            FTCard(title = "7-DAY CALORIE TREND") {
                CalorieTrendBars(overview.last7Days)
            }
        }
    }
}

@Composable
private fun MealRowItem(meal: MealRow) {
    val time = runCatching { OffsetDateTime.parse(meal.loggedAt).format(DateTimeFormatter.ofPattern("HH:mm")) }.getOrDefault("—")
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(
                meal.description ?: "Meal",
                style = FTType.BodySmall,
                color = FT.TextPrimary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(time, style = FTType.MonoCaption, color = FT.TextMuted)
        }
        meal.calories?.let {
            Text(
                "${it.roundToInt()} kcal",
                style = FTType.Telemetry,
                color = FT.TextSecondary,
            )
        }
    }
}

@Composable
private fun NutrientBreakdownButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(FT.RadiusSmall)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(FT.BorderWidth, FT.DomainFuel.copy(alpha = 0.5f), shape)
            .background(FT.DomainFuel.copy(alpha = 0.08f), shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "VIEW DETAILED NUTRIENT BREAKDOWN",
            style = FTType.LabelCaps,
            color = FT.DomainFuel,
        )
    }
}

private fun referenceRange(label: String, current: Double, lower: Double, upper: Double) = PersonalRange(
    kind = RangeKind.ReferenceRange,
    lower = lower,
    upper = upper,
    current = current,
    label = label,
    sufficientHistory = true,
)

// Saved target when one exists, generic reference range otherwise -- the label
// says which, so a reference band is never mistaken for a personal goal.
private fun dailyRange(label: String, current: Double, target: Double?, referenceUpper: Double) =
    if (target != null) targetRange("$label · TARGET", current, target)
    else referenceRange("$label · REFERENCE", current, 0.0, referenceUpper)

private fun hydrationRange(totalMl: Double, goals: NutritionGoals, exerciseDemandMl: Double): PersonalRange {
    val userSet = goals.hasHydrationRange
    val lower = (goals.hydrationMinMl ?: DEFAULT_HYDRATION_MIN_ML) + exerciseDemandMl
    val upper = (goals.hydrationMaxMl ?: DEFAULT_HYDRATION_MAX_ML) + exerciseDemandMl
    val comparison = when {
        totalMl < lower -> RangeComparison.Below
        totalMl > upper -> RangeComparison.Above
        else -> RangeComparison.Within
    }
    return PersonalRange(
        kind = if (userSet) RangeKind.TargetRange else RangeKind.ReferenceRange,
        lower = lower,
        upper = upper,
        current = totalMl,
        label = if (userSet) "WATER (ML) · TARGET" else "WATER (ML) · REFERENCE",
        comparison = comparison,
        sufficientHistory = true,
    )
}

// DAV-100 / DAV-181: liters headline combining hydration_daily (plain water)
// + effective_hydration_ml from beverage meal_items. Each source is shown
// in a breakdown so the user can see where the total comes from.
@Composable
fun HydrationTabContent(overview: NutritionOverview) {
    val waterMl = overview.todayHydrationMl?.toDouble() ?: 0.0
    val beverageMl = overview.todayBeverageItems.mapNotNull { it.effectiveHydrationMl }.sum()
    val totalMl = waterMl + beverageMl
    val hasAnyHydration = overview.todayHydrationMl != null || overview.todayBeverageItems.isNotEmpty()
    val todayCaffeineMg = overview.todayBeverageItems.mapNotNull { it.caffeineMg }.takeIf { it.isNotEmpty() }?.sum()

    // 09.5 Hydration Intelligence: this tab's own small independent load
    // (today's exercise duration only), same "each subtab loads its own
    // data" convention TrainingTileScreen.kt's InjuryTab already follows.
    var todayExerciseMinutes by remember { mutableStateOf(0.0) }
    LaunchedEffect(Unit) {
        val today = java.time.LocalDate.now()
        todayExerciseMinutes = AnalysisRepository(SupabaseClientProvider.client)
            .loadExerciseSessionsForTrainingLoad()
            .filter { runCatching { java.time.OffsetDateTime.parse(it.startTime).toLocalDate() == today }.getOrDefault(false) }
            .sumOf { it.durationMin ?: 0.0 }
    }
    val hydrationIntelligence = computeHydrationIntelligence(
        measuredIntakeMl = overview.todayHydrationMl?.toDouble(),
        effectiveHydrationMl = overview.todayBeverageItems.mapNotNull { it.effectiveHydrationMl }.sum().takeIf { overview.todayBeverageItems.isNotEmpty() },
        exerciseDurationMinutesToday = todayExerciseMinutes,
    )

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        FTCard(title = "HYDRATION") {
            if (!hasAnyHydration) {
                Text("No hydration logged yet today.", style = FTType.Body, color = FT.TextSecondary)
            } else {
                FTMetricValue(DisplayValue(primary = "%.1f".format(totalMl / 1000.0), unit = "L"))
                FTRangeIndicator(hydrationRange(totalMl, NutritionGoalsStore.getGoals(LocalContext.current), hydrationIntelligence.inferredDemandMl ?: 0.0))

                // Breakdown by source when there are beverage entries alongside
                // plain water, so the user can see each contribution.
                if (overview.todayBeverageItems.isNotEmpty()) {
                    Column(modifier = Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        overview.todayHydrationMl?.let { ml ->
                            FTMetricRow("Water", "${ml} ml")
                        }
                        overview.todayBeverageItems.forEach { item ->
                            val hydStr = item.effectiveHydrationMl?.let { "%.0f ml (est.)".format(it) } ?: "—"
                            FTMetricRow(item.description ?: "Beverage", hydStr)
                        }
                    }
                }

                if (overview.todayBeverageItems.isNotEmpty()) {
                    Text(
                        "Beverage effective-hydration values are modelled estimates.",
                        style = FTType.Caption,
                        color = FT.TextMuted,
                    )
                }
            }
        }

        HydrationIntelligenceCard(hydrationIntelligence)

        if (todayCaffeineMg != null) {
            FTCard(title = "CAFFEINE TODAY") {
                FTMetricValue(DisplayValue(primary = "%.0f".format(todayCaffeineMg), unit = "MG"))
                Text(
                    "Total from logged beverages",
                    style = FTType.Caption,
                    color = FT.TextMuted,
                )
            }
        }
    }
}

// DAV-287: daily averages, not period totals -- summed over a selectable
// window via the existing sumNutritionSince(), then divided by dayCount
// (already tracked) so the headline reads as "per day" regardless of window
// length. Raw totals stay available inside NutritionTotals for anything that
// still wants a sum; this card just doesn't show them anymore.
@Composable
private fun NutritionAveragesCard(allDays: List<DailyNutrition>) {
    var period by remember { mutableStateOf(TotalsPeriod.Week) }
    val totals = sumNutritionSince(allDays, LocalDate.now(), period.days)
    val days = totals.dayCount.coerceAtLeast(1)

    FTCard(title = "NUTRITION AVERAGES") {
        PeriodToggle(selected = period, onSelect = { period = it })
        FTMetricValue(DisplayValue(primary = (totals.calories / days).roundToInt().toString(), unit = "KCAL/DAY"))
        FTMetricRow("Fiber", "${(totals.fiberG / days).roundToInt()} g/day")
        FTMetricRow("Sugar", "${(totals.sugarG / days).roundToInt()} g/day")
        FTMetricRow("Sodium", "${(totals.sodiumMg / days).roundToInt()} mg/day")
        MacroDonutChart(
            carbsG = totals.carbsG / days,
            proteinG = totals.proteinG / days,
            fatG = totals.fatG / days,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            "daily average over the last ${period.label.lowercase()} — ${totals.dayCount} day${if (totals.dayCount == 1) "" else "s"} with logged meals",
            style = FTType.Caption,
            color = FT.TextMuted,
        )
    }
}

// DAV-288: adherence against the goals saved in Settings (NutritionGoalsStore)
// -- reuses FTRangeIndicator/PersonalRange exactly as the reference-range bars
// above do, just with RangeKind.TargetRange instead of ReferenceRange (an
// enum case that existed but nothing used yet). Same 7-day window as the
// averages card's default so "today's adherence" reads as a real week, not a
// single noisy day.
@Composable
private fun NutritionGoalAdherenceCard(allDays: List<DailyNutrition>) {
    val context = LocalContext.current
    val goals = remember { NutritionGoalsStore.getGoals(context) }
    if (!goals.isSet) {
        FTCard(title = "GOAL ADHERENCE") {
            FTDataState(DataAvailability.Unavailable, "No nutrition goals set yet — add them in Setup to see adherence here.")
        }
        return
    }

    val totals = sumNutritionSince(allDays, LocalDate.now(), TotalsPeriod.Week.days)
    val days = totals.dayCount.coerceAtLeast(1)

    FTCard(title = "GOAL ADHERENCE") {
        goals.caloriesKcal?.let { FTRangeIndicator(targetRange("CALORIES (KCAL/DAY)", totals.calories / days, it)) }
        goals.proteinG?.let { FTRangeIndicator(targetRange("PROTEIN (G/DAY)", totals.proteinG / days, it)) }
        goals.carbsG?.let { FTRangeIndicator(targetRange("CARBS (G/DAY)", totals.carbsG / days, it)) }
        goals.fatG?.let { FTRangeIndicator(targetRange("FAT (G/DAY)", totals.fatG / days, it)) }
        Text(
            "7-day daily average vs. your saved targets",
            style = FTType.Caption,
            color = FT.TextMuted,
        )
    }
}

private fun targetRange(label: String, current: Double, target: Double): PersonalRange {
    val comparison = when {
        current < target * 0.9 -> RangeComparison.Below
        current > target * 1.1 -> RangeComparison.Above
        else -> RangeComparison.Within
    }
    // The band is the same +/-10% window the Below/Above comparison uses, so
    // the shaded range and the label always agree.
    return PersonalRange(
        kind = RangeKind.TargetRange,
        lower = target * 0.9,
        upper = target * 1.1,
        current = current,
        label = label,
        comparison = comparison,
        sufficientHistory = true,
    )
}

@Composable
private fun HydrationIntelligenceCard(result: HydrationIntelligenceResult) {
    FTCard(title = "HYDRATION INTELLIGENCE") {
        FTConfidenceChip(confidenceLevel(result.confidence))
        result.measuredIntakeMl?.let { FTMetricRow("Measured intake", "%.0f ml".format(it)) }
        result.effectiveHydrationMl?.let { FTMetricRow("Effective hydration (modeled)", "%.0f ml".format(it)) }
        if (result.inferredDemandMl != null) {
            FTMetricRow("Inferred demand from today's exercise", "%.0f ml".format(result.inferredDemandMl))
        } else {
            Text(
                "No exercise logged today -- no additional demand inferred.",
                style = FTType.Caption,
                color = FT.TextMuted,
            )
        }
        if (!result.environmentalContextAvailable) {
            Text(
                "Environmental context (temperature/humidity) isn't available yet -- demand estimate is exercise-only.",
                style = FTType.Caption,
                color = FT.TextMuted,
            )
        }
    }
}

@Composable
private fun CalorieTrendBars(days: List<DailyNutrition>) {
    val maxCal = days.maxOf { it.calories }.coerceAtLeast(1.0)
    Row(modifier = Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        days.forEach { day ->
            val fraction = (day.calories / maxCal).toFloat().coerceIn(0.05f, 1f)
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((40 * fraction).dp)
                        .background(FT.Emerald.copy(alpha = 0.7f)),
                )
            }
        }
    }
}
