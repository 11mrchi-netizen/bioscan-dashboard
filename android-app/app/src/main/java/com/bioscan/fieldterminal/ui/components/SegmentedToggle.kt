package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// DAV-115/123. Generalized from what was three near-identical copies
// (PeriodToggle, the old Vo2MaxTimeframeToggle, and a metric-tab row this
// rework added) into one -- same look, same behavior, any enum/list of
// options. PeriodToggle/TotalsPeriod stay as their own thing (shared with
// the Nutrition tab, which this rework doesn't touch); new call sites use
// this instead of adding a fourth copy.
// v1 `.ft-seg`: one faint glass track with a hairline; the selected segment is platinum with dark ink. [accentColor] is
// kept for old call sites and no longer tints anything: selection is platinum app-wide, colour belongs to metrics.
@Composable
fun <T> SegmentedToggle(
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") accentColor: Color = FT.TextPrimary,
) {
    val outer = RoundedCornerShape(FT.RadiusSmall)
    val inner = RoundedCornerShape(9.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0x0DFFFFFF), outer)
            .border(FT.BorderWidth, FT.Line, outer)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 40.dp)
                    .then(if (isSelected) Modifier.background(FT.Platinum, inner) else Modifier)
                    .ftClickable(shape = inner, role = androidx.compose.ui.semantics.Role.Tab) { onSelect(option) }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    labelOf(option),
                    style = if (isSelected) FTType.LabelCaps.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) else FTType.LabelCaps,
                    color = if (isSelected) FT.PlatinumInk else FT.TextSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}
