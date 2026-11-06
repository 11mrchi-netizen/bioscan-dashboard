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
