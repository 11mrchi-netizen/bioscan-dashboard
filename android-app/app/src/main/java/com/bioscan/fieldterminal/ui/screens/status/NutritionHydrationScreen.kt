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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.NutritionGoals
import com.bioscan.fieldterminal.data.NutritionGoalsStore
import com.bioscan.fieldterminal.data.NutritionOverview
import com.bioscan.fieldterminal.data.NutritionRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.domain.CaffeineEvent
import com.bioscan.fieldterminal.domain.computeCaffeineWindow
import com.bioscan.fieldterminal.domain.computeHydrationIntelligence
import com.bioscan.fieldterminal.domain.HydrationIntelligenceResult
import java.time.LocalDateTime
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
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter
import com.bioscan.fieldterminal.ui.theme.RobotoMono
import com.bioscan.fieldterminal.ui.theme.RobotoMono
import com.bioscan.fieldterminal.ui.theme.Inter
import com.bioscan.fieldterminal.ui.theme.Inter
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
// The committed mockup (design/Field Terminal Mockups.dc.html, "Status ·
// Nutrition & hydration") shows every bar against a personal target
// (2750 kcal, 180g protein, 3.0L water) -- none of these exist anywhere in
// this project's real data (confirmed by grep; the web dashboard's own
// Kidneys/Hydration panel explicitly says so: "No stored personal target").
// Rather than invent numbers the mockup implies but nothing backs, this
// shows the same generic sanity-range bars the web dashboard actually uses
// (0-3500 kcal, 0-220g protein, 0-450g carbs, 0-180g fat, 0-4000ml water,
// same watch-thresholds) with one honest disclosure line instead of a fake
// "/2750" denominator.
// DAV-72 (First feedback fixes): split into separate public
// NutritionTabContent/HydrationTabContent -- the Fuel tile page now hosts
// Nutrition and Hydration as two switchable tabs sharing one
// NutritionOverview load, instead of one combined screen.
// DAV-100: calories headline -> macros -> meals logged -> 7-day trend,
// migrated onto the shared Futuristic Material primitives (DAV-105).
// RangeKind.ReferenceRange is used throughout (not PersonalRange/TargetRange)
// because these stay real, generic sanity ranges -- no personal calorie/
// macro target is stored anywhere in this project (see this file's own
// long-standing note above); ReferenceRange is the one range kind whose
// contract doesn't imply a personal history gate or an achieved goal.
@Composable
fun NutritionTabContent(overview: NutritionOverview, onOpenNutrientBreakdown: () -> Unit = {}) {
    val today = overview.today
    if (today == null) {
        Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
            Text("No meals logged yet.", style = TextStyle(fontFamily = Inter, fontSize = 15.5.sp), color = FT.TextSecondary)
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
            FTRangeIndicator(referenceRange("DAILY REFERENCE RANGE", today.calories, 0.0, 3500.0))
            Text(
                text = "${today.mealCount} MEAL${if (today.mealCount == 1) "" else "S"} LOGGED — ${today.date}",
                style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.14f.em),
                color = FT.TextSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        FTCard(title = "MACROS") {
            FTRangeIndicator(referenceRange("PROTEIN (g)", today.proteinG, 0.0, 220.0))
            FTRangeIndicator(referenceRange("CARBS (g)", today.carbsG, 0.0, 450.0))
            FTRangeIndicator(referenceRange("FAT (g)", today.fatG, 0.0, 180.0))
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

        Text(
            text = "Ranges shown are general reference points — no personal targets are stored anywhere in this project yet.",
            style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp),
            color = FT.TextMuted,
        )
    }
}

@Composable
private fun MealRowItem(meal: MealRow) {
    val time = runCatching { OffsetDateTime.parse(meal.loggedAt).format(DateTimeFormatter.ofPattern("HH:mm")) }.getOrDefault("—")
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(
                meal.description ?: "Meal",
                style = TextStyle(fontFamily = Inter, fontSize = 14.sp),
                color = FT.TextPrimary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(time, style = TextStyle(fontFamily = RobotoMono, fontSize = 11.sp), color = FT.TextMuted)
        }
        meal.calories?.let {
            Text(
                "${it.roundToInt()} kcal",
                style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Bold, fontSize = 13.sp),
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
            style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.14f.em),
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
    val caffeineWindow = run {
        val mealTimestamps = overview.todaysMeals.associate { it.id to it.loggedAt }
        val events = overview.todayBeverageItems.mapNotNull { item ->
            val caffeineMg = item.caffeineMg ?: return@mapNotNull null
            val loggedAtStr = mealTimestamps[item.mealId] ?: return@mapNotNull null
            val dt = runCatching {
                OffsetDateTime.parse(loggedAtStr).toLocalDateTime()
            }.getOrNull() ?: return@mapNotNull null
            CaffeineEvent(dt, caffeineMg, false)
        }
        if (events.isNotEmpty()) computeCaffeineWindow(events) else null
    }

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
                Text("No hydration logged yet today.", style = TextStyle(fontFamily = Inter, fontSize = 15.5.sp), color = FT.TextSecondary)
            } else {
                FTMetricValue(DisplayValue(primary = "%.1f".format(totalMl / 1000.0), unit = "L"))
                HydrationSegments(totalMl.toInt())

                // Breakdown by source when there are beverage entries alongside
                // plain water, so the user can see each contribution.
                if (overview.todayBeverageItems.isNotEmpty()) {
                    Column(modifier = Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        overview.todayHydrationMl?.let { ml ->
                            StatLine("Water", "${ml} ml")
                        }
                        overview.todayBeverageItems.forEach { item ->
                            val hydStr = item.effectiveHydrationMl?.let { "%.0f ml (est.)".format(it) } ?: "—"
                            StatLine(item.description ?: "Beverage", hydStr)
                        }
                    }
                }

                Text(
                    "4.0 L reference — no personal hydration target is stored anywhere in this project yet." +
                        if (overview.todayBeverageItems.isNotEmpty()) " Beverage effective-hydration values are modelled estimates." else "",
                    style = TextStyle(fontFamily = Inter, fontSize = 12.sp),
                    color = FT.TextMuted,
                )
            }
        }

        HydrationIntelligenceCard(hydrationIntelligence)

        if (todayCaffeineMg != null) {
            FTCard(title = "CAFFEINE WINDOW") {
                FTMetricValue(DisplayValue(primary = "%.0f".format(todayCaffeineMg), unit = "MG TODAY"))
                if (caffeineWindow != null) {
                    Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        StatLine("Residual now", "~%.0f mg".format(caffeineWindow.residualNowMg))
                        caffeineWindow.residualAtBedtimeMg?.let { r ->
                            StatLine("At 11pm", "~%.0f mg".format(r))
                        }
                        caffeineWindow.cutoffHour?.let { h ->
                            val label = if (h == 0) "Threshold already met" else "Last dose by %02d:00".format(h)
                            StatLine("Cutoff", label)
                        }
                    }
                    Text(
                        "Half-life 5h model · 200 mg reference dose · not personalized yet",
                        style = TextStyle(fontFamily = Inter, fontSize = 12.sp),
                        color = FT.TextMuted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    Text(
                        "Total from logged beverages",
                        style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp),
                        color = FT.TextMuted,
                    )
                }
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
        StatLine("Protein", "${(totals.proteinG / days).roundToInt()} g/day")
        StatLine("Carbs", "${(totals.carbsG / days).roundToInt()} g/day")
        StatLine("Fat", "${(totals.fatG / days).roundToInt()} g/day")
        StatLine("Fiber", "${(totals.fiberG / days).roundToInt()} g/day")
        StatLine("Sugar", "${(totals.sugarG / days).roundToInt()} g/day")
        StatLine("Sodium", "${(totals.sodiumMg / days).roundToInt()} mg/day")
        MacroDonutChart(
            carbsG = totals.carbsG / days,
            proteinG = totals.proteinG / days,
            fatG = totals.fatG / days,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            "daily average over the last ${period.label.lowercase()} — ${totals.dayCount} day${if (totals.dayCount == 1) "" else "s"} with logged meals",
            style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp),
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
            style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp),
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
    return PersonalRange(
        kind = RangeKind.TargetRange,
        lower = 0.0,
        upper = target,
        current = current,
        label = label,
        comparison = comparison,
        sufficientHistory = true,
    )
}

@Composable
// DAV-108: label is unweighted (always a short fixed phrase) so it never
// shrinks; value takes the rest of the row via weight(1f) so a long value
// wraps within its own bounded width and stays right-aligned instead of
// colliding with the label.
private fun StatLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = TextStyle(fontFamily = Inter, fontSize = 14.5.sp), color = FT.TextSecondary)
        Text(
            value,
            style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Medium, fontSize = 14.5.sp),
            color = FT.TextPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
    }
}

@Composable
private fun HydrationIntelligenceCard(result: HydrationIntelligenceResult) {
    FTCard(title = "HYDRATION INTELLIGENCE") {
        StatLine("Confidence", result.confidence.label)
        result.measuredIntakeMl?.let { StatLine("Measured intake", "%.0f ml".format(it)) }
        result.effectiveHydrationMl?.let { StatLine("Effective hydration (modeled)", "%.0f ml".format(it)) }
        if (result.inferredDemandMl != null) {
            StatLine("Inferred demand from today's exercise", "%.0f ml".format(result.inferredDemandMl))
        } else {
            Text(
                "No exercise logged today -- no additional demand inferred.",
                style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp),
                color = FT.TextMuted,
            )
        }
        if (!result.environmentalContextAvailable) {
            Text(
                "Environmental context (temperature/humidity) isn't available yet -- demand estimate is exercise-only.",
                style = TextStyle(fontFamily = Inter, fontSize = 12.sp),
                color = FT.TextMuted,
            )
        }
    }
}

@Composable
private fun HydrationSegments(ml: Int) {
    val segments = 8
    val genericTargetMl = 4000.0 // same generic reference the web dashboard's hydration panel uses
    val filledSegments = ((ml / genericTargetMl) * segments).roundToInt().coerceIn(0, segments)
    Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(segments) { i ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(26.dp)
                    .then(
                        if (i < filledSegments) Modifier.background(FT.Emerald)
                        else Modifier.border(1.dp, FT.GlassBorder),
                    ),
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
