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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableDoubleStateOf
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
import com.bioscan.fieldterminal.data.NutritionGoals
import com.bioscan.fieldterminal.data.NutritionGoalsStore
import com.bioscan.fieldterminal.data.NutritionOverview
import com.bioscan.fieldterminal.data.NutritionRepository
import com.bioscan.fieldterminal.data.GeminiApiKeyStore
import com.bioscan.fieldterminal.data.HydrationExplanationRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.hydrationBoundsMl
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
import com.bioscan.fieldterminal.ui.components.NutrientColor
import com.bioscan.fieldterminal.ui.components.FTDataChip
import com.bioscan.fieldterminal.ui.components.FTDataState
import com.bioscan.fieldterminal.ui.components.FTMetricRow
import com.bioscan.fieldterminal.ui.components.FTMetricValue
import com.bioscan.fieldterminal.ui.components.FTRangeIndicator
import com.bioscan.fieldterminal.ui.components.MacroDonutChart
import com.bioscan.fieldterminal.ui.components.PeriodToggle
import com.bioscan.fieldterminal.ui.components.ftClickable
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
@Composable
internal fun MealRowItem(meal: MealRow) {
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
    var todayExerciseMinutes by remember { mutableDoubleStateOf(0.0) }
    var todaySweat by remember { mutableStateOf<com.bioscan.fieldterminal.data.TodaySweat?>(null) }
    val homeContext = LocalContext.current
    LaunchedEffect(Unit) {
        val today = java.time.LocalDate.now()
        todayExerciseMinutes = AnalysisRepository(SupabaseClientProvider.client)
            .loadExerciseSessionsForTrainingLoad()
            .filter { runCatching { java.time.OffsetDateTime.parse(it.startTime).toLocalDate() == today }.getOrDefault(false) }
            .sumOf { it.durationMin ?: 0.0 }
        // Fuel planning phase 4: the person's sweat profile at today's temperature at home, as the Status FUEL preview uses.
        if (todayExerciseMinutes > 0) {
            todaySweat = com.bioscan.fieldterminal.data.todaySweatRate(SupabaseClientProvider.client, com.bioscan.fieldterminal.data.MapSettingsStore.getHome(homeContext))
        }
    }
    val hydrationIntelligence = computeHydrationIntelligence(
        measuredIntakeMl = overview.todayHydrationMl?.toDouble(),
        effectiveHydrationMl = overview.todayBeverageItems.mapNotNull { it.effectiveHydrationMl }.sum().takeIf { overview.todayBeverageItems.isNotEmpty() },
        exerciseDurationMinutesToday = todayExerciseMinutes,
        sweatRateMlPerHour = todaySweat?.mlPerHour,
        environmentKnown = todaySweat?.environmentKnown ?: false,
    )

    val context = LocalContext.current
    val geminiKey = remember { GeminiApiKeyStore.get(context) }
    var explanation by remember { mutableStateOf<String?>(null) }
    var explanationLoading by remember { mutableStateOf(false) }
    LaunchedEffect(hydrationIntelligence, caffeineWindow) {
        val key = geminiKey ?: return@LaunchedEffect
        explanationLoading = true
        explanation = try {
            HydrationExplanationRepository(key).explain(hydrationIntelligence, caffeineWindow)
        } catch (e: Exception) { null }
        explanationLoading = false
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        HydrationCharts(overview)

        HydrationIntelligenceCard(hydrationIntelligence)

        if (todayCaffeineMg != null) {
            FTCard(title = "CAFFEINE WINDOW") {
                FTMetricValue(DisplayValue(primary = "%.0f".format(todayCaffeineMg), unit = "MG TODAY"))
                if (caffeineWindow != null) {
                    Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FTMetricRow("Residual now", "~%.0f mg".format(caffeineWindow.residualNowMg))
                        caffeineWindow.residualAtBedtimeMg?.let { r ->
                            FTMetricRow("At 11pm", "~%.0f mg".format(r))
                        }
                        caffeineWindow.cutoffHour?.let { h ->
                            val label = if (h == 0) "Threshold already met" else "Last dose by %02d:00".format(h)
                            FTMetricRow("Cutoff", label)
                        }
                    }
                    Text(
                        "Half-life 5h model · 200 mg reference dose · not personalized yet",
                        style = FTType.Caption,
                        color = FT.TextMuted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    Text(
                        "Total from logged beverages",
                        style = FTType.Caption,
                        color = FT.TextMuted,
                    )
                }
            }
        }

        if (geminiKey != null) {
            FTCard(title = "AI HYDRATION SUMMARY") {
                if (explanationLoading) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(strokeWidth = 2.dp, color = FT.DomainFuel)
                    }
                } else {
                    explanation?.let { text ->
                        Text(text, style = FTType.BodySmall, color = FT.TextPrimary)
                    } ?: Text("Explanation unavailable.", style = FTType.BodySmall, color = FT.TextMuted)
                }
                Text(
                    "AI interpretation · validated data only · not medical advice",
                    style = FTType.Caption,
                    color = FT.TextMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun HydrationIntelligenceCard(result: HydrationIntelligenceResult) {
    FTCard(dataQuality = result.confidence, title = "HYDRATION INTELLIGENCE") {
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
                "Today's temperature isn't known (set a home location in Setup › Connected services) -- the estimate assumes a mild day.",
                style = FTType.Caption,
                color = FT.TextMuted,
            )
        }
    }
}

