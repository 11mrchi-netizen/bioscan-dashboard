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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.LogArousalRow
import com.bioscan.fieldterminal.data.model.OstrcAnalysisRow
import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.data.model.StoolAnalysisRow
import com.bioscan.fieldterminal.data.model.TrainingLoadSessionRow
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.data.model.WellbeingAnalysisRow
import com.bioscan.fieldterminal.domain.BristolEvaluation
import com.bioscan.fieldterminal.domain.BristolPattern
import com.bioscan.fieldterminal.domain.OstrcEvaluation
import com.bioscan.fieldterminal.domain.RespiratoryAnomalyEvaluation
import com.bioscan.fieldterminal.domain.SleepNight
import com.bioscan.fieldterminal.domain.SriEvaluation
import com.bioscan.fieldterminal.domain.SubjectiveEvaluation
import com.bioscan.fieldterminal.domain.SwcEvaluation
import com.bioscan.fieldterminal.domain.evaluateBristol
import com.bioscan.fieldterminal.domain.evaluateHrv
import com.bioscan.fieldterminal.domain.evaluateOstrc
import com.bioscan.fieldterminal.domain.evaluateRespiratoryAnomaly
import com.bioscan.fieldterminal.domain.evaluateRestCadence
import com.bioscan.fieldterminal.domain.evaluateRhr
import com.bioscan.fieldterminal.domain.evaluateSleepDuration
import com.bioscan.fieldterminal.domain.evaluateSri
import com.bioscan.fieldterminal.domain.evaluateSubjective
import com.bioscan.fieldterminal.domain.evaluateTrainingLoad
import com.bioscan.fieldterminal.domain.expValue
import com.bioscan.fieldterminal.ui.components.Card
import com.bioscan.fieldterminal.ui.components.StateRow
import com.bioscan.fieldterminal.ui.components.SubTabRow
import com.bioscan.fieldterminal.ui.components.TileHeader
import com.bioscan.fieldterminal.ui.nav.HeartTab
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles
import com.bioscan.fieldterminal.ui.theme.JetBrainsMono
import com.bioscan.fieldterminal.ui.theme.Saira
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

// DAV-73 (First feedback fixes): Heart tile page, 7 tabs. Every card here
// (except Arousal, which has no Analysis Layer evaluation yet -- only ever
// logged, never evaluated) is migrated unchanged from AnalysisScreen.kt's
// old monolithic Column (Phase A2-A4); only which tab shows it changed. One
// shared load for the whole page so switching tabs is instant, matching the
// Fuel/Training tile pattern.
@Composable
fun HeartTileScreen(onBack: () -> Unit) {
    var tab by remember { mutableStateOf(HeartTab.Heart) }

    var wearable by remember { mutableStateOf<List<WearableAnalysisRow>?>(null) }
    var sleep by remember { mutableStateOf<List<SleepAnalysisRow>?>(null) }
    var wellbeing by remember { mutableStateOf<List<WellbeingAnalysisRow>?>(null) }
    var stool by remember { mutableStateOf<List<StoolAnalysisRow>?>(null) }
    var ostrc by remember { mutableStateOf<List<OstrcAnalysisRow>?>(null) }
    var trainingSessions by remember { mutableStateOf<List<TrainingLoadSessionRow>?>(null) }
    var arousal by remember { mutableStateOf<List<LogArousalRow>?>(null) }

    LaunchedEffect(Unit) {
        val repo = AnalysisRepository(SupabaseClientProvider.client)
        wearable = repo.loadWearableDaily()
        sleep = repo.loadSleepDaily()
        wellbeing = repo.loadWellbeingDaily()
        stool = repo.loadStoolLog()
        ostrc = repo.loadOstrcCheckins()
        trainingSessions = repo.loadExerciseSessionsForTrainingLoad()
        arousal = SupabaseClientProvider.client.postgrest.from("arousal_daily")
            .select(columns = Columns.list("id,date,morning_erection_quality,arousal_level")) {
                order("date", Order.DESCENDING)
                limit(30)
            }
            .decodeList()
    }

    Column(modifier = Modifier.fillMaxSize().background(FieldColors.Ground).verticalScroll(rememberScrollState())) {
        TileHeader(title = "HEART", context = "HRV · SLEEP · WELLNESS · INJURIES", onBack = onBack)
        SubTabRow(items = HeartTab.entries, selected = tab, label = { it.label }, onSelect = { tab = it })

        val w = wearable
        val s = sleep
        val wb = wellbeing
        val st = stool
        val os = ostrc
        val t = trainingSessions
        val ar = arousal
        if (w == null || s == null || wb == null || st == null || os == null || t == null || ar == null) {
            Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = FieldColors.Amber)
            }
            return@Column
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            when (tab) {
                HeartTab.Heart -> {
                    val hrvPoints = w.mapNotNull { row -> row.hrv?.let { LocalDate.parse(row.date) to it } }
                    EvalCard("HRV", evaluateHrv(hrvPoints).toExpSpace(), "ms")
                    val rhrPoints = w.mapNotNull { row -> row.rhr?.let { LocalDate.parse(row.date) to it } }
                    EvalCard("RESTING HEART RATE", evaluateRhr(rhrPoints), "bpm")
                }
                HeartTab.Arousal -> ArousalHistory(ar)
                HeartTab.Wellness -> {
                    SubjectiveCard("ENERGY", wb.mapNotNull { row -> row.energy?.let { LocalDate.parse(row.date) to it.toDouble() } })
                    SubjectiveCard("MOOD", wb.mapNotNull { row -> row.mood?.let { LocalDate.parse(row.date) to it.toDouble() } })
                    SubjectiveCard("STRESS", wb.mapNotNull { row -> row.stress?.let { LocalDate.parse(row.date) to it.toDouble() } })
                    SubjectiveCard("SORENESS", wb.mapNotNull { row -> row.soreness?.let { LocalDate.parse(row.date) to it.toDouble() } })
                }
                HeartTab.Sleep -> {
                    val hoursPoints = s.mapNotNull { row -> row.hours?.let { LocalDate.parse(row.date) to it } }
                    EvalCard("SLEEP DURATION", evaluateSleepDuration(hoursPoints), "h")
                    val nights = s.mapNotNull { row ->
                        val bedtime = row.bedtime?.let { runCatching { Instant.parse(it) }.getOrNull() }
                        val wake = row.wakeTime?.let { runCatching { Instant.parse(it) }.getOrNull() }
                        if (bedtime != null && wake != null) SleepNight(LocalDate.parse(row.date), bedtime, wake) else null
                    }
                    SriCard(evaluateSri(nights))
                }
                HeartTab.Stool -> {
                    val stoolEntries = st.mapNotNull { row -> row.bristolType?.let { OffsetDateTime.parse(row.occurredAt).toLocalDateTime().toLocalDate() to it } }
                    BristolCard(evaluateBristol(stoolEntries))
                }
                HeartTab.Injuries -> {
                    HealthEventsScreen()
                    val sessionLoads = t.mapNotNull { row ->
                        val duration = row.durationMin
                        val rpe = row.rpe
                        if (duration != null && rpe != null) OffsetDateTime.parse(row.startTime).toLocalDateTime().toLocalDate() to duration * rpe else null
                    }
                    val trainingLoadEval = evaluateTrainingLoad(sessionLoads)
                    val restCadenceEval = evaluateRestCadence(sessionLoads, trainingLoadEval.tsb, trainingLoadEval.confidence.met)
                    val sorenessEval = evaluateSubjective(wb.mapNotNull { row -> row.soreness?.let { LocalDate.parse(row.date) to it.toDouble() } })
                    val ostrcByBodyArea = os.mapNotNull { row -> row.severityScore?.let { row.bodyArea to (LocalDate.parse(row.checkDate) to it) } }
                        .groupBy({ it.first }, { it.second })
                    if (ostrcByBodyArea.isEmpty()) {
                        OstrcCard(evaluateOstrc("—", emptyList()), trainingLoadEval.tsb, restCadenceEval.consecutiveDaysWithoutRest, sorenessEval.median7d)
                    } else {
                        ostrcByBodyArea.forEach { (bodyArea, entries) ->
                            OstrcCard(evaluateOstrc(bodyArea, entries), trainingLoadEval.tsb, restCadenceEval.consecutiveDaysWithoutRest, sorenessEval.median7d)
                        }
                    }
                }
                HeartTab.Respiratory -> {
                    val rrPoints = s.mapNotNull { row -> row.respiratoryRate?.let { LocalDate.parse(row.date) to it } }
                    RespiratoryCard(evaluateRespiratoryAnomaly(rrPoints))
                }
            }
        }
    }
}

@Composable
private fun ArousalHistory(rows: List<LogArousalRow>) {
    if (rows.isEmpty()) {
        Text("No arousal entries logged yet.", style = FieldTextStyles.placeholderBody, color = FieldColors.InkMuted)
        return
    }
    Card(title = "RECENT ENTRIES") {
        Text(
            "No Analysis Layer evaluation exists for arousal yet -- real recent log history only.",
            style = TextStyle(fontFamily = Saira, fontSize = 12.sp),
            color = FieldColors.InkMuted,
        )
        rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(row.date, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp), color = FieldColors.InkMuted)
                Text(
                    listOfNotNull(
                        row.morningErectionQuality?.let { "Morning wood $it/10" },
                        row.arousalLevel?.let { "Arousal $it/10" },
                    ).joinToString(" · "),
                    style = TextStyle(fontFamily = Saira, fontSize = 13.sp),
                    color = FieldColors.Ink,
                )
            }
        }
    }
}

private fun SwcEvaluation.toExpSpace(): SwcEvaluation = copy(baseline7d = expValue(baseline7d), mean60d = expValue(mean60d))

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
private fun BristolCard(eval: BristolEvaluation) {
    Card(title = "DIGESTIVE PATTERN (BRISTOL)") {
        StatLine("Confidence", eval.confidence.label)
        eval.pattern?.let { StatLine("Pattern", bristolPatternLabel(it)) }
        eval.pctHard?.let { StatLine("Hard (types 1-2)", "%.0f%%".format(it)) }
        eval.pctNormal?.let { StatLine("Normal (types 3-5)", "%.0f%%".format(it)) }
        eval.pctLoose?.let { StatLine("Loose (types 6-7)", "%.0f%%".format(it)) }
        Text(
            "Descriptive pattern only — not a diagnostic tool.",
            style = TextStyle(fontFamily = Saira, fontSize = 12.sp),
            color = FieldColors.InkMuted,
        )
    }
}

private fun bristolPatternLabel(p: BristolPattern): String = when (p) {
    BristolPattern.PredominantlyFirm -> "PREDOMINANTLY FIRM"
    BristolPattern.PredominantlyLoose -> "PREDOMINANTLY LOOSE"
    BristolPattern.Mixed -> "MIXED PATTERN"
    BristolPattern.Typical -> "TYPICAL PATTERN"
}

@Composable
private fun OstrcCard(eval: OstrcEvaluation, tsb: Double?, daysWithoutRest: Int, sorenessMedian: Double?) {
    Card(title = "OSTRC-H2 · ${eval.bodyArea.uppercase()}") {
        StatLine("Confidence", eval.confidence.label)
        eval.latestSeverityScore?.let { StatLine("Latest severity", "$it / 100") }
        eval.latestCheckDate?.let { StatLine("Last check-in", it.toString()) }
        Text(
            "LOAD CONTEXT (shown adjacent, never combined into one score)",
            style = FieldTextStyles.subTabLabel,
            color = FieldColors.InkMuted,
        )
        tsb?.let { StatLine("TSB (form)", "%+.1f".format(it)) }
        StatLine("Days without rest", "$daysWithoutRest")
        sorenessMedian?.let { StatLine("7-day soreness median", "%.1f".format(it)) }
        Text(
            "No injury risk score — single-factor screening doesn't predict injury. You do the synthesis; this doesn't.",
            style = TextStyle(fontFamily = Saira, fontSize = 12.sp),
            color = FieldColors.InkMuted,
        )
    }
}

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

@Composable
private fun StatLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = TextStyle(fontFamily = Saira, fontSize = 14.5.sp), color = FieldColors.InkMuted)
        Text(value, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 14.5.sp), color = FieldColors.Ink)
    }
}
