package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.bioscan.fieldterminal.data.BodyCheckinRepository
import com.bioscan.fieldterminal.data.UserProfileRepository
import com.bioscan.fieldterminal.data.model.UserProfileRow
import com.bioscan.fieldterminal.domain.BodyCheckinRecord
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import com.bioscan.fieldterminal.domain.gateStateWord
import com.bioscan.fieldterminal.domain.proteinPercentSeries
import com.bioscan.fieldterminal.domain.trendStateWord
import com.bioscan.fieldterminal.ui.components.DateTrendLine
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTDataChip
import com.bioscan.fieldterminal.ui.components.FTDataState
import com.bioscan.fieldterminal.ui.components.FTMetricRow
import com.bioscan.fieldterminal.ui.components.FTMetricValue
import com.bioscan.fieldterminal.ui.components.FTSegment
import com.bioscan.fieldterminal.ui.components.FTSegmentBar
import com.bioscan.fieldterminal.ui.components.FTStatePill
import com.bioscan.fieldterminal.ui.components.LoadErrorCard
import com.bioscan.fieldterminal.ui.components.PinnedPage
import com.bioscan.fieldterminal.ui.components.SubTabRow
import com.bioscan.fieldterminal.ui.components.rememberLoadGuard
import java.time.OffsetDateTime
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

// App restructure: Health › BODY (was Fuel › BODY): weight trend, the monthly body check-in, body fat, energy balance and
// digestion. Protein on target moved to Nutrition. It loads its own nutrition overview (energy balance needs the logged
// days) and stool log, so the Health tile's shared load stays what its other tabs need.
@Composable
fun BodyTabContent(openCheckIn: Boolean = false, onCheckInHandled: () -> Unit = {}) {
    var overview by remember { mutableStateOf<NutritionOverview?>(null) }
    var stool by remember { mutableStateOf<List<StoolAnalysisRow>?>(null) }
    var checkInOpen by remember { mutableStateOf(false) }
    // Bumped after a check-in is saved so the measurements card reads the new one.
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(openCheckIn) {
        if (openCheckIn) {
            checkInOpen = true
            onCheckInHandled()
        }
    }
    // A failed read leaves energy balance on its building state and digestion out, rather than failing the tab.
    LaunchedEffect(Unit) {
        overview = runCatching { NutritionRepository(SupabaseClientProvider.client).loadOverview() }.getOrNull()
        stool = runCatching { AnalysisRepository(SupabaseClientProvider.client).loadStoolLog() }.getOrNull()
    }

    BodyTab(allDays = overview?.allDays ?: emptyList(), reloadKey = reload, onStartCheckIn = { checkInOpen = true })
    stool?.let { rows ->
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            val stoolEntries = rows.mapNotNull { row -> row.bristolType?.let { OffsetDateTime.parse(row.occurredAt).toLocalDateTime().toLocalDate() to it } }
            BristolCard(evaluateBristol(stoolEntries), stoolEntries)
        }
    }

    if (checkInOpen) {
        BodyCheckInSheet(
            onDismiss = { checkInOpen = false },
            onSaved = {
                checkInOpen = false
                reload++
            },
        )
    }
}

// DAV-100: frequency (real logged-entry count) and a longitudinal trend of
// bristol type (1-7) alongside the existing 14-day pattern breakdown --
// a single reading is nearly meaningless per evaluateBristol's own header
// comment, the shape across days is the signal.
@Composable
private fun BristolCard(eval: BristolEvaluation, entries: List<Pair<LocalDate, Int>>) {
    FTCard(dataQuality = eval.confidence, title = "DIGESTIVE PATTERN (BRISTOL)", info = "Descriptive pattern only — not a diagnostic tool.") {
        FTMetricRow("Frequency (14d)", "${eval.confidence.have} entries logged")
        eval.pattern?.let { FTMetricRow("Pattern", bristolPatternLabel(it)) }
        // Bristol type is an ordinal category (1-7), so the shape across days is
        // shown as a hard / normal / loose distribution, not a continuous line.
        val hard = eval.pctHard
        val normal = eval.pctNormal
        val loose = eval.pctLoose
        if (hard != null && normal != null && loose != null) {
            FTSegmentBar(
                listOf(
                    FTSegment("Hard (1-2)", hard, FT.Category.Digestion.c700),
                    FTSegment("Normal (3-5)", normal, FT.Category.Digestion.c500),
                    FTSegment("Loose (6-7)", loose, FT.Category.Digestion.c300),
                ),
            )
        }
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
private fun BodyTab(allDays: List<DailyNutrition>, reloadKey: Int, onStartCheckIn: () -> Unit) {
    var bodyMetrics by remember { mutableStateOf<List<BodyMetricsAnalysisRow>?>(null) }
    // The monthly body check-in: its history, the newest height and the profile its estimates need. A failed read
    // leaves the card on its empty state rather than failing the tab.
    var checkins by remember { mutableStateOf<List<BodyCheckinRecord>>(emptyList()) }
    var heightCm by remember { mutableStateOf<Double?>(null) }
    var profile by remember { mutableStateOf<UserProfileRow?>(null) }
    val bodyGuard = rememberLoadGuard()

    LaunchedEffect(bodyGuard.attempt, reloadKey) {
        bodyGuard.run { bodyMetrics = AnalysisRepository(SupabaseClientProvider.client).loadBodyMetrics() }
        val repo = BodyCheckinRepository(SupabaseClientProvider.client)
        checkins = runCatching { repo.loadHistory() }.getOrElse { emptyList() }
        heightCm = runCatching { repo.latestHeightCm() }.getOrNull()
        profile = runCatching { UserProfileRepository(SupabaseClientProvider.client).loadProfile() }.getOrNull()
    }

    val b = bodyMetrics
    if (b == null && bodyGuard.error != null) {
        LoadErrorCard(bodyGuard)
        return
    } else if (b == null) {
        Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = FT.DomainHeart)
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

        BodyMeasurementsCard(
            history = checkins,
            heightCm = heightCm,
            sex = profile?.sex?.takeIf { it == "male" || it == "female" },
            ageYears = profile?.dateOfBirth?.let { runCatching { ChronoUnit.YEARS.between(LocalDate.parse(it), LocalDate.now()).toInt() }.getOrNull() },
            scaleBodyFat = b.lastOrNull { it.bodyFatPct != null }?.bodyFatPct,
            onStart = onStartCheckIn,
        )


        val bodyFatPoints = b.mapNotNull { row -> row.bodyFatPct?.let { LocalDate.parse(row.date) to it } }
        BodyFatCard(evaluateBodyFat(bodyFatPoints))

        // DAV-104: real post-fact TDEE, gated on both this evaluation's own
        // weight-EMA state above and Category 4's nutrition-completeness
        // gate -- reuses both verbatim rather than a third data pipeline.
        TdeeCard(evaluateTdee(evaluateNutrition(allDays), weightEval))
    }
}

private const val TREND_WINDOW_DAYS = 90L

@Composable
private fun WeightCard(eval: WeightEvaluation) {
    FTCard(dataQuality = eval.confidence, title = "WEIGHT TREND") {
        FTStatePill(trendStateWord(eval.state))
        eval.emaToday?.let { FTMetricRow("EMA (today)", "%.1f kg".format(it)) }
        eval.rateKgPerWeek?.let { FTMetricRow("Rate", "%+.2f kg/week".format(it)) }
        val today = LocalDate.now()
        val recentSeries = eval.emaSeries.filter { ChronoUnit.DAYS.between(it.first, today) <= TREND_WINDOW_DAYS }
        DateTrendLine(points = recentSeries, color = FT.DomainFuel, modifier = Modifier.padding(top = 6.dp), unit = "kg (EMA)")
    }
}

@Composable
private fun BodyFatCard(eval: BodyFatEvaluation) {
    FTCard(dataQuality = eval.confidence, title = "BODY FAT %") {
        FTStatePill(trendStateWord(eval.state))
        eval.latest?.let { FTMetricRow("Latest", "%.1f%%".format(it)) }
        eval.previous?.let { FTMetricRow("Previous (≥30d prior)", "%.1f%%".format(it)) }
        eval.delta?.let { FTMetricRow("Delta", "%+.1f pp".format(it)) }
    }
}

// DAV-104: post-fact adaptive TDEE. Building state is the honest, expected
// default while either input gate is unmet -- FTDataState (not a fabricated
// number or a decorative placeholder) makes that explicit, per the ticket's
// own "must not use glow, color, or large typography to imply precision
// the data gate does not support."
@Composable
private fun TdeeCard(eval: TdeeEvaluation) {
    FTCard(dataQuality = eval.confidence,
        title = "ENERGY BALANCE (TDEE ESTIMATE)",
        info = "Post-fact estimate from your own logged intake and real weight trend — not a predictive formula, and not a target to hit.",
    ) {
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
    }
}

