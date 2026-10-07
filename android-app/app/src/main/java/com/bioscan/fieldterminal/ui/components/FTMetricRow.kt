package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.RobotoMono

// The one label/value row (replaces seven private StatLine copies). Label is
// unweighted so it never shrinks; the value takes the rest of the row and
// wraps within its own width, right-aligned. For supporting detail only --
// a primary value belongs in FTMetricValue.
@Composable
fun FTMetricRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        Text(label, style = FTType.Body, color = FT.TextSecondary)
        Text(
            value,
            style = FTType.Body.copy(fontFamily = RobotoMono),
            color = FT.TextPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
    }
}
