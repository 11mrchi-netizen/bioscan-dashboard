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

private const val BAND_INFO =
    "Each morning is compared with a forecast made before it from your usual level for that weekday. The grey band holds " +
        "8 mornings in 10 when nothing has changed. One morning outside it is ordinary; a flag needs a morning beyond " +
        "the 99-in-100 band, or several in a row on one side."

private const val BAND_DETAILS =
    "The line under the chart says how often the band actually held. A new device starts the band again, wide at first. " +
        "Days inside a logged illness are shown but not judged."

private val METRIC_ORDER = listOf("hrv", "rhr", "sleep_hours")
private val METRIC_TITLES = mapOf("hrv" to "HRV", "rhr" to "RESTING HR", "sleep_hours" to "SLEEP")

// What the phone has for one metric: its own readings (natural units, oldest first) for the chart's scale and for a
// reading that arrived after the nightly run.
data class BandInputs(val row: ExpectedBandRow, val readings: List<Pair<LocalDate, Double>>)

// Today's lead card. `metrics` holds the rows that exist; the card is left out entirely before the first run.
@Composable
internal fun ThisMorningCard(
    metrics: Map<String, BandInputs>,
    today: LocalDate,
    modifier: Modifier = Modifier,
    onOpenCardio: (() -> Unit)? = null,
    // Analysis review phase D: last evening's exposures that the evidence board reads CLEAR about.
    contributors: List<Contributor> = emptyList(),
) {
    if (metrics.isEmpty()) return
    val ordered = METRIC_ORDER.mapNotNull { key -> metrics[key]?.let { key to it } }
    val readouts = ordered.mapNotNull { (_, m) -> morningReadout(m.row.payload, today, m.readings.lastOrNull()) }
    FTCard(title = "THIS MORNING", info = BAND_INFO, infoDetails = BAND_DETAILS, emphasis = FTCardEmphasis.Hero, modifier = modifier) {
        ordered.forEach { (key, m) ->
            val readout = readouts.firstOrNull { it.metric == key }
            BandSection(METRIC_TITLES[key] ?: key.uppercase(), m, readout, today)
        }
        contributors.forEach { c ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(c.title.uppercase(), style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.weight(1f))
                    VerdictChip(Verdict.Clear)
                }
                Text(c.line, style = FTType.Caption, color = FT.TextSecondary)
            }
        }
        onOpenCardio?.let { FTButton("HRV AND RESTING HR IN DETAIL", emphasis = FTButtonEmphasis.Tertiary, onClick = it) }
    }
}

// App restructure, fix 1: each metric is its title and its pill, then its chart. The sentences that sat beside the pill
// ("as expected for a Monday", the range, how rare, the run) are in the pill's pop-up, with how many recent mornings fell
// inside the range and how well the band has held.
@Composable
private fun BandSection(title: String, inputs: BandInputs, readout: MorningReadout?, today: LocalDate) {
    val p = inputs.row.payload
    var open by remember { mutableStateOf(false) }
    val word = when {
        readout != null -> expectedStateWord(readout)
        p.state == "building" -> StateWord(MetricState.Building, "BUILDING")
        else -> StateWord(MetricState.Neutral, "NO READING YET")
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(title, style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.weight(1f))
            FTStatePill(word, onClick = { open = true })
        }
        if (p.state != "building") BandChart(p, inputs.readings, readout, today)
    }
    if (open) InfoDialog(title, bandExplanation(p, readout, today), details = BAND_INFO + "\n\n" + BAND_DETAILS) { open = false }
}

// The pop-up text for one metric: the answer, the range, how rare, any run or flag, how many recent mornings fell inside
// the range, and how often the band has held.
private fun bandExplanation(p: ExpectedBandPayload, readout: MorningReadout?, today: LocalDate): String {
    val lines = mutableListOf<String>()
    when {
        p.state == "building" -> lines += "The expected range is still being learnt: ${p.have} of ${p.need} mornings so far" +
            (p.regimeStart?.let { " (since $it)" } ?: "") + "."
        readout == null -> {
            val point = bandPointFor(p, today)
            val range = if (point?.lo80 != null && point.hi80 != null) {
                if (p.unit == "h") "%.1f–%.1f h".format(point.lo80, point.hi80) else "%.0f–%.0f %s".format(point.lo80, point.hi80, p.unit)
            } else {
                null
            }
            lines += "No reading yet this morning." + (range?.let { " Expected $it: 8 mornings in 10 fall inside." } ?: "")
        }
        else -> {
            lines += readout.headline()
            lines += readout.bandLine()
            if (readout.excluded) {
                lines += "Illness logged: shown against the band, not judged."
            } else {
                readout.rarityLine()?.let { lines += it }
                readout.flagLine()?.let { lines += it }
            }
        }
    }
    val judged = p.recent.filter { !it.excluded && it.value != null && it.lo80 != null && it.hi80 != null }
    if (judged.isNotEmpty()) {
        val inside = judged.count { it.value!! in it.lo80!!..it.hi80!! }
        lines += "$inside of the last ${judged.size} mornings fell inside their expected range."
    }
    val c = p.calibration
    if (c.days >= 30 && c.inside80 != null) {
        lines += "Over the last ${c.days} mornings the band held %.0f%% of them (it should hold about 80%%).".format(c.inside80 * 100)
    }
    return lines.joinToString("\n\n")
}

// The band over the last four weeks on the person's usual scale: each morning a dot against its own band, flagged ones
// ringed, illness shaded, a new source marked. The calibration line under it is the honesty check.
@Composable
private fun BandChart(p: ExpectedBandPayload, readings: List<Pair<LocalDate, Double>>, readout: MorningReadout?, today: LocalDate) {
    val parse = { d: String -> runCatching { LocalDate.parse(d) }.getOrNull() }
    val points = buildList {
        p.recent.forEach { pt -> val d = parse(pt.date); val v = pt.value; if (d != null && v != null) add(d to v) }
        if (readout != null && none { it.first == today }) add(today to readout.value)
    }
    val bandPoints = (p.recent + listOfNotNull(p.next?.takeIf { it.date == today.toString() }))
    val band = bandPoints.mapNotNull { pt ->
        val d = parse(pt.date) ?: return@mapNotNull null
        val lo = pt.lo80 ?: return@mapNotNull null
        val hi = pt.hi80 ?: return@mapNotNull null
        TrendBandDay(d, lo, hi, pt.expected)
    }
    val marks = buildList {
        p.recent.forEach { pt ->
            val d = parse(pt.date)
            val v = pt.value
            if (d != null && v != null && pt.flag != null && !pt.excluded) add(TrendMark(d, v, flagColor(p, pt.side == "low", v)))
        }
        if (readout?.flag != null && none { it.date == today } && !readout.excluded) add(TrendMark(today, readout.value, flagColor(p, readout.flag.low, readout.value)))
    }
    val annotations = buildList {
        illnessSpans(p).forEach { (from, to) -> add(TrendAnnotation(from, to, "Illness")) }
        val regime = p.regimeStart?.let(parse)
        val first = p.recent.firstOrNull()?.date?.let(parse)
        if (regime != null && first != null && regime.isAfter(first)) add(TrendAnnotation(regime, label = "New source"))
    }
    val unit = when (p.unit) { "h" -> "hours"; else -> p.unit }
    DateTrendLine(
        series = listOf(TrendSeries(points, FT.DomainHeart, label = "READING", dotsOnly = true)),
        modifier = Modifier.padding(top = 4.dp),
        valueFormat = { if (p.unit == "h") "%.1f".format(it) else "%.0f".format(it) },
        unit = "$unit · last 4 weeks",
        legend = true,
        scale = personalScale(readings.takeLast(365).map { it.second }),
        band = band,
        marks = marks,
        annotations = annotations,
        bandLabel = "EXPECTED, 8 IN 10",
        markLabel = "FLAGGED",
        chartHeight = 120.dp,
    )
}

private fun flagColor(p: ExpectedBandPayload, low: Boolean, value: Double): Color {
    val unfavorable = when (p.better) {
        "higher" -> low
        "lower" -> !low
        else -> p.metric == "sleep_hours" && value !in SLEEP_HOURS_RANGE
    }
    return if (unfavorable) FT.Warning else FT.TextPrimary
}

// Runs of consecutive excluded (illness) days in the recent window.
private fun illnessSpans(p: ExpectedBandPayload): List<Pair<LocalDate, LocalDate>> {
    val days = p.recent.filter { it.excluded }.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }.sorted()
    val out = mutableListOf<Pair<LocalDate, LocalDate>>()
    days.forEach { d ->
        val last = out.lastOrNull()
        if (last != null && last.second.plusDays(1) == d) out[out.size - 1] = last.first to d else out.add(d to d)
    }
    return out
}
