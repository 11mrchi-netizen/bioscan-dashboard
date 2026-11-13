package com.bioscan.fieldterminal.ui.screens.status

import com.bioscan.fieldterminal.data.catching
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
        catching {
            ScorecardMemo.get(listOf(wearable, sleep, wellbeing, sessions, breakStarts, loadSpans), planned) {
                compositeInputs(wearable, sleep, wellbeing, sessions, breakStarts, loadSpans).history(LocalDate.now())
            }
        }.getOrNull()
    }
}

