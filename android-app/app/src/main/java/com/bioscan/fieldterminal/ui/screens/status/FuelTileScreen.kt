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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.NutritionOverview
import com.bioscan.fieldterminal.data.NutritionRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.BodyMetricsAnalysisRow
import com.bioscan.fieldterminal.data.model.StoolAnalysisRow
import com.bioscan.fieldterminal.domain.BodyFatEvaluation
import com.bioscan.fieldterminal.domain.BristolEvaluation
import com.bioscan.fieldterminal.domain.BristolPattern
import com.bioscan.fieldterminal.domain.DailyNutrition
import com.bioscan.fieldterminal.domain.DisplayValue
import com.bioscan.fieldterminal.domain.EvalState
import com.bioscan.fieldterminal.domain.MetricState
import com.bioscan.fieldterminal.domain.NutritionEvaluation
import com.bioscan.fieldterminal.domain.PROTEIN_PCT_HIGH
import com.bioscan.fieldterminal.domain.PROTEIN_PCT_LOW
import com.bioscan.fieldterminal.domain.TdeeEvaluation
import com.bioscan.fieldterminal.domain.WeightEvaluation
import com.bioscan.fieldterminal.domain.evaluateBodyFat
import com.bioscan.fieldterminal.domain.evaluateBristol
import com.bioscan.fieldterminal.domain.evaluateNutrition
import com.bioscan.fieldterminal.domain.evaluateTdee
import com.bioscan.fieldterminal.domain.evaluateWeightTrend
import com.bioscan.fieldterminal.domain.proteinPercentSeries
import java.time.OffsetDateTime
import com.bioscan.fieldterminal.ui.components.DateTrendLine
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTDataState
import com.bioscan.fieldterminal.ui.components.FTMetricValue
import com.bioscan.fieldterminal.ui.components.FTStatePill
import com.bioscan.fieldterminal.ui.components.SubTabRow
import com.bioscan.fieldterminal.ui.components.TileHeader
import com.bioscan.fieldterminal.ui.nav.FuelTab
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter
import com.bioscan.fieldterminal.ui.theme.RobotoMono
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

// DAV-72 (First feedback fixes): Fuel tile page, tabs Nutrition/Hydration/
// Supplements/Weight-TDEE. Nutrition+Hydration migrated from the old
// combined NutritionHydrationScreen.kt (split into NutritionTabContent/
// HydrationTabContent there); Supplements reuses SupplementsScreen()
// untouched; Weight/TDEE folds in Category 5's Weight + Body Fat evaluations
// (previously stranded on the old Analysis sub-tab). No real TDEE
// calculation exists anywhere in this project yet (Category 10 is still
// backlog per ROADMAP.md) -- this tab shows the real weight/body-fat trend
// data that does exist rather than fabricate a TDEE number.
@Composable
fun FuelTileScreen(onBack: () -> Unit) {
    var tab by remember { mutableStateOf(FuelTab.Nutrition) }
    var overview by remember { mutableStateOf<NutritionOverview?>(null) }
    var stool by remember { mutableStateOf<List<StoolAnalysisRow>?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        overview = NutritionRepository(SupabaseClientProvider.client).loadOverview()
        stool = AnalysisRepository(SupabaseClientProvider.client).loadStoolLog()
        isLoading = false
    }

    Column(modifier = Modifier.fillMaxSize().background(FieldColors.Ground).verticalScroll(rememberScrollState())) {
        TileHeader(title = "FUEL", context = "NUTRITION · HYDRATION · SUPPLEMENTS · DIGESTION · BODY", onBack = onBack)
        SubTabRow(items = FuelTab.entries, selected = tab, label = { it.label }, onSelect = { tab = it })

        when {
            isLoading && tab != FuelTab.Supplements && tab != FuelTab.Body -> Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = FieldColors.Amber)
            }
            else -> when (tab) {
                FuelTab.Nutrition -> overview?.let {
                    NutritionTabContent(it)
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 16.dp)) {
                        NutritionCard(evaluateNutrition(it.allDays))
                    }
                }
                FuelTab.Hydration -> overview?.let { HydrationTabContent(it) }
                FuelTab.Supplements -> SupplementsScreen()
                // DAV-96/100: relocated from Heart's old Stool tab -- frequency
                // and longitudinal pattern, descriptive only, no food->stool
                // causal link implied or computed (room is left for a future
                // correlation analysis once enough nutrition+digestion history
                // exists, per the ticket's own framing -- not built here).
                FuelTab.Digestion -> stool?.let { rows ->
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        val stoolEntries = rows.mapNotNull { row -> row.bristolType?.let { OffsetDateTime.parse(row.occurredAt).toLocalDateTime().toLocalDate() to it } }
                        BristolCard(evaluateBristol(stoolEntries), stoolEntries)
                    }
                }
                FuelTab.Body -> BodyTab(allDays = overview?.allDays ?: emptyList())
            }
        }
    }
}

// DAV-100: frequency (real logged-entry count) and a longitudinal trend of
// bristol type (1-7) alongside the existing 14-day pattern breakdown --
// a single reading is nearly meaningless per evaluateBristol's own header
// comment, the shape across days is the signal.
@Composable
private fun BristolCard(eval: BristolEvaluation, entries: List<Pair<LocalDate, Int>>) {
    FTCard(title = "DIGESTIVE PATTERN (BRISTOL)") {
        StatLine("Frequency (14d)", "${eval.confidence.have} entries logged")
        StatLine("Confidence", eval.confidence.label)
        eval.pattern?.let { StatLine("Pattern", bristolPatternLabel(it)) }
        eval.pctHard?.let { StatLine("Hard (types 1-2)", "%.0f%%".format(it)) }
        eval.pctNormal?.let { StatLine("Normal (types 3-5)", "%.0f%%".format(it)) }
        eval.pctLoose?.let { StatLine("Loose (types 6-7)", "%.0f%%".format(it)) }
        if (entries.size >= 2) {
            DateTrendLine(
                points = entries.map { (date, type) -> date to type.toDouble() },
                color = FT.Emerald,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Text(
            "Descriptive pattern only — not a diagnostic tool.",
            style = TextStyle(fontFamily = Inter, fontSize = 12.sp),
            color = FT.TextMuted,
        )
    }
}

private fun bristolPatternLabel(p: BristolPattern): String = when (p) {
    BristolPattern.PredominantlyFirm -> "PREDOMINANTLY FIRM"
    BristolPattern.PredominantlyLoose -> "PREDOMINANTLY LOOSE"
    BristolPattern.Mixed -> "MIXED PATTERN"
    BristolPattern.Typical -> "TYPICAL PATTERN"
}

// DAV-96/103: renamed from WeightTdeeTab to match the Body tab it now lives
// under -- weight/body-fat trend content is unchanged; DAV-104's real TDEE
// estimate replaces the old "not built yet" placeholder.
@Composable
private fun BodyTab(allDays: List<DailyNutrition>) {
    var bodyMetrics by remember { mutableStateOf<List<BodyMetricsAnalysisRow>?>(null) }

    LaunchedEffect(Unit) {
        bodyMetrics = AnalysisRepository(SupabaseClientProvider.client).loadBodyMetrics()
    }

    val b = bodyMetrics
    if (b == null) {
        Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = FieldColors.Amber)
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        val weightPoints = b.mapNotNull { row -> row.weightKg?.let { LocalDate.parse(row.date) to it } }
        val weightEval = evaluateWeightTrend(weightPoints)
        WeightCard(weightEval)

        // DAV-76: read alongside weight rather than as a disconnected number
        // -- no numeric calorie/macro goal exists anywhere in this project
        // (see NutritionEvaluation.kt's own note on that), so "on-target"
        // here is the one real target this project already has: the AMDR
        // protein band evaluateNutrition() already gates proteinAdherence14d
        // on, shown day-by-day instead of rolled into one percentage.
        NutritionOnTargetCard(proteinPercentSeries(allDays))

        val bodyFatPoints = b.mapNotNull { row -> row.bodyFatPct?.let { LocalDate.parse(row.date) to it } }
        BodyFatCard(evaluateBodyFat(bodyFatPoints))

        // DAV-104: real post-fact TDEE, gated on both this evaluation's own
        // weight-EMA state above and Category 4's nutrition-completeness
        // gate -- reuses both verbatim rather than a third data pipeline.
        TdeeCard(evaluateTdee(evaluateNutrition(allDays), weightEval))
    }
}

private const val TREND_WINDOW_DAYS = 90L

// DAV-101/103's toMetricState() equivalent for this file -- kept local
// rather than shared since only this screen's cards use the legacy 6-state
// EvalState vocabulary now that Nutrition/Hydration/Digestion moved to
// MetricPresentation-native states in DAV-100.
private fun EvalState.toMetricState(): MetricState = when (this) {
    EvalState.NoData -> MetricState.Unavailable
    EvalState.Building -> MetricState.Building
    EvalState.Stable -> MetricState.Optimal
    EvalState.ShiftUp, EvalState.ShiftDown -> MetricState.Warning
    EvalState.Unstable -> MetricState.Critical
}

@Composable
private fun WeightCard(eval: WeightEvaluation) {
    FTCard(title = "WEIGHT TREND") {
        FTStatePill(eval.state.toMetricState())
        StatLine("Confidence", eval.confidence.label)
        eval.emaToday?.let { StatLine("EMA (today)", "%.1f kg".format(it)) }
        eval.rateKgPerWeek?.let { StatLine("Rate", "%+.2f kg/week".format(it)) }
        val today = LocalDate.now()
        val recentSeries = eval.emaSeries.filter { ChronoUnit.DAYS.between(it.first, today) <= TREND_WINDOW_DAYS }
        DateTrendLine(points = recentSeries, color = FT.Emerald, modifier = Modifier.padding(top = 6.dp))
    }
}

// DAV-76. Green ("on-target here") already means "in range" everywhere else
// in this app (BLOODWORK STABLE, 0 FLAGS), not a color picked fresh for
// this card.
@Composable
private fun NutritionOnTargetCard(proteinSeries: List<Pair<LocalDate, Double>>) {
    FTCard(title = "PROTEIN ON-TARGET (AMDR 10–35%)") {
        if (proteinSeries.size < 2) {
            Text(
                "Not enough complete-day nutrition logs yet to chart a trend.",
                style = TextStyle(fontFamily = Inter, fontSize = 13.sp),
                color = FT.TextSecondary,
            )
        } else {
            DateTrendLine(
                points = proteinSeries,
                color = FT.Emerald,
                refLow = PROTEIN_PCT_LOW * 100,
                refHigh = PROTEIN_PCT_HIGH * 100,
            )
        }
    }
}

// Phase A4 (Category 4). No SHIFT_UP/SHIFT_DOWN here -- the spec never
// defines a threshold for calling an energy-trend move a "shift" the way it
// does for Categories 1/2/3/5/6, so `Stable` is shown once the gate is met,
// not because nothing moves but because there's no rule to judge it by.
@Composable
private fun NutritionCard(eval: NutritionEvaluation) {
    FTCard(title = "NUTRITION (ANALYSIS)") {
        FTStatePill(eval.state.toMetricState())
        StatLine("Confidence", eval.confidence.label)
        eval.energyTrend14d?.let { StatLine("14-day energy trend", "%.0f kcal".format(it)) }
        eval.energyCv28d?.let { StatLine("28-day energy CV", "%.1f%%".format(it)) }
        eval.proteinAdherence14d?.let { StatLine("Protein in AMDR band (10-35% kcal)", "%.0f%%".format(it)) }
    }
}

@Composable
private fun BodyFatCard(eval: BodyFatEvaluation) {
    FTCard(title = "BODY FAT %") {
        FTStatePill(eval.state.toMetricState())
        StatLine("Confidence", eval.confidence.label)
        eval.latest?.let { StatLine("Latest", "%.1f%%".format(it)) }
        eval.previous?.let { StatLine("Previous (≥30d prior)", "%.1f%%".format(it)) }
        eval.delta?.let { StatLine("Delta", "%+.1f pp".format(it)) }
    }
}

// DAV-104: post-fact adaptive TDEE. Building state is the honest, expected
// default while either input gate is unmet -- FTDataState (not a fabricated
// number or a decorative placeholder) makes that explicit, per the ticket's
// own "must not use glow, color, or large typography to imply precision
// the data gate does not support."
@Composable
private fun TdeeCard(eval: TdeeEvaluation) {
    FTCard(title = "ENERGY BALANCE (TDEE ESTIMATE)") {
        StatLine("Confidence", eval.confidence.label)
        if (eval.state == EvalState.Stable && eval.tdeeKcal != null && eval.rangeLowKcal != null && eval.rangeHighKcal != null) {
            FTMetricValue(
                DisplayValue(
                    primary = eval.tdeeKcal.roundToInt().toString(),
                    unit = "KCAL/DAY",
                    secondary = "likely ${eval.rangeLowKcal.roundToInt()}–${eval.rangeHighKcal.roundToInt()} kcal/day",
                ),
            )
        } else {
            FTDataState(
                com.bioscan.fieldterminal.domain.DataAvailability.Building,
                "Needs 10+ complete-logged days in the last 14 and an established weight trend (10+ weigh-ins across 14+ days) before an estimate is trustworthy.",
            )
        }
        Text(
            "Post-fact estimate from your own logged intake and real weight trend — not a predictive formula, and not a target to hit.",
            style = TextStyle(fontFamily = Inter, fontSize = 12.sp),
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
            style = TextStyle(fontFamily = RobotoMono, fontSize = 14.5.sp),
            color = FT.TextPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
    }
}
