package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TrainingCyclesRepository
import com.bioscan.fieldterminal.data.model.BodyMetricsAnalysisRow
import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.data.model.TrainingLoadSessionRow
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.data.model.WellbeingAnalysisRow
import com.bioscan.fieldterminal.domain.BodyFatEvaluation
import com.bioscan.fieldterminal.domain.DailyNutrition
import com.bioscan.fieldterminal.domain.DeloadCadenceFlag
import com.bioscan.fieldterminal.domain.EvalState
import com.bioscan.fieldterminal.domain.ExpectationTier
import com.bioscan.fieldterminal.domain.MetricCategory
import com.bioscan.fieldterminal.domain.NutritionEvaluation
import com.bioscan.fieldterminal.domain.RespiratoryAnomalyEvaluation
import com.bioscan.fieldterminal.domain.RestCadenceEvaluation
import com.bioscan.fieldterminal.domain.SleepNight
import com.bioscan.fieldterminal.domain.SriEvaluation
import com.bioscan.fieldterminal.domain.SubjectiveEvaluation
import com.bioscan.fieldterminal.domain.SwcEvaluation
import com.bioscan.fieldterminal.domain.TrainingCycle
import com.bioscan.fieldterminal.domain.TrainingLoadEvaluation
import com.bioscan.fieldterminal.domain.WeightEvaluation
import com.bioscan.fieldterminal.domain.evaluateBodyFat
import com.bioscan.fieldterminal.domain.evaluateHrv
import com.bioscan.fieldterminal.domain.evaluateNutrition
import com.bioscan.fieldterminal.domain.evaluateRespiratoryAnomaly
import com.bioscan.fieldterminal.domain.evaluateRestCadence
import com.bioscan.fieldterminal.domain.evaluateRhr
import com.bioscan.fieldterminal.domain.evaluateSleepDuration
import com.bioscan.fieldterminal.domain.evaluateSri
import com.bioscan.fieldterminal.domain.evaluateSubjective
import com.bioscan.fieldterminal.domain.evaluateTrainingLoad
import com.bioscan.fieldterminal.domain.evaluateWeightTrend
import com.bioscan.fieldterminal.domain.expValue
import com.bioscan.fieldterminal.domain.resolveTier
import com.bioscan.fieldterminal.ui.components.Card
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles
import com.bioscan.fieldterminal.ui.theme.JetBrainsMono
import com.bioscan.fieldterminal.ui.theme.Saira
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

// Phase A2 (Analysis Layer, see ROADMAP.md). First real implementation of
// the Evaluation Method Spec's Categories 1 (HRV/RHR), 2 (Sleep), and 5
// (Body composition) -- five per-stream evaluations, each independently
// resolving to one of the spec's six states via its own chosen formula and
// gate. Deliberately raw/unstyled, same precedent as Phase G4's first pass:
// proving these real, cited statistical methods compute correctly against
// real (often sparse) data is this phase's job; charts/granular-vs-trend
// views are Phase A5. No composite score anywhere here -- five separate
// cards, never blended into one number, matching this project's own
// existing "no composite health index" precedent (domain/Readiness.kt).
@Composable
fun AnalysisScreen() {
    var wearable by remember { mutableStateOf<List<WearableAnalysisRow>?>(null) }
    var sleep by remember { mutableStateOf<List<SleepAnalysisRow>?>(null) }
    var bodyMetrics by remember { mutableStateOf<List<BodyMetricsAnalysisRow>?>(null) }
    var trainingSessions by remember { mutableStateOf<List<TrainingLoadSessionRow>?>(null) }
    var wellbeing by remember { mutableStateOf<List<WellbeingAnalysisRow>?>(null) }
    var nutrition by remember { mutableStateOf<List<DailyNutrition>?>(null) }
    var activeCycle by remember { mutableStateOf<TrainingCycle?>(null) }
    var cycleLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val repo = AnalysisRepository(SupabaseClientProvider.client)
        wearable = repo.loadWearableDaily()
        sleep = repo.loadSleepDaily()
        bodyMetrics = repo.loadBodyMetrics()
        trainingSessions = repo.loadExerciseSessionsForTrainingLoad()
        wellbeing = repo.loadWellbeingDaily()
        nutrition = repo.loadDailyNutrition()
        activeCycle = TrainingCyclesRepository(SupabaseClientProvider.client).loadActiveCycle()
        cycleLoaded = true
    }

    val w = wearable
    val s = sleep
    val b = bodyMetrics
    val t = trainingSessions
    val wb = wellbeing
    val n = nutrition
    if (w == null || s == null || b == null || t == null || wb == null || n == null || !cycleLoaded) {
        Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = FieldColors.Amber)
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = "PER-STREAM EVALUATION · WITHIN-PERSON ONLY",
            style = FieldTextStyles.headerContext,
            color = FieldColors.InkMuted,
        )

        val hrvPoints = w.mapNotNull { row -> row.hrv?.let { LocalDate.parse(row.date) to it } }
        EvalCard("HRV", evaluateHrv(hrvPoints).toExpSpace(), "ms")

        val rhrPoints = w.mapNotNull { row -> row.rhr?.let { LocalDate.parse(row.date) to it } }
        EvalCard("RESTING HEART RATE", evaluateRhr(rhrPoints), "bpm")

        val hoursPoints = s.mapNotNull { row -> row.hours?.let { LocalDate.parse(row.date) to it } }
        EvalCard("SLEEP DURATION", evaluateSleepDuration(hoursPoints), "h")

        val nights = s.mapNotNull { row ->
            val bedtime = row.bedtime?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val wake = row.wakeTime?.let { runCatching { Instant.parse(it) }.getOrNull() }
            if (bedtime != null && wake != null) SleepNight(LocalDate.parse(row.date), bedtime, wake) else null
        }
        SriCard(evaluateSri(nights))

        val rrPoints = s.mapNotNull { row -> row.respiratoryRate?.let { LocalDate.parse(row.date) to it } }
        RespiratoryCard(evaluateRespiratoryAnomaly(rrPoints))

        val weightPoints = b.mapNotNull { row -> row.weightKg?.let { LocalDate.parse(row.date) to it } }
        WeightCard(evaluateWeightTrend(weightPoints))

        val bodyFatPoints = b.mapNotNull { row -> row.bodyFatPct?.let { LocalDate.parse(row.date) to it } }
        BodyFatCard(evaluateBodyFat(bodyFatPoints))

        val sessionLoads = t.mapNotNull { row ->
            val duration = row.durationMin
            val rpe = row.rpe
            if (duration != null && rpe != null) {
                OffsetDateTime.parse(row.startTime).toLocalDateTime().toLocalDate() to duration * rpe
            } else {
                null
            }
        }
        val trainingLoadEval = evaluateTrainingLoad(sessionLoads)
        TrainingLoadCard(trainingLoadEval, resolveTier(MetricCategory.TrainingLoad, activeCycle))

        RestCadenceCard(evaluateRestCadence(sessionLoads, trainingLoadEval.tsb, trainingLoadEval.confidence.met))

        SubjectiveCard("ENERGY", wb.mapNotNull { row -> row.energy?.let { LocalDate.parse(row.date) to it.toDouble() } })
        SubjectiveCard("MOOD", wb.mapNotNull { row -> row.mood?.let { LocalDate.parse(row.date) to it.toDouble() } })
        SubjectiveCard("STRESS", wb.mapNotNull { row -> row.stress?.let { LocalDate.parse(row.date) to it.toDouble() } })
        SubjectiveCard("SORENESS", wb.mapNotNull { row -> row.soreness?.let { LocalDate.parse(row.date) to it.toDouble() } })

        NutritionCard(evaluateNutrition(n))
    }
}

// HRV's baseline7d/mean60d come back in ln-space (see evaluateHrv) --
// exponentiated here, at the display boundary, so the domain layer itself
// never has to know it's being displayed in "ms."
private fun SwcEvaluation.toExpSpace(): SwcEvaluation =
    copy(baseline7d = expValue(baseline7d), mean60d = expValue(mean60d))

@Composable
private fun EvalCard(title: String, eval: SwcEvaluation, unit: String) {
    Card(title = title) {
        StateRow(eval.state)
        StatLine("Confidence", eval.confidence.label)
        eval.baseline7d?.let { StatLine("7-day baseline", "%.1f %s".format(it, unit)) }
        eval.mean60d?.let { StatLine("60-day mean", "%.1f %s".format(it, unit)) }
        eval.swcPct?.let { StatLine("SWC band", "±%.1f%%".format(it)) }
        eval.cv7d?.let { StatLine("7-day CV", "%.1f%%".format(it)) }
    }
}

@Composable
private fun SriCard(eval: SriEvaluation) {
    Card(title = "SLEEP REGULARITY (SRI)") {
        StatLine("Confidence", eval.confidence.label)
        if (eval.value != null) {
            StatLine("SRI", "%.0f / 100".format(eval.value))
        } else {
            Text(
                "Not enough consecutive nights yet (gaps over 2 nights reset the count).",
                style = TextStyle(fontFamily = Saira, fontSize = 13.sp),
                color = FieldColors.InkMuted,
            )
        }
    }
}

@Composable
private fun RespiratoryCard(eval: RespiratoryAnomalyEvaluation) {
    Card(title = "RESPIRATORY RATE") {
        StatLine("Confidence", eval.confidence.label)
        eval.baseline?.let { StatLine("14-night baseline", "%.1f breaths/min".format(it)) }
        Text(
            if (eval.flagged) {
                "Physiological anomaly flagged — 2 consecutive nights outside your baseline ±2 SD. Not a diagnosis."
            } else {
                "No anomaly flagged."
            },
            style = TextStyle(fontFamily = Saira, fontSize = 13.sp),
            color = if (eval.flagged) FieldColors.Alert else FieldColors.InkMuted,
        )
    }
}

@Composable
private fun WeightCard(eval: WeightEvaluation) {
    Card(title = "WEIGHT TREND") {
        StateRow(eval.state)
        StatLine("Confidence", eval.confidence.label)
        eval.emaToday?.let { StatLine("EMA (today)", "%.1f kg".format(it)) }
        eval.rateKgPerWeek?.let { StatLine("Rate", "%+.2f kg/week".format(it)) }
    }
}

@Composable
private fun BodyFatCard(eval: BodyFatEvaluation) {
    Card(title = "BODY FAT %") {
        StateRow(eval.state)
        StatLine("Confidence", eval.confidence.label)
        eval.latest?.let { StatLine("Latest", "%.1f%%".format(it)) }
        eval.previous?.let { StatLine("Previous (≥30d prior)", "%.1f%%".format(it)) }
        eval.delta?.let { StatLine("Delta", "%+.1f pp".format(it)) }
    }
}

// Phase A4. TSB's own descriptive band (Freshened/Neutral/Loaded/...) is
// the real label here, not the generic ABOVE/BELOW-YOUR-BAND wording
// StateRow uses for the SWC-based categories -- TSB is a TrainingPeaks
// convention, not a personal-baseline band, so it gets its own row.
@Composable
private fun TrainingLoadCard(eval: TrainingLoadEvaluation, tier: ExpectationTier?) {
    Card(title = "TRAINING LOAD (CTL/ATL/TSB)") {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("State", style = TextStyle(fontFamily = Saira, fontSize = 14.5.sp), color = FieldColors.InkMuted)
            Text(
                eval.tsbBand?.uppercase() ?: stateLabel(eval.state),
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 14.5.sp),
                color = stateColor(eval.state),
            )
        }
        StatLine("Confidence", eval.confidence.label)
        eval.ctl?.let { StatLine("CTL (fitness)", "%.1f".format(it)) }
        eval.atl?.let { StatLine("ATL (fatigue)", "%.1f".format(it)) }
        eval.tsb?.let { StatLine("TSB (form)", "%+.1f".format(it)) }
        // Phase A3: a pure re-label of the state above, never a recomputation
        // -- only rendered when a training cycle is actually active. No
        // active cycle means no framing applies, not "unmanaged."
        tier?.let { StatLine("This cycle", tierLabel(it)) }
        Text(
            "Grade-adjusted pace / Efficiency Factor not built yet — needs per-point route " +
                "elevation data this app doesn't persist for logged sessions.",
            style = TextStyle(fontFamily = Saira, fontSize = 12.sp),
            color = FieldColors.InkMuted,
        )
    }
}

// Phase A4 (Category 7). Two independent signals, never combined into one
// state -- Signal A (acute, gated on Category 6's own TSB gate) and Signal B
// (deload cadence, its own 8-week gate) are shown as separate lines, per the
// spec's own "these are two different questions" framing. No StateRow here:
// this category was never given the six-state vocabulary in the spec, only
// a flagged/not-flagged signal and a cadence classification.
@Composable
private fun RestCadenceCard(eval: RestCadenceEvaluation) {
    Card(title = "REST CADENCE") {
        StatLine("Days without rest", "${eval.consecutiveDaysWithoutRest}")
        Text(
            if (!eval.gateAMet) {
                "Signal A (acute): needs Category 6's own 42-day TSB gate first."
            } else if (eval.signalAFlagged) {
                "Signal A (acute): flagged — 9+ days without rest and TSB below -20."
            } else {
                "Signal A (acute): not flagged."
            },
            style = TextStyle(fontFamily = Saira, fontSize = 13.sp),
            color = if (eval.signalAFlagged) FieldColors.Alert else FieldColors.InkMuted,
        )
        StatLine("Deload cadence", deloadCadenceLabel(eval.deloadCadenceFlag))
        eval.weeksSinceDeload?.let { StatLine("Weeks since deload", "$it") }
    }
}

private fun deloadCadenceLabel(flag: DeloadCadenceFlag): String = when (flag) {
    DeloadCadenceFlag.InsufficientHistory -> "NEEDS 8 WEEKS OF LOAD HISTORY"
    DeloadCadenceFlag.None -> "ON TRACK"
    DeloadCadenceFlag.Soft -> "SOFT FLAG (4+ WEEKS)"
    DeloadCadenceFlag.Firm -> "FIRM FLAG (6+ WEEKS)"
}

// Phase A4 (Category 3). One card per dimension, called four times --
// energy/mood/stress/soreness are never summed into a Hooper Index or any
// other composite, per the spec's own explicit "keep the four items
// displayed separately" rule. The trend arrow is a real Mann-Kendall
// result, not decorative: it's absent whenever the 14-day trend doesn't
// clear p<0.05, per the spec's own "otherwise render no arrow" rule.
@Composable
private fun SubjectiveCard(title: String, points: List<Pair<LocalDate, Double>>) {
    val eval = evaluateSubjective(points)
    Card(title = title) {
        StateRow(eval.state)
        StatLine("Confidence", eval.confidence.label)
        eval.median7d?.let { StatLine("7-day median", "%.1f".format(it)) }
        eval.medianBaseline30d?.let { StatLine("30-day baseline", "%.1f".format(it)) }
        eval.iqr7d?.let { StatLine("7-day IQR", "%.1f".format(it)) }
        eval.trendDirection?.let { StatLine("14-day trend", if (it > 0) "↑ rising (p<0.05)" else "↓ falling (p<0.05)") }
    }
}

// Phase A4 (Category 4). No SHIFT_UP/SHIFT_DOWN here -- the spec never
// defines a threshold for calling an energy-trend move a "shift" the way it
// does for Categories 1/2/3/5/6, so `Stable` is shown once the gate is met,
// not because nothing moves but because there's no rule to judge it by.
@Composable
private fun NutritionCard(eval: NutritionEvaluation) {
    Card(title = "NUTRITION") {
        StateRow(eval.state)
        StatLine("Confidence", eval.confidence.label)
        eval.energyTrend14d?.let { StatLine("14-day energy trend", "%.0f kcal".format(it)) }
        eval.energyCv28d?.let { StatLine("28-day energy CV", "%.1f%%".format(it)) }
        eval.proteinAdherence14d?.let { StatLine("Protein in AMDR band (10-35% kcal)", "%.0f%%".format(it)) }
    }
}

private fun tierLabel(tier: ExpectationTier): String = when (tier) {
    ExpectationTier.PrimaryTarget -> "PRIMARY TARGET"
    ExpectationTier.Maintained -> "MAINTAINED"
    ExpectationTier.Unmanaged -> "NOT A TARGET THIS CYCLE"
}

@Composable
private fun StateRow(state: EvalState) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("State", style = TextStyle(fontFamily = Saira, fontSize = 14.5.sp), color = FieldColors.InkMuted)
        Text(stateLabel(state), style = TextStyle(fontFamily = JetBrainsMono, fontSize = 14.5.sp), color = stateColor(state))
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = TextStyle(fontFamily = Saira, fontSize = 14.5.sp), color = FieldColors.InkMuted)
        Text(value, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 14.5.sp), color = FieldColors.Ink)
    }
}

// Neutral labels per the spec's own explicit rule -- "above your band," not
// "improved": a SHIFT_UP is not automatically good (chronically elevated
// HRV can mean parasympathetic saturation, not great recovery), so no color
// or word here implies a value judgement except UNSTABLE, which really is
// always worth a second look regardless of direction.
private fun stateLabel(state: EvalState): String = when (state) {
    EvalState.NoData -> "NO DATA"
    EvalState.Building -> "BUILDING"
    EvalState.Stable -> "STABLE"
    EvalState.ShiftUp -> "ABOVE YOUR BAND"
    EvalState.ShiftDown -> "BELOW YOUR BAND"
    EvalState.Unstable -> "UNSTABLE"
}

private fun stateColor(state: EvalState): Color = when (state) {
    EvalState.NoData, EvalState.Building -> FieldColors.InkMuted
    EvalState.Stable -> FieldColors.Green
    EvalState.ShiftUp, EvalState.ShiftDown -> FieldColors.Amber
    EvalState.Unstable -> FieldColors.Alert
}
