package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.NutrientBreakdownData
import com.bioscan.fieldterminal.data.NutrientBreakdownRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.NUTRIENT_REFERENCE
import com.bioscan.fieldterminal.domain.NutrientCategory
import com.bioscan.fieldterminal.domain.NutrientInfo
import com.bioscan.fieldterminal.domain.nutrientDisplayName
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.SegmentedToggle
import com.bioscan.fieldterminal.ui.components.TileHeader
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter
import com.bioscan.fieldterminal.ui.theme.RobotoMono
import kotlin.math.roundToInt

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

@Composable
fun NutrientBreakdownScreen(onBack: () -> Unit) {
    var timeRange by remember { mutableStateOf(TimeRange.Today) }
    var valueMode by remember { mutableStateOf(ValueMode.Total) }
    var data by remember { mutableStateOf<NutrientBreakdownData?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(timeRange) {
        isLoading = true
        data = NutrientBreakdownRepository(SupabaseClientProvider.client).loadNutrients(timeRange.days)
        isLoading = false
    }

    Column(modifier = Modifier.fillMaxSize().background(FT.Base).verticalScroll(rememberScrollState())) {
        TileHeader(onBack = onBack)

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp)) {
            Text(
                "NUTRIENT BREAKDOWN",
                style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 18.sp),
                color = FT.TextPrimary,
            )
            Spacer(Modifier.height(12.dp))

            SegmentedToggle(
                options = TimeRange.entries,
                selected = timeRange,
                labelOf = { it.label },
                onSelect = { timeRange = it },
            )
            Spacer(Modifier.height(8.dp))
            SegmentedToggle(
                options = ValueMode.entries,
                selected = valueMode,
                labelOf = { it.label },
                onSelect = { valueMode = it },
            )
        }

        when {
            isLoading -> Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = FT.DomainFuel)
            }
            data != null -> {
                val d = data!!
                val divisor = if (valueMode == ValueMode.Average) d.daysCovered.toDouble() else 1.0
                val rdaMultiplier = if (valueMode == ValueMode.Total) d.daysCovered else 1

                if (d.nutrients.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "No nutrient data logged for this period.",
                            style = TextStyle(fontFamily = Inter, fontSize = 15.sp),
                            color = FT.TextSecondary,
                        )
                    }
                } else {
                    val periodNote = when (timeRange) {
                        TimeRange.Today -> "today"
                        else -> "last ${timeRange.days} days (${d.daysCovered} day${if (d.daysCovered != 1) "s" else ""} with data)"
                    }
                    val modeNote = if (valueMode == ValueMode.Average) "daily average" else "accumulated total"
                    Text(
                        "$modeNote — $periodNote",
                        style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp),
                        color = FT.TextMuted,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
                    )

                    NutrientCategory.entries.forEach { category ->
                        val nutrients = nutrientsForCategory(category, d.nutrients)
                        if (nutrients.isNotEmpty()) {
                            NutrientCategoryCard(
                                category = category,
                                nutrients = nutrients,
                                divisor = divisor,
                                rdaMultiplier = rdaMultiplier,
                            )
                        }
                    }

                    Text(
                        "RDA values shown are adult male reference intakes (NASEM DRI). " +
                            "They are general guides, not personal targets.",
                        style = TextStyle(fontFamily = Inter, fontSize = 12.sp),
                        color = FT.TextMuted,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NutrientCategoryCard(
    category: NutrientCategory,
    nutrients: List<Pair<NutrientInfo, Double>>,
    divisor: Double,
    rdaMultiplier: Int,
) {
    FTCard(
        title = category.label,
        modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
    ) {
        nutrients.forEach { (info, rawAmount) ->
            val amount = rawAmount / divisor
            NutrientRow(info = info, amount = amount, rdaMultiplier = rdaMultiplier)
        }
    }
}

@Composable
private fun NutrientRow(info: NutrientInfo, amount: Double, rdaMultiplier: Int) {
    val effectiveRda = info.rda?.times(rdaMultiplier)
    val effectiveUL = info.upperLimit?.times(rdaMultiplier)

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                info.displayName,
                style = TextStyle(fontFamily = Inter, fontSize = 14.sp),
                color = FT.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                formatAmount(amount, info.unit),
                style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Bold, fontSize = 14.sp),
                color = FT.TextPrimary,
                textAlign = TextAlign.End,
            )
        }

        if (effectiveRda != null || effectiveUL != null) {
            val rangeLabel = when {
                effectiveRda != null && effectiveUL != null ->
                    "Range: ${formatAmount(effectiveRda, info.unit)} – ${formatAmount(effectiveUL, info.unit)}"
                effectiveRda != null ->
                    "Range: ≥ ${formatAmount(effectiveRda, info.unit)}"
                else ->
                    "Range: < ${formatAmount(effectiveUL!!, info.unit)}"
            }
            Text(
                rangeLabel,
                style = TextStyle(fontFamily = Inter, fontSize = 12.sp),
                color = FT.TextMuted,
            )
            Spacer(Modifier.height(4.dp))
            NutrientBar(amount = amount, rda = effectiveRda, upperLimit = effectiveUL)
        }
    }
}

@Composable
private fun NutrientBar(amount: Double, rda: Double?, upperLimit: Double?) {
    val maxRef = listOfNotNull(rda, upperLimit, amount).max() * 1.2
    if (maxRef <= 0) return

    val barColor = when {
        upperLimit != null && amount > upperLimit -> FT.Warning
        rda != null && amount >= rda -> FT.Emerald
        rda != null && amount >= rda * 0.7 -> FT.Emerald.copy(alpha = 0.7f)
        else -> FT.DomainFuel
    }

    Canvas(modifier = Modifier.fillMaxWidth().height(30.dp)) {
        val barH = 14.dp.toPx()
        val cy = size.height / 2f
        val cr = barH / 2f

        drawRoundRect(
            color = FT.GlassTrack,
            topLeft = Offset(0f, cy - barH / 2f),
            size = Size(size.width, barH),
            cornerRadius = CornerRadius(cr),
        )

        val fraction = (amount / maxRef).toFloat().coerceIn(0f, 1f)
        val fillW = fraction * size.width
        if (fillW > 0f) {
            drawRoundRect(
                color = barColor,
                topLeft = Offset(0f, cy - barH / 2f),
                size = Size(fillW.coerceAtLeast(barH), barH),
                cornerRadius = CornerRadius(cr),
            )
        }

        val markerR = 10.dp.toPx()
        val markerX = fillW.coerceIn(markerR, size.width - markerR)
        drawCircle(color = barColor, radius = markerR, center = Offset(markerX, cy))

        rda?.let {
            val x = (it / maxRef).toFloat().coerceIn(0f, 1f) * size.width
            drawLine(FT.TextSecondary, Offset(x, cy - barH * 0.7f), Offset(x, cy + barH * 0.7f), strokeWidth = 2.5f)
        }
        upperLimit?.let {
            val x = (it / maxRef).toFloat().coerceIn(0f, 1f) * size.width
            drawLine(FT.Warning.copy(alpha = 0.6f), Offset(x, cy - barH * 0.7f), Offset(x, cy + barH * 0.7f), strokeWidth = 2.5f)
        }
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
