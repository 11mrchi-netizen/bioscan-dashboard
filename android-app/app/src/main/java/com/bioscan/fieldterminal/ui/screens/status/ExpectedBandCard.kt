package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.model.ExpectedBandPayload
import com.bioscan.fieldterminal.data.model.ExpectedBandRow
import com.bioscan.fieldterminal.domain.Contributor
import com.bioscan.fieldterminal.domain.MetricState
import com.bioscan.fieldterminal.domain.Verdict
import com.bioscan.fieldterminal.domain.MorningReadout
import com.bioscan.fieldterminal.domain.SLEEP_HOURS_RANGE
import com.bioscan.fieldterminal.domain.StateWord
import com.bioscan.fieldterminal.domain.WhyContext
import com.bioscan.fieldterminal.ui.components.InfoDialog
import com.bioscan.fieldterminal.domain.bandLine
import com.bioscan.fieldterminal.domain.bandPointFor
import com.bioscan.fieldterminal.domain.expectedStateWord
import com.bioscan.fieldterminal.domain.flagLine
import com.bioscan.fieldterminal.domain.headline
import com.bioscan.fieldterminal.domain.morningReadout
import com.bioscan.fieldterminal.domain.rarityLine
import com.bioscan.fieldterminal.ui.components.DateTrendLine
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTCardEmphasis
import com.bioscan.fieldterminal.ui.components.FTStatePill
import com.bioscan.fieldterminal.ui.components.TrendAnnotation
import com.bioscan.fieldterminal.ui.components.TrendBandDay
import com.bioscan.fieldterminal.ui.components.TrendMark
import com.bioscan.fieldterminal.ui.components.TrendSeries
import com.bioscan.fieldterminal.ui.components.personalScale
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate

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
    return if (unfavorable) FT.Warning else FT.TextPrimary
}

