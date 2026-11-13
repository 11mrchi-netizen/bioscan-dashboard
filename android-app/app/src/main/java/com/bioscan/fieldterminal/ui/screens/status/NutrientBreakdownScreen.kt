package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.NutrientBreakdownData
import com.bioscan.fieldterminal.data.NutrientBreakdownRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.NUTRIENT_REFERENCE
import com.bioscan.fieldterminal.domain.NutrientCategory
import com.bioscan.fieldterminal.domain.NutrientInfo
import com.bioscan.fieldterminal.domain.nutrientDisplayName
import com.bioscan.fieldterminal.ui.components.LoadErrorCard
import com.bioscan.fieldterminal.ui.components.drawZonedTrack
import com.bioscan.fieldterminal.ui.components.drawZonedValue
import com.bioscan.fieldterminal.ui.components.rememberLoadGuard
import com.bioscan.fieldterminal.ui.components.zoneEdges
import com.bioscan.fieldterminal.ui.components.zonedFraction
import com.bioscan.fieldterminal.ui.screens.detail.Chip
import com.bioscan.fieldterminal.ui.screens.detail.DetailScreen
import com.bioscan.fieldterminal.ui.screens.detail.EmptyLine
import com.bioscan.fieldterminal.ui.screens.detail.Loading
import com.bioscan.fieldterminal.ui.screens.detail.Section
import com.bioscan.fieldterminal.ui.theme.FTType
import kotlin.math.roundToInt
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

private enum class TimeRange(val label: String, val days: Int) {
    Today("1D", 1),
    Week("7D", 7),
    Month("28D", 28),
    Quarter("90D", 90),
}

private enum class ValueMode(val label: String) {
    Total("TOTAL"),
    Average("AVG/DAY"),
}

// The page follows the shared detail kit (ui/screens/detail/DetailKit.kt): the DetailScreen header, chips for the window
// and the value mode, one glass Section per category, and slim one-line bars like ShareBars, here on the zoned scale.

private const val BREAKDOWN_INFO =
    "Each bar is on the same scale as the other nutrition bars: the first 70 % runs up to the reference intake (RDA), the " +
        "green part is the RDA to the upper limit, the grey end is over the limit. With no upper limit the last 30 % is the " +
        "good range; with only a limit, everything under it is. In TOTAL the reference is multiplied by the days with data. " +
        "Reference values are adult male intakes (NASEM DRI): general guides, not personal targets."

@Composable
fun NutrientBreakdownScreen(onBack: () -> Unit) {
    var timeRange by remember { mutableStateOf(TimeRange.Today) }
    var valueMode by remember { mutableStateOf(ValueMode.Total) }
    var data by remember { mutableStateOf<NutrientBreakdownData?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    val guard = rememberLoadGuard()
    LaunchedEffect(timeRange, guard.attempt) {
        isLoading = true
        guard.run { data = NutrientBreakdownRepository(SupabaseClientProvider.client).loadNutrients(timeRange.days) }
        isLoading = false
    }

    DetailScreen("NUTRIENT BREAKDOWN", onBack, eyebrow = "FUEL", domain = FT.Domain.Fuel, ranged = false) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TimeRange.entries.forEach { r -> Chip(r.label, r == timeRange) { timeRange = r } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ValueMode.entries.forEach { m -> Chip(m.label, m == valueMode) { valueMode = m } }
        }
        val d = data
        when {
            guard.error != null && !isLoading -> LoadErrorCard(guard)
            isLoading || d == null -> Loading(guard)
            d.nutrients.isEmpty() -> EmptyLine("No nutrient data logged for this period.")
            else -> {
                val divisor = if (valueMode == ValueMode.Average) d.daysCovered.toDouble() else 1.0
                val rdaMultiplier = if (valueMode == ValueMode.Total) d.daysCovered else 1
                val periodNote = when (timeRange) {
                    TimeRange.Today -> "today"
                    else -> "last ${timeRange.days} days (${d.daysCovered} day${if (d.daysCovered != 1) "s" else ""} with data)"
                }
                val modeNote = if (valueMode == ValueMode.Average) "Daily average" else "Total"
                Text("$modeNote · $periodNote", style = FTType.Caption, color = FT.TextMuted)
                NutrientCategory.entries.forEach { category ->
                    val nutrients = nutrientsForCategory(category, d.nutrients)
                    if (nutrients.isNotEmpty()) {
                        Section(category.label.uppercase(), info = BREAKDOWN_INFO) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                nutrients.forEach { (info, raw) -> NutrientRow(info, raw / divisor, rdaMultiplier) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One line: name, a slim zoned bar, the amount; the reference range in small type under the bar when there is one. */
@Composable
private fun NutrientRow(info: NutrientInfo, amount: Double, rdaMultiplier: Int) {
    val rda = info.rda?.times(rdaMultiplier)
    val ul = info.upperLimit?.times(rdaMultiplier)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(info.displayName, style = FTType.Caption, color = FT.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(110.dp))
            Box(Modifier.weight(1f)) { NutrientBar(amount, rda, ul) }
            Text(formatAmount(amount, info.unit), style = FTType.Telemetry, color = FT.TextPrimary, textAlign = TextAlign.End,
                maxLines = 1, modifier = Modifier.width(76.dp))
        }
        val range = when {
            rda != null && ul != null -> "${formatAmount(rda, info.unit)} – ${formatAmount(ul, info.unit)}"
            rda != null -> "≥ ${formatAmount(rda, info.unit)}"
            ul != null -> "< ${formatAmount(ul, info.unit)}"
            else -> null
        }
        range?.let { Text(it, style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.padding(start = 118.dp)) }
    }
}

// The same 70 / 20 / 10 layout as the other nutrition bars (ZonedScale.kt), drawn 8 dp tall like the page kit's bars.
// The fill keeps its state colour (no nutrient identity here). No reference: an empty track.
@Composable
private fun NutrientBar(amount: Double, rda: Double?, upperLimit: Double?) {
    val edges = zoneEdges(rda, upperLimit)
    val fraction = zonedFraction(amount, rda, upperLimit)
    val barColor = when {
        upperLimit != null && amount > upperLimit -> FT.Warning
        rda != null && amount >= rda -> FT.Emerald
        rda != null && amount >= rda * 0.7 -> FT.Emerald.copy(alpha = 0.7f)
        else -> FT.TextSecondary
    }
    Canvas(Modifier.fillMaxWidth().height(12.dp)) {
        val barH = 8.dp.toPx()
        val cy = size.height / 2f
        if (edges == null || fraction == null) {
            drawZonedTrack(cy, barH, 1f to 1f, FT.Emerald)
            return@Canvas
        }
        drawZonedTrack(cy, barH, edges, FT.Emerald)
        drawZonedValue(cy, barH, fraction, barColor, 4.dp.toPx())
    }
}

private fun nutrientsForCategory(
    category: NutrientCategory,
    data: Map<String, Double>,
): List<Pair<NutrientInfo, Double>> {
    val known = NUTRIENT_REFERENCE.values
        .filter { it.category == category }
        .mapNotNull { info -> data[info.key]?.let { info to it } }

    val unknown = data
        .filter { (key, _) -> key !in NUTRIENT_REFERENCE && guessCategoryForUnknown(key) == category }
        .map { (key, amount) ->
            NutrientInfo(key, nutrientDisplayName(key), "", null, null, category) to amount
        }

    return known + unknown
}

private fun guessCategoryForUnknown(key: String): NutrientCategory = NutrientCategory.Other

private fun formatAmount(value: Double, unit: String): String {
    val formatted = when {
        value >= 100 -> value.roundToInt().toString()
        value >= 10 -> "%.1f".format(value)
        value >= 1 -> "%.1f".format(value)
        value >= 0.01 -> "%.2f".format(value)
        value > 0 -> "%.3f".format(value)
        else -> "0"
    }
    return if (unit.isNotEmpty()) "$formatted $unit" else formatted
}
