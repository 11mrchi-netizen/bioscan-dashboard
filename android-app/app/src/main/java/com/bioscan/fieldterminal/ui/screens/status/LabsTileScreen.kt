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
import com.bioscan.fieldterminal.data.model.LabDrawAnalysisRow
import com.bioscan.fieldterminal.data.model.LabResultAnalysisRow
import com.bioscan.fieldterminal.domain.BloodworkMarkerEvaluation
import com.bioscan.fieldterminal.domain.BloodworkTrendState
import com.bioscan.fieldterminal.domain.evaluateBloodworkMarker
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.DotPlot
import com.bioscan.fieldterminal.ui.components.TileHeader
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter
import com.bioscan.fieldterminal.ui.theme.RobotoMono
import java.time.LocalDate

// DAV-74/DAV-87 (First feedback fixes): Labs tile page. Keeps the existing
// 2-draw comparison table (LabsScreen.kt, unchanged) as the quick "what
// changed since last draw" view, and folds Category 9's real RCV/dot-plot
// analysis (previously stranded on the old Analysis sub-tab) in directly
// below it -- satisfies DAV-87 ("fold bloodwork analysis into Labs, each
// chart in the right place") without removing the simpler table anyone
// opening Labs sees first.
@Composable
fun LabsTileScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(FT.Base).verticalScroll(rememberScrollState())) {
        TileHeader(title = "LABS", context = "BLOODWORK", onBack = onBack)
        LabsScreen()
        BloodworkAnalysisSection()
    }
}

@Composable
private fun BloodworkAnalysisSection() {
    var labDraws by remember { mutableStateOf<List<LabDrawAnalysisRow>?>(null) }
    var labResults by remember { mutableStateOf<List<LabResultAnalysisRow>?>(null) }

    LaunchedEffect(Unit) {
        val repo = AnalysisRepository(SupabaseClientProvider.client)
        labDraws = repo.loadLabDraws()
        labResults = repo.loadLabResults()
    }

    val ld = labDraws
    val lr = labResults
    if (ld == null || lr == null) {
        Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = FT.Warning)
        }
        return
    }

    val drawDatesById = ld.associate { it.id to LocalDate.parse(it.drawDate) }
    val bloodworkEvals = lr
        .mapNotNull { row -> row.value?.let { v -> drawDatesById[row.drawId]?.let { date -> Triple(row, date, v) } } }
        .groupBy { it.first.markerName }
        .map { (markerName, rows) ->
            val first = rows.first().first
            val draws = rows.map { it.second to it.third }
            evaluateBloodworkMarker(
                markerName = markerName,
                draws = draws,
                unit = first.unit,
                refLow = first.refLow,
                refHigh = first.refHigh,
            ) to draws
        }
        .sortedBy { it.first.markerName }

    if (bloodworkEvals.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 16.dp)) {
        FTCard(title = "BLOODWORK (RCV)") {
            Text(
                "~1 draw/year means every comparison here is a single two-point delta against a real, marker-specific noise threshold — never a trend.",
                style = TextStyle(fontFamily = Inter, fontSize = 12.sp),
                color = FT.TextSecondary,
            )
            bloodworkEvals.forEach { (m, draws) -> BloodworkMarkerRow(m, draws) }
        }
    }
}

// Phase A5, Part 1: the dot plot renders every real draw for this marker,
// isolated (never connected by a line -- see ui/components/DotPlot.kt's own
// header comment for why), with the reference band behind them.
@Composable
private fun BloodworkMarkerRow(m: BloodworkMarkerEvaluation, draws: List<Pair<LocalDate, Double>>) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(m.markerName, style = TextStyle(fontFamily = Inter, fontSize = 14.sp), color = FT.TextPrimary)
            Text(
                "%.2f%s".format(m.latestValue, m.unit?.let { " $it" } ?: ""),
                style = TextStyle(fontFamily = RobotoMono, fontSize = 13.sp),
                color = FT.TextPrimary,
            )
        }
        val rangeText = if (m.refLow != null && m.refHigh != null) {
            "range %.2f–%.2f".format(m.refLow, m.refHigh)
        } else {
            "no reference range on file"
        }
        val stateText = if (m.previousValue == null) {
            "BUILDING (1 draw)"
        } else if (m.rcv == null || m.deltaPercent == null) {
            "no RCV citation for this marker"
        } else {
            when (m.state) {
                BloodworkTrendState.Stable -> "STABLE (Δ%+.1f%%, within RCV ±%.1f%%)".format(m.deltaPercent, m.rcv)
                BloodworkTrendState.ShiftUp -> "SHIFT UP (Δ%+.1f%% exceeds RCV ±%.1f%%)".format(m.deltaPercent, m.rcv)
                BloodworkTrendState.ShiftDown -> "SHIFT DOWN (Δ%+.1f%% exceeds RCV ±%.1f%%)".format(m.deltaPercent, m.rcv)
                null -> ""
            }
        }
        Text(
            "$rangeText · $stateText",
            style = TextStyle(fontFamily = Inter, fontSize = 12.sp),
            color = when (m.state) {
                BloodworkTrendState.ShiftUp, BloodworkTrendState.ShiftDown -> FT.Warning
                BloodworkTrendState.Stable -> FT.Emerald
                null -> FT.TextSecondary
            },
        )
        m.indexOfIndividuality?.takeIf { it < 0.6 }?.let {
            Text(
                "Index of Individuality %.2f — population range less informative here; read against your own history.".format(it),
                style = TextStyle(fontFamily = Inter, fontSize = 11.5.sp),
                color = FT.TextSecondary,
            )
        }
        val latestValue = draws.maxByOrNull { it.first }?.second
        val latestColor = when (m.state) {
            BloodworkTrendState.ShiftUp, BloodworkTrendState.ShiftDown -> FT.Warning
            BloodworkTrendState.Stable -> FT.Emerald
            null -> FT.TextSecondary
        }
        DotPlot(
            points = draws,
            refLow = m.refLow,
            refHigh = m.refHigh,
            dotColor = { v -> if (v == latestValue) latestColor else FT.TextSecondary },
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
