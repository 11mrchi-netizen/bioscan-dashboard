package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// Polish brief, chart consistency: the pieces every chart frame shares, so a chart reads the same on every page.
// The order around a chart is: card title (FTCard / Section) → the latest reading and its date (FTLatestReading) → the
// window it covers and the summary over it → the chart (its unit on the axis) → the legend (FTLegend) when it has more
// than one series → a note when the data is sparse or missing (FTChartNote). Dates are one format everywhere.

object ChartDates {
    private val AXIS = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val FULL = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    /** On an axis, a legend or a short label: "14 Aug". */
    fun short(d: LocalDate): String = d.format(AXIS)

    /** A reading's date in full: "14 Aug 2026". */
    fun full(d: LocalDate): String = d.format(FULL)

    /** A reading's date relative to today when that is clearer: "today", "yesterday", else [short] (+ year if not this year). */
    fun reading(d: LocalDate, today: LocalDate = LocalDate.now()): String = when (d) {
        today -> "today"
        today.minusDays(1) -> "yesterday"
        else -> if (d.year == today.year) short(d) else full(d)
    }
}

/** The latest reading above a chart: the value in its metric's ink, the unit, and when it was taken. */
@Composable
fun FTLatestReading(value: String, unit: String?, date: LocalDate?, modifier: Modifier = Modifier, ink: Color = FT.TextPrimary) {
    val words = listOfNotNull(value, unit, date?.let { "on " + ChartDates.reading(it) }).joinToString(" ")
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = "Latest $words" },
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(value, style = FTType.MetricMedium, color = ink)
        unit?.let { Text(it, style = FTType.MonoCaption, color = FT.TextMuted, modifier = Modifier.padding(bottom = 4.dp)) }
        date?.let { Text("· ${ChartDates.reading(it)}", style = FTType.MonoCaption, color = FT.TextMuted, modifier = Modifier.padding(bottom = 4.dp)) }
    }
}

/** One legend for every multi-series chart: a dot (or a dash for a dashed series) and the name; wraps on a narrow phone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FTLegend(items: List<Pair<String, Color>>, modifier: Modifier = Modifier, dashed: Set<String> = emptySet()) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((label, color) in items) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (label in dashed) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        repeat(2) { Box(Modifier.size(width = 5.dp, height = 2.dp).background(color)) }
                    }
                } else {
                    Box(Modifier.size(9.dp).background(color, CircleShape))
                }
                Text(label, style = FTType.Caption, color = FT.TextSecondary)
            }
        }
    }
}

/** What a chart cannot show: no readings in the window, too few to draw a line, or readings months apart. */
@Composable
fun FTChartNote(text: String, modifier: Modifier = Modifier) =
    Text(text, style = FTType.Caption, color = FT.TextMuted, modifier = modifier)
