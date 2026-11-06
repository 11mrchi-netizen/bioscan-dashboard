package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Extracted from StatusScreen.kt's own sub-tab rail (design/README.md's
// square chip, not Material3's default rounded FilterChip) -- First feedback
// fixes' tile-page restructure needs the exact same chip strip on 4 separate
// pages (Fuel/Heart/Labs tabs), so this is a shared component now rather than
// four copies of the same 25 lines.
@Composable
fun <T> SubTabRow(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            SubTabChip(label = label(item), selected = item == selected, onClick = { onSelect(item) })
        }
    }
}

// `.ft-chip`: a mono pill; the selected one is platinum with dark ink.
@Composable
fun SubTabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .defaultMinSize(minHeight = 40.dp)
            .then(
                if (selected) Modifier.background(FT.Platinum, shape)
                else Modifier.border(FT.BorderWidth, FT.Line, shape).background(Color(0x08FFFFFF), shape),
            )
            .ftClickable(onClick = onClick, shape = shape, role = androidx.compose.ui.semantics.Role.Tab)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = if (selected) FTType.LabelCaps.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) else FTType.LabelCaps,
            color = if (selected) FT.PlatinumInk else FT.TextSecondary,
        )
    }
}
