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
import com.bioscan.fieldterminal.data.TrainingCyclesRepository
import com.bioscan.fieldterminal.data.model.TrainingLoadSessionRow
import com.bioscan.fieldterminal.domain.DeloadCadenceFlag
import com.bioscan.fieldterminal.domain.ExpectationTier
import com.bioscan.fieldterminal.domain.MetricCategory
import com.bioscan.fieldterminal.domain.RestCadenceEvaluation
import com.bioscan.fieldterminal.domain.TrainingCycle
import com.bioscan.fieldterminal.domain.TrainingLoadEvaluation
import com.bioscan.fieldterminal.domain.evaluateRestCadence
import com.bioscan.fieldterminal.domain.evaluateTrainingLoad
import com.bioscan.fieldterminal.domain.resolveTier
import com.bioscan.fieldterminal.ui.components.Card
import com.bioscan.fieldterminal.ui.components.TileHeader
import com.bioscan.fieldterminal.ui.components.stateColor
import com.bioscan.fieldterminal.ui.components.stateLabel
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles
import com.bioscan.fieldterminal.ui.theme.JetBrainsMono
import com.bioscan.fieldterminal.ui.theme.Saira
import java.time.OffsetDateTime

// DAV-71 (First feedback fixes): Training tile page, single view (no
// sub-tabs, per the user's own "barbell - with only training" spec). Wraps
// the existing TrainingScreen() content (Step 7/Phase G3, untouched) and
// folds in Category 6 (training load CTL/ATL/TSB) + Category 7 (rest
// cadence) directly below it -- these were previously stranded on the old
// Analysis sub-tab, disconnected from the training data they're actually
// about. Cards themselves are unchanged from AnalysisScreen.kt (Phase A3/A4);
// only their home moved.
@Composable
fun TrainingTileScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(FieldColors.Ground).verticalScroll(rememberScrollState())) {
        TileHeader(title = "TRAINING", context = "ENDURANCE + STRENGTH", onBack = onBack)
        TrainingScreen()
        TrainingLoadSection()
    }
}

@Composable
private fun TrainingLoadSection() {
    var sessions by remember { mutableStateOf<List<TrainingLoadSessionRow>?>(null) }
    var activeCycle by remember { mutableStateOf<TrainingCycle?>(null) }
    var cycleLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        sessions = AnalysisRepository(SupabaseClientProvider.client).loadExerciseSessionsForTrainingLoad()
        activeCycle = TrainingCyclesRepository(SupabaseClientProvider.client).loadActiveCycle()
        cycleLoaded = true
    }

    val s = sessions
    if (s == null || !cycleLoaded) {
        Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = FieldColors.Amber)
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        val sessionLoads = s.mapNotNull { row ->
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

        val restCadenceEval = evaluateRestCadence(sessionLoads, trainingLoadEval.tsb, trainingLoadEval.confidence.met)
        RestCadenceCard(restCadenceEval)
    }
}

// Phase A4. TSB's own descriptive band (Freshened/Neutral/Loaded/...) is
// the real label here, not the generic ABOVE/BELOW-YOUR-BAND wording
// stateLabel() uses for the SWC-based categories -- TSB is a TrainingPeaks
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
        // Phase A3: a pure re-label of the state above, never a
        // recomputation -- only rendered when a training cycle is actually
        // active. No active cycle means no framing applies, not "unmanaged."
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
// spec's own "these are two different questions" framing.
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

private fun tierLabel(tier: ExpectationTier): String = when (tier) {
    ExpectationTier.PrimaryTarget -> "PRIMARY TARGET"
    ExpectationTier.Maintained -> "MAINTAINED"
    ExpectationTier.Unmanaged -> "NOT A TARGET THIS CYCLE"
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = TextStyle(fontFamily = Saira, fontSize = 14.5.sp), color = FieldColors.InkMuted)
        Text(value, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 14.5.sp), color = FieldColors.Ink)
    }
}
