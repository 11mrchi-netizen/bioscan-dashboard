package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.model.ExpectedBandPayload
import com.bioscan.fieldterminal.data.model.ExpectedBandRow
import com.bioscan.fieldterminal.domain.MetricState
import com.bioscan.fieldterminal.domain.MorningReadout
import com.bioscan.fieldterminal.domain.SLEEP_HOURS_RANGE
import com.bioscan.fieldterminal.domain.bandLine
import com.bioscan.fieldterminal.domain.bandPointFor
import com.bioscan.fieldterminal.domain.expectedStateWord
import com.bioscan.fieldterminal.domain.flagLine
import com.bioscan.fieldterminal.domain.headline
import com.bioscan.fieldterminal.domain.leadReadout
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
    "Each morning is compared with a forecast made before it: your usual level for that weekday, learnt from your own " +
        "history. The grey band holds 8 mornings in 10 when nothing has changed; the line under the chart says how often it " +
        "actually did. One morning outside it is ordinary and is not flagged. A flag needs a morning beyond the band 99 in " +
        "100 fall inside, or several mornings in a row on one side. A new device starts the band again, wide at first. " +
        "Days inside a logged illness are shown but not judged."

private val METRIC_ORDER = listOf("hrv", "rhr", "sleep_hours")
private val METRIC_TITLES = mapOf("hrv" to "HRV", "rhr" to "RESTING HR", "sleep_hours" to "SLEEP")

// What the phone has for one metric: its own readings (natural units, oldest first) for the chart's scale and for a
// reading that arrived after the nightly run.
data class BandInputs(val row: ExpectedBandRow, val readings: List<Pair<LocalDate, Double>>)

// Today's lead card. `metrics` holds the rows that exist; the card is left out entirely before the first run.
@Composable
internal fun ThisMorningCard(metrics: Map<String, BandInputs>, today: LocalDate, onOpenCardio: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    if (metrics.isEmpty()) return
    val ordered = METRIC_ORDER.mapNotNull { key -> metrics[key]?.let { key to it } }
    val readouts = ordered.mapNotNull { (_, m) -> morningReadout(m.row.payload, today, m.readings.lastOrNull()) }
    val lead = leadReadout(readouts)
    FTCard(title = "THIS MORNING", info = BAND_INFO, emphasis = FTCardEmphasis.Hero, modifier = modifier) {
        ordered.forEach { (key, m) ->
            val readout = readouts.firstOrNull { it.metric == key }
            BandSection(METRIC_TITLES[key] ?: key.uppercase(), m, readout, today, chart = readout != null && readout === lead)
        }
        onOpenCardio?.let { FTButton("HRV AND RESTING HR IN DETAIL", emphasis = FTButtonEmphasis.Tertiary, onClick = it) }
    }
}

// Health › Cardio's lead card: HRV and resting heart rate, each with its band chart.
@Composable
internal fun CardioMorningCard(metrics: Map<String, BandInputs>, today: LocalDate, modifier: Modifier = Modifier) {
    val ordered = listOf("hrv", "rhr").mapNotNull { key -> metrics[key]?.let { key to it } }
    if (ordered.isEmpty()) return
    FTCard(title = "THIS MORNING", info = BAND_INFO, emphasis = FTCardEmphasis.Hero, modifier = modifier) {
        ordered.forEach { (key, m) ->
            val readout = morningReadout(m.row.payload, today, m.readings.lastOrNull())
            BandSection(METRIC_TITLES[key] ?: key.uppercase(), m, readout, today, chart = true)
        }
    }
}

@Composable
private fun BandSection(title: String, inputs: BandInputs, readout: MorningReadout?, today: LocalDate, chart: Boolean) {
    val p = inputs.row.payload
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(title, style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.weight(1f))
            if (readout != null) FTStatePill(expectedStateWord(readout))
        }
        when {
            p.state == "building" -> Text(
                "The expected range is still being learnt: ${p.have} of ${p.need} mornings so far" +
                    (p.regimeStart?.let { " (since $it)" } ?: "") + ".",
                style = FTType.BodySmall,
                color = FT.TextSecondary,
            )
            readout == null -> {
                val point = bandPointFor(p, today)
                val range = if (point?.lo80 != null && point.hi80 != null) {
                    if (p.unit == "h") "%.1f–%.1f h".format(point.lo80, point.hi80) else "%.0f–%.0f %s".format(point.lo80, point.hi80, p.unit)
                } else {
                    null
                }
                Text(
                    "No reading yet this morning." + (range?.let { " Expected $it: 8 mornings in 10 fall inside." } ?: ""),
                    style = FTType.BodySmall,
                    color = FT.TextSecondary,
                )
            }
            else -> {
                Text(readout.headline(), style = FTType.RowTitle, color = FT.TextPrimary)
                Text(readout.bandLine(), style = FTType.Caption, color = FT.TextSecondary)
                if (readout.excluded) {
                    Text("Illness logged: shown against the band, not judged.", style = FTType.Caption, color = FT.TextSecondary)
                } else {
                    readout.rarityLine()?.let { Text(it, style = FTType.Caption, color = FT.TextSecondary) }
                    readout.flagLine()?.let {
                        val unfavorable = expectedStateWord(readout).state == MetricState.Warning
                        Text(it, style = FTType.Caption, color = if (unfavorable) FT.Warning else FT.TextSecondary)
                    }
                }
            }
        }
        if (chart && p.state != "building") BandChart(p, inputs.readings, readout, today)
    }
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
    val c = p.calibration
    if (c.days >= 30 && c.inside80 != null) {
        Text(
            "Over the last ${c.days} mornings the band held %.0f%% of them (it should hold about 80%%).".format(c.inside80 * 100),
            style = FTType.Micro,
            color = FT.TextMuted,
        )
    }
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
