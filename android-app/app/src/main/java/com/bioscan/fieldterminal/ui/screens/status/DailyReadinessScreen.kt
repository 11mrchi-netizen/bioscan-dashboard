package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.NutritionOverview
import com.bioscan.fieldterminal.data.NutritionRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TrainingCyclesRepository
import com.bioscan.fieldterminal.data.ZeppStressRepository
import com.bioscan.fieldterminal.data.buildTrainingSessionLoads
import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.data.model.TrainingLoadSessionRow
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.data.model.WellbeingAnalysisRow
import com.bioscan.fieldterminal.domain.comparison.METRIC_DIRECTIONALITY
import com.bioscan.fieldterminal.domain.MetricCategory
import com.bioscan.fieldterminal.domain.PerformanceTimeframe
import com.bioscan.fieldterminal.domain.SleepNight
import com.bioscan.fieldterminal.domain.TrainingCycle
import com.bioscan.fieldterminal.domain.DisplayValue
import com.bioscan.fieldterminal.domain.computeDynamicRecovery
import com.bioscan.fieldterminal.domain.computeSleepIndex
import com.bioscan.fieldterminal.domain.computeTrainingReadiness
import com.bioscan.fieldterminal.domain.evaluateHrv
import com.bioscan.fieldterminal.domain.evaluateRhr
import com.bioscan.fieldterminal.domain.evaluateSubjective
import com.bioscan.fieldterminal.domain.evaluateTrainingLoad
import com.bioscan.fieldterminal.domain.resolveTier
import com.bioscan.fieldterminal.domain.stress.StressDay
import com.bioscan.fieldterminal.domain.stress.analyzeStressDay
import com.bioscan.fieldterminal.domain.trainingLoadSeries
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTMetricValue
import com.bioscan.fieldterminal.ui.components.HydrationSourceDonutChart
import com.bioscan.fieldterminal.ui.components.MacroDonutChart
import com.bioscan.fieldterminal.ui.components.TileHeader
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import kotlin.math.roundToInt

// Daily Readiness page: Training Load, Today's Readiness, a Fuel breakdown
// and Cardio Health in one place, reached from the condition bar on Status.
// Every section below reuses the exact domain chain + render card its own
// tile screen already uses (TrainingLoadSection/TrainingReadinessCard in
// TrainingTileScreen.kt, EvalCard in HeartTileScreen.kt, the hydration/
// macro totals in NutritionHydrationScreen.kt) -- this screen just composes
// them, loading its own data independently per this app's established
// per-screen-loader convention.
@Composable
fun DailyReadinessScreen(onBack: () -> Unit) {
    var sessions by remember { mutableStateOf<List<TrainingLoadSessionRow>?>(null) }
    var wearable by remember { mutableStateOf<List<WearableAnalysisRow>?>(null) }
    var sleep by remember { mutableStateOf<List<SleepAnalysisRow>?>(null) }
    var wellbeing by remember { mutableStateOf<List<WellbeingAnalysisRow>?>(null) }
    var stressDays by remember { mutableStateOf<List<StressDay>>(emptyList()) }
    var activeCycle by remember { mutableStateOf<TrainingCycle?>(null) }
    var cycleLoaded by remember { mutableStateOf(false) }
    var nutrition by remember { mutableStateOf<NutritionOverview?>(null) }

    LaunchedEffect(Unit) {
        val repo = AnalysisRepository(SupabaseClientProvider.client)
        sessions = repo.loadExerciseSessionsForTrainingLoad()
        wearable = repo.loadWearableDaily()
        sleep = repo.loadSleepDaily()
        wellbeing = repo.loadWellbeingDaily()
        stressDays = ZeppStressRepository(SupabaseClientProvider.client).loadStressDays()
        activeCycle = TrainingCyclesRepository(SupabaseClientProvider.client).loadActiveCycle()
        cycleLoaded = true
        nutrition = NutritionRepository(SupabaseClientProvider.client).loadOverview()
    }

    val s = sessions
    val w = wearable
    val sl = sleep
    val wb = wellbeing
    val nut = nutrition
    if (s == null || w == null || sl == null || wb == null || !cycleLoaded || nut == null) {
        Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
            TileHeader(onBack = onBack)
            Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = FT.Emerald)
            }
        }
        return
    }

    val sessionLoads = buildTrainingSessionLoads(s, w.lastOrNull()?.rhr)
    val trainingLoadEval = evaluateTrainingLoad(sessionLoads)

    val nights = sl.mapNotNull { row ->
        val bedtime = row.bedtime?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
        val wake = row.wakeTime?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
        if (bedtime != null && wake != null) SleepNight(LocalDate.parse(row.date), bedtime, wake) else null
    }
    val sleepIndexResult = computeSleepIndex(
        sl.mapNotNull { row -> row.hours?.let { LocalDate.parse(row.date) to it } },
        sl.mapNotNull { row -> row.deepMin?.let { LocalDate.parse(row.date) to it } },
        sl.mapNotNull { row -> row.respiratoryRate?.let { LocalDate.parse(row.date) to it } },
        nights,
    )
    val exerciseWindows = s.mapNotNull { session ->
        val start = runCatching { java.time.OffsetDateTime.parse(session.startTime) }.getOrNull()?.toInstant() ?: return@mapNotNull null
        val durationMin = session.durationMin ?: return@mapNotNull null
        start to start.plusSeconds((durationMin * 60).toLong())
    }
    val physiologicalStress = stressDays.firstOrNull()?.let { analyzeStressDay(it, stressDays.drop(1), exerciseWindows) }
    val hrvPoints = w.mapNotNull { row -> row.hrv?.let { LocalDate.parse(row.date) to it } }
    val rhrPoints = w.mapNotNull { row -> row.rhr?.let { LocalDate.parse(row.date) to it } }
    val recovery = computeDynamicRecovery(
        sleepIndex = sleepIndexResult,
        hrvEval = evaluateHrv(hrvPoints),
        rhrEval = evaluateRhr(rhrPoints),
        trainingLoadEval = trainingLoadEval,
        energyEval = evaluateSubjective(wb.mapNotNull { row -> row.energy?.let { LocalDate.parse(row.date) to it.toDouble() } }),
        stressEval = evaluateSubjective(wb.mapNotNull { row -> row.stress?.let { LocalDate.parse(row.date) to it.toDouble() } }),
        sorenessEval = evaluateSubjective(wb.mapNotNull { row -> row.soreness?.let { LocalDate.parse(row.date) to it.toDouble() } }),
        physiologicalStress = physiologicalStress,
        morningWoodEval = evaluateSubjective(wb.mapNotNull { row -> row.morningErectionQuality?.let { LocalDate.parse(row.date) to it.toDouble() } }),
        arousalEval = evaluateSubjective(wb.mapNotNull { row -> row.arousalLevel?.let { LocalDate.parse(row.date) to it.toDouble() } }),
    )

    val waterMl = nut.todayHydrationMl?.toDouble() ?: 0.0
    val beverageMl = nut.todayBeverageItems.mapNotNull { it.effectiveHydrationMl }.sum()
    val todayCaffeineMg = nut.todayBeverageItems.mapNotNull { it.caffeineMg }.takeIf { it.isNotEmpty() }?.sum()

    Column(modifier = Modifier.fillMaxSize().background(FT.Base).verticalScroll(rememberScrollState())) {
        TileHeader(onBack = onBack)
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            TrainingLoadCard(
                trainingLoadEval,
                resolveTier(MetricCategory.TrainingLoad, activeCycle),
                trainingLoadSeries(sessionLoads),
                PerformanceTimeframe.ThreeMonths,
            )
            TrainingReadinessCard(computeTrainingReadiness(recovery))

            // Stacked full-width, not side-by-side -- MacroDonutChart/
            // HydrationSourceDonutChart's fixed 110dp canvas + legend row is
            // sized for a full-width card (as NutritionAveragesCard already
            // uses them); a half-width column left no room for the legend
            // text, wrapping it one character per line. Fixing the layout
            // here rather than the shared components, which work correctly
            // everywhere else they're already used.
            FTCard(title = "CALORIES TODAY") {
                val today = nut.today
                if (today == null) {
                    Text("No meals logged yet.", style = FTType.BodySmall, color = FT.TextSecondary)
                } else {
                    FTMetricValue(DisplayValue(primary = today.calories.roundToInt().toString(), unit = "KCAL"))
                    MacroDonutChart(carbsG = today.carbsG, proteinG = today.proteinG, fatG = today.fatG, modifier = Modifier.padding(top = 10.dp))
                }
            }
            FTCard(title = "HYDRATION TODAY") {
                if (waterMl + beverageMl <= 0.0) {
                    Text("No hydration logged yet today.", style = FTType.BodySmall, color = FT.TextSecondary)
                } else {
                    FTMetricValue(DisplayValue(primary = "%.1f".format((waterMl + beverageMl) / 1000.0), unit = "L"))
                    HydrationSourceDonutChart(waterMl = waterMl, beverageMl = beverageMl, modifier = Modifier.padding(top = 10.dp))
                }
            }

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

            EvalCard("HRV", evaluateHrv(hrvPoints).toExpSpace(), "ms", directionality = METRIC_DIRECTIONALITY.getValue("hrv"), points = hrvPoints)
            EvalCard("RESTING HEART RATE", evaluateRhr(rhrPoints), "bpm", directionality = METRIC_DIRECTIONALITY.getValue("resting_heart_rate"), points = rhrPoints)
        }
    }
}
