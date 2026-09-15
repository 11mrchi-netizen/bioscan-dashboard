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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.NutritionOverview
import com.bioscan.fieldterminal.data.NutritionRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.DailyNutrition
import com.bioscan.fieldterminal.ui.components.Card
import com.bioscan.fieldterminal.ui.components.RangeBar
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles
import com.bioscan.fieldterminal.ui.theme.JetBrainsMono
import com.bioscan.fieldterminal.ui.theme.Saira
import com.bioscan.fieldterminal.ui.theme.SairaCondensed
import kotlin.math.roundToInt

// Step 6 (Phase C). Real data from `meals`/`hydration_daily`. "Today" means
// the most recent day with any logged data, matching index.html's own
// convention (nutrition.cal[length-1]) -- see ROADMAP.md P8 Step 6.
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
@Composable
fun NutritionHydrationScreen() {
    var overview by remember { mutableStateOf<NutritionOverview?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        overview = NutritionRepository(SupabaseClientProvider.client).loadOverview()
        isLoading = false
    }

    when {
        isLoading -> Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = FieldColors.Amber)
        }
        overview?.today == null -> Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
            Text("No meals logged yet.", style = FieldTextStyles.placeholderBody, color = FieldColors.InkMuted)
        }
        else -> NutritionContent(overview!!)
    }
}

@Composable
private fun NutritionContent(overview: NutritionOverview) {
    val today = overview.today!!
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // Calories headline
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = today.calories.roundToInt().toString(),
                    style = TextStyle(fontFamily = SairaCondensed, fontWeight = FontWeight.Bold, fontSize = 40.sp),
                    color = FieldColors.Amber,
                )
                Text(
                    text = " KCAL",
                    style = FieldTextStyles.headerContext,
                    color = FieldColors.InkMuted,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            RangeBar(value = today.calories, max = 3500.0, watchBelow = 1800.0, color = FieldColors.Amber)
            Text(
                text = "${today.mealCount} MEAL${if (today.mealCount == 1) "" else "S"} LOGGED — ${today.date}",
                style = FieldTextStyles.tabBarLabel,
                color = FieldColors.InkMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // Macros card
        Card(title = "MACROS") {
            MacroRow("Protein", today.proteinG, max = 220.0, color = FieldColors.Green)
            MacroRow("Carbs", today.carbsG, max = 450.0, color = FieldColors.Cyan)
            MacroRow("Fat", today.fatG, max = 180.0, color = FieldColors.Amber)
        }

        // Hydration card
        Card(title = "HYDRATION") {
            val ml = overview.todayHydrationMl
            if (ml == null) {
                Text("No hydration logged yet.", style = FieldTextStyles.placeholderBody, color = FieldColors.InkMuted)
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "%.1f".format(ml / 1000.0),
                        style = TextStyle(fontFamily = SairaCondensed, fontWeight = FontWeight.Bold, fontSize = 26.sp),
                        color = FieldColors.Ink,
                    )
                    Text(" L", style = FieldTextStyles.headerContext, color = FieldColors.InkMuted, modifier = Modifier.padding(bottom = 3.dp))
                }
                HydrationSegments(ml)
            }
        }

        // 7-day trend -- relative to the week's own max, not a fixed target
        // (matches the "TRAIN tile" histogram motif already used in the
        // Status launch-screen mockups).
        if (overview.last7Days.size > 1) {
            Card(title = "7-DAY CALORIE TREND") {
                CalorieTrendBars(overview.last7Days)
            }
        }

        Text(
            text = "Ranges shown are general reference points — no personal targets are stored anywhere in this project yet.",
            style = TextStyle(fontFamily = Saira, fontSize = 11.sp),
            color = FieldColors.InkMuted,
        )
    }
}

@Composable
private fun MacroRow(label: String, valueG: Double, max: Double, color: androidx.compose.ui.graphics.Color) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = TextStyle(fontFamily = Saira, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp), color = FieldColors.Ink)
            Text(
                "${valueG.roundToInt()} g",
                style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 13.sp),
                color = FieldColors.Ink,
            )
        }
        RangeBar(value = valueG, max = max, watchBelow = null, color = color, height = 6.dp, topPadding = 6.dp)
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
                        if (i < filledSegments) Modifier.background(FieldColors.Cyan)
                        else Modifier.border(1.dp, FieldColors.Hairline),
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
                        .background(FieldColors.Amber.copy(alpha = 0.7f)),
                )
            }
        }
    }
}
