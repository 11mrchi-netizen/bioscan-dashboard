package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles

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

@Composable
fun SubTabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val (textColor, borderColor, background) = if (selected) {
        Triple(FieldColors.Amber, FieldColors.Amber, FieldColors.Amber.copy(alpha = 0.1f))
    } else {
        Triple(FieldColors.InkMuted, FieldColors.Hairline, Color.Transparent)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .defaultMinSize(minHeight = 48.dp) // under Android's 48dp minimum touch target
            .border(1.dp, borderColor)
            .background(background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text = label, style = FieldTextStyles.subTabLabel, color = textColor)
    }
}
