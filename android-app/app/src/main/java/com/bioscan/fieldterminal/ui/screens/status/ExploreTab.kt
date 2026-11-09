package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.correlate
import com.bioscan.fieldterminal.domain.correlationWords
import com.bioscan.fieldterminal.domain.score.ScoreSpecs
import com.bioscan.fieldterminal.domain.score.Series
import com.bioscan.fieldterminal.domain.score.formatInputValue
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.screens.detail.Chip
import com.bioscan.fieldterminal.ui.screens.detail.EmptyLine
import com.bioscan.fieldterminal.ui.screens.detail.Section
import com.bioscan.fieldterminal.ui.screens.today.TodayStore
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate

// LAB › EXPLORE: any two of the daily series the scores are built from, the second a chosen number of days later, as a
// scatter with Pearson's r, its interval and the number of days (domain/Correlation.kt). Read from the series TODAY
// keeps on disk, so it opens at once and costs no extra reads.

private const val EXPLORE_INFO =
    "Each dot is a day: the first measure that day across, the second measure that many days later up. r runs from −1 " +
        "to 1; the 95% interval says how sure that is, and with few days it is wide. A link here is an association in your " +
        "own data, not proof that one causes the other: something else (a busy week, an illness) can move both. Pick the " +
        "lag before looking; trying every lag until one stands out finds links that are not there."

private val WINDOWS = listOf(90 to "90 D", 180 to "180 D", 365 to "1 Y")

@Composable
fun ExploreTab() {
    val context = LocalContext.current
    val series by produceState<Map<String, Series>?>(null) {
        val today = LocalDate.now()
        value = (TodayStore.forDay(today)?.view ?: TodayStore.readDisk(context, today)?.view
            ?: TodayStore.readDisk(context, today.minusDays(1))?.view)?.series ?: emptyMap()
    }
    val labels = remember {
        (ScoreSpecs.ALL.values + ScoreSpecs.LOAD_FATIGUE).flatMap { d -> d.groups.flatMap { g -> g.inputs.map { it.key to it.label } } }.toMap()
    }
    var xKey by rememberSaveable { mutableStateOf("fuel.drinks") }
    var yKey by rememberSaveable { mutableStateOf("recovery.hrv") }
    var lag by rememberSaveable { mutableIntStateOf(1) }
    var window by rememberSaveable { mutableIntStateOf(180) }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val s = series
        if (s == null) { EmptyLine("Loading…"); return@Column }
        val available = labels.filterKeys { k -> (s[k]?.size ?: 0) >= 10 }.toList().sortedBy { it.second }
        if (available.size < 2) { EmptyLine("Open TODAY once so the scores are built; the measures appear here from them."); return@Column }
        if (xKey !in s) xKey = available.first().first
        if (yKey !in s) yKey = available.last().first

        Section("COMPARE", info = EXPLORE_INFO) {
            PickerRow("FIRST", labels[xKey] ?: xKey) { picking = if (picking == "x") null else "x" }
            if (picking == "x") Options(available, xKey) { xKey = it; picking = null }
            PickerRow("THEN", labels[yKey] ?: yKey) { picking = if (picking == "y") null else "y" }
            if (picking == "y") Options(available, yKey) { yKey = it; picking = null }
            Text("DAYS LATER", style = FTType.Micro, color = FT.TextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { (0..3).forEach { l -> Chip(if (l == 0) "SAME DAY" else "+$l", l == lag) { lag = l } } }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { WINDOWS.forEach { (d, label) -> Chip(label, d == window) { window = d } } }
        }

        val from = LocalDate.now().minusDays(window.toLong())
        val c = remember(s, xKey, yKey, lag, window) { correlate(s[xKey].orEmpty(), s[yKey].orEmpty(), lag, from) }
        Section("${(labels[xKey] ?: xKey).uppercase()} → ${(labels[yKey] ?: yKey).uppercase()}${if (lag > 0) " +$lag D" else ""}") {
            if (c == null) {
                EmptyLine("Fewer than 10 days have both in this window.")
                return@Section
            }
            Text(correlationWords(c), style = FTType.BodySmall, color = if (c.clear) FT.TextPrimary else FT.TextSecondary)
            Scatter(c.pairs.map { it.second to it.third })
            val xs = c.pairs.map { it.second }
            val ys = c.pairs.map { it.third }
            Text("Across: ${formatInputValue(xKey, xs.min())} to ${formatInputValue(xKey, xs.max())} · up: ${formatInputValue(yKey, ys.min())} to ${formatInputValue(yKey, ys.max())}",
                style = FTType.Caption, color = FT.TextMuted)
            Text("An association in your own days, not proof of cause.", style = FTType.Caption, color = FT.TextMuted)
        }
    }
}

@Composable
private fun PickerRow(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().ftClickable(onClick = onClick).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.padding(top = 2.dp))
        Text("$value ▾", style = FTType.RowTitle, color = FT.TextPrimary)
    }
}

@Composable
private fun Options(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { (k, label) -> Chip(label, k == selected) { onPick(k) } } }
        }
    }
}

@Composable
private fun Scatter(points: List<Pair<Double, Double>>) {
    val dot = FT.Emerald
    val axis = FT.GlassBorder
    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        val xs = points.map { it.first }
        val ys = points.map { it.second }
        val (x0, x1) = xs.min() to xs.max()
        val (y0, y1) = ys.min() to ys.max()
        fun px(v: Double) = if (x1 > x0) ((v - x0) / (x1 - x0)).toFloat() * (size.width - 12f) + 6f else size.width / 2
        fun py(v: Double) = if (y1 > y0) size.height - 6f - ((v - y0) / (y1 - y0)).toFloat() * (size.height - 12f) else size.height / 2
        drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
        drawLine(axis, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 2f)
        points.forEach { (x, y) -> drawCircle(dot.copy(alpha = 0.6f), radius = 4.dp.toPx(), center = Offset(px(x), py(y))) }
    }
}
