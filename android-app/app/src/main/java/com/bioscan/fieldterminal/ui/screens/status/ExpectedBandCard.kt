package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.bioscan.fieldterminal.data.model.ExpectedBandPayload
import com.bioscan.fieldterminal.data.model.ExpectedBandRow
import com.bioscan.fieldterminal.domain.MetricState
import com.bioscan.fieldterminal.domain.MorningReadout
import com.bioscan.fieldterminal.domain.SLEEP_HOURS_RANGE
import com.bioscan.fieldterminal.domain.StateWord
import com.bioscan.fieldterminal.domain.expectedStateWord
import java.time.LocalDate
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Analysis review phase C (P1, P2, P10). Each morning against what was expected for you: the nightly analysis forecasts a
// band for every morning before the reading arrives (your usual level for that weekday, from your own history), and the
// card answers first, in a sentence, then shows the evidence: the band on a fixed personal scale, flagged mornings
// ringed, illness and source changes marked.

// What the phone has for one metric: its own readings (natural units, oldest first) for the chart's scale and for a
// reading that arrived after the nightly run.
data class BandInputs(val row: ExpectedBandRow, val readings: List<Pair<LocalDate, Double>>)

private fun bandWord(inputs: BandInputs, readout: MorningReadout?): StateWord = when {
    readout != null -> expectedStateWord(readout)
    inputs.row.payload.state == "building" -> StateWord(MetricState.Building, "BUILDING")
    else -> StateWord(MetricState.Neutral, "NO READING YET")
}

private fun flagColor(p: ExpectedBandPayload, low: Boolean, value: Double): Color {
    val unfavorable = when (p.better) {
        "higher" -> low
        "lower" -> !low
        else -> p.metric == "sleep_hours" && value !in SLEEP_HOURS_RANGE
    }
    return if (unfavorable) FT.Warn else FT.TextPrimary
}

