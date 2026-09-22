package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.NutritionOverview
import com.bioscan.fieldterminal.data.NutritionRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.MealRow
import com.bioscan.fieldterminal.domain.DailyNutrition
import com.bioscan.fieldterminal.domain.DisplayValue
import com.bioscan.fieldterminal.domain.PersonalRange
import com.bioscan.fieldterminal.domain.RangeKind
import com.bioscan.fieldterminal.domain.TotalsPeriod
import com.bioscan.fieldterminal.domain.sumNutritionSince
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTMetricValue
import com.bioscan.fieldterminal.ui.components.FTRangeIndicator
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
fun NutritionTabContent(overview: NutritionOverview) {
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
        }

        if (overview.todaysMeals.isNotEmpty()) {
            FTCard(title = "MEALS TODAY") {
                overview.todaysMeals.forEach { meal -> MealRowItem(meal) }
            }
        }

        MacroTotalsCard(overview.allDays)

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

private fun referenceRange(label: String, current: Double, lower: Double, upper: Double) = PersonalRange(
    kind = RangeKind.ReferenceRange,
    lower = lower,
    upper = upper,
    current = current,
    label = label,
    sufficientHistory = true,
)

// DAV-100: liters headline + the existing segmented target/actual
// visualization, migrated onto FT tokens/typography.
@Composable
fun HydrationTabContent(overview: NutritionOverview) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        FTCard(title = "HYDRATION") {
            val ml = overview.todayHydrationMl
            if (ml == null) {
                Text("No hydration logged yet today.", style = TextStyle(fontFamily = Inter, fontSize = 15.5.sp), color = FT.TextSecondary)
            } else {
                FTMetricValue(DisplayValue(primary = "%.1f".format(ml / 1000.0), unit = "L"))
                HydrationSegments(ml)
                Text(
                    "4.0 L reference — no personal hydration target is stored anywhere in this project yet.",
                    style = TextStyle(fontFamily = Inter, fontSize = 12.sp),
                    color = FT.TextMuted,
                )
            }
        }
    }
}

// Calories + macros summed over a selectable window -- computed client-side
// from the same day-aggregated meals data the rest of this screen already
// fetched, using the existing sumNutritionSince(). No new query per period
// switch.
@Composable
private fun MacroTotalsCard(allDays: List<DailyNutrition>) {
    var period by remember { mutableStateOf(TotalsPeriod.Week) }
    val totals = sumNutritionSince(allDays, LocalDate.now(), period.days)

    FTCard(title = "NUTRITION TOTALS") {
        PeriodToggle(selected = period, onSelect = { period = it })
        FTMetricValue(DisplayValue(primary = totals.calories.roundToInt().toString(), unit = "KCAL"))
        StatLine("Protein", "${totals.proteinG.roundToInt()} g")
        StatLine("Carbs", "${totals.carbsG.roundToInt()} g")
        StatLine("Fat", "${totals.fatG.roundToInt()} g")
        StatLine("Fiber", "${totals.fiberG.roundToInt()} g")
        StatLine("Sugar", "${totals.sugarG.roundToInt()} g")
        StatLine("Sodium", "${totals.sodiumMg.roundToInt()} mg")
        Text(
            "over the last ${period.label.lowercase()} — ${totals.dayCount} day${if (totals.dayCount == 1) "" else "s"} with logged meals",
            style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp),
            color = FT.TextMuted,
        )
    }
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
