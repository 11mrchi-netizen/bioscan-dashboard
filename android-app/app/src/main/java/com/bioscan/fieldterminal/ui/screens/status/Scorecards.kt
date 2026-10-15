package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.bioscan.fieldterminal.data.model.PlannedOutcomeRow
import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.data.model.TrainingLoadSessionRow
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.data.model.WellbeingAnalysisRow
import com.bioscan.fieldterminal.domain.CompositeHistory
import com.bioscan.fieldterminal.domain.CompositeScorecards
import com.bioscan.fieldterminal.domain.LoadSpans
import com.bioscan.fieldterminal.domain.MetricState
import com.bioscan.fieldterminal.domain.ScorecardResult
import com.bioscan.fieldterminal.domain.ScorecardVerdict
import com.bioscan.fieldterminal.domain.SeriesBreakStarts
import com.bioscan.fieldterminal.domain.StateWord
import com.bioscan.fieldterminal.domain.compositeInputs
import com.bioscan.fieldterminal.domain.history
import com.bioscan.fieldterminal.domain.plannedDoneByDay
import com.bioscan.fieldterminal.domain.readyForScorecard
import com.bioscan.fieldterminal.domain.scorecardWord
import com.bioscan.fieldterminal.domain.scorecards
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

// Analysis review phase G (P11): the composite scorecards on screen. A composite's scorecard is worked out on the person's
// own history (domain/Scorecard.kt, Composites.kt) off the main thread, once per loaded data set: the loaders hand the
// Today page, the Heart tile and the Training tile the same cached lists, so every screen reads the same verdict and a
// composite demoted on one is demoted on all.

data class ScorecardBundle(
    val recovery: ScorecardResult,
    val sleepIndex: ScorecardResult,
    val sleepEnergy: ScorecardResult,
    val sleepHrv: ScorecardResult,
    /** Null until the planned sessions are loaded (or when the screen does not load them). */
    val readyFor: ScorecardResult?,
)

private object ScorecardMemo {
    private var key: List<Any?>? = null
    private var history: CompositeHistory? = null
    private var cards: CompositeScorecards? = null
    private var plannedKey: Any? = null
    private var readyFor: ScorecardResult? = null

    // The row lists by identity (the cache hands every screen the same instances), small values by equality.
    private fun same(a: List<Any?>?, b: List<Any?>) =
        a != null && a.size == b.size && a.indices.all { a[it] === b[it] || (a[it] !is List<*> && a[it] == b[it]) }

    @Synchronized
    fun get(key: List<Any?>, planned: List<PlannedOutcomeRow>?, compute: () -> CompositeHistory): ScorecardBundle {
        if (!same(this.key, key)) {
            val h = compute()
            this.key = key
            history = h
            cards = h.scorecards()
            plannedKey = null
            readyFor = null
        }
        if (planned != null && plannedKey !== planned) {
            readyFor = history!!.readyForScorecard(plannedDoneByDay(planned))
            plannedKey = planned
        }
        val c = cards!!
        return ScorecardBundle(c.recovery, c.sleepIndex, c.sleepEnergy, c.sleepHrv, readyFor)
    }
}

/** The scorecards for the loaded rows, or null while they are being worked out. */
@Composable
fun rememberScorecards(
    wearable: List<WearableAnalysisRow>?,
    sleep: List<SleepAnalysisRow>?,
    wellbeing: List<WellbeingAnalysisRow>?,
    sessions: List<TrainingLoadSessionRow>?,
    breakStarts: SeriesBreakStarts,
    loadSpans: LoadSpans,
    planned: List<PlannedOutcomeRow>? = null,
): State<ScorecardBundle?> = produceState<ScorecardBundle?>(null, wearable, sleep, wellbeing, sessions, breakStarts, loadSpans, planned) {
    if (wearable == null || sleep == null || wellbeing == null || sessions == null) return@produceState
    value = withContext(Dispatchers.Default) {
        runCatching {
            ScorecardMemo.get(listOf(wearable, sleep, wellbeing, sessions, breakStarts, loadSpans), planned) {
                compositeInputs(wearable, sleep, wellbeing, sessions, breakStarts, loadSpans).history(LocalDate.now())
            }
        }.getOrNull()
    }
}

/** The state word of a composite whose scorecard demoted it: its inputs are shown, without its own verdict. */
val INPUTS_ONLY = StateWord(MetricState.Neutral, "INPUTS ONLY")

/** One line under a composite: its scorecard's verdict and sentence (nothing while it is being worked out). */
@Composable
fun ScorecardLine(result: ScorecardResult?) {
    if (result == null) return
    Text("SCORECARD · ${scorecardWord(result.verdict)}", style = FTType.LabelCaps, color = FT.TextSecondary)
    Text(result.sentence, style = FTType.Caption, color = FT.TextMuted)
}

private const val SCORECARDS_INFO =
    "Each summary score is tested on your own history against the outcome it should help predict: every morning it is " +
        "turned into a forecast from the days before, and the forecast is scored once the outcome is known (log score, or Brier " +
        "score for yes or no). Each of its inputs is scored the same way. A summary that forecasts no better than its best " +
        "single input adds nothing that input does not already say, so it is shown as its inputs instead. EARNS ITS PLACE: " +
        "better than its best input. NO BETTER: not meaningfully better. UNCLEAR: not settled. NOT YET SCORED: too few days."

// The Today page's overview: every composite with its scorecard, and the two the plan settled without one.
@Composable
fun ScorecardsCard(bundle: ScorecardBundle?, typicality: Pair<String, String>? = null) {
    FTCard(title = "DO THE SUMMARIES EARN THEIR PLACE?", info = SCORECARDS_INFO) {
        if (bundle == null) {
            Text("Working out the scorecards from your history…", style = FTType.BodySmall, color = FT.TextSecondary)
            return@FTCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScorecardEntry("TODAY'S RECOVERY", bundle.recovery)
            ScorecardEntry("READY FOR", bundle.readyFor, missing = "No planned sessions in the last 200 days to score it against.")
            ScorecardEntry("SLEEP INDEX · ENERGY", bundle.sleepEnergy)
            ScorecardEntry("SLEEP INDEX · HRV", bundle.sleepHrv)
            SettledEntry("IS TODAY TYPICAL? · ${typicality?.first ?: "NOT YET SCORED"}", typicality?.second ?: "Scored nightly against logged illness once the analysis has run.")
            SettledEntry("CONDITION", "Retired: the expected band on THIS MORNING answers the same question against weekday and trend, with a calibrated band.")
            SettledEntry("HEALTHSPAN GRADE", "Kept as a summary of its factors: the outcomes it is about are years away, so it is not scored as a forecast.")
        }
    }
}

@Composable
private fun ScorecardEntry(title: String, result: ScorecardResult?, missing: String = "Not scored yet.") {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$title · ${result?.let { scorecardWord(it.verdict) } ?: "NOT YET SCORED"}", style = FTType.Label, color = FT.TextPrimary)
        Text(result?.sentence ?: missing, style = FTType.Caption, color = FT.TextSecondary)
        val best = result?.best
        if (result != null && best != null && result.verdict != ScorecardVerdict.NOT_YET) {
            Text("Best single input: ${best.label.lowercase()}", style = FTType.Micro, color = FT.TextMuted)
        }
    }
}

@Composable
private fun SettledEntry(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = FTType.Label, color = FT.TextPrimary)
        Text(text, style = FTType.Caption, color = FT.TextSecondary)
    }
}
