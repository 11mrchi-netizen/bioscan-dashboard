package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// One component of a composite score: label, its own 0..max score, and a bar,
// so a composite ("78 / 100") is never opaque -- you can see which parts hold
// it up or drag it down. `max` is the score's real bound (100 for the index
// components), never a guessed ceiling.
@Composable
fun FTScoreRow(
    label: String,
    score: Double,
    color: Color,
    modifier: Modifier = Modifier,
    max: Double = 100.0,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = FTType.Body, color = FT.TextSecondary, modifier = Modifier.weight(1f))
            Text("%.0f".format(score), style = FTType.Telemetry, color = FT.TextPrimary)
        }
        FTBar(fraction = (score / max).toFloat(), color = color, height = 6.dp)
    }
}
