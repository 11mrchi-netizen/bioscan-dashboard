package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.TypicalityView
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Analysis Layer 3 / Phase 4 (read-only, quiet drill-down). Compares today with your own recent typical days across HRV,
// resting heart rate, sleep, deep + REM sleep and steps. It is deliberately NOT part of Today's Recovery.
@Composable
fun TypicalityCard(view: TypicalityView) {
    FTCard(
        title = "IS TODAY TYPICAL?",
        info = "Compares today with your own recent typical days across several signals together. It is experimental: on real history it flags more ordinary days than its nominal share, so it is a prompt to look, not a finding, and it does not feed Today's Recovery.",
        methodology = "typicality",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(view.headline, style = FTType.Label, color = FT.TextPrimary)
            view.lines.forEach { Text(it, style = FTType.BodySmall, color = FT.TextSecondary) }
            view.scorecard?.let { (word, sentence) ->
                Text("SCORECARD · $word", style = FTType.LabelCaps, color = FT.TextSecondary)
                Text(sentence, style = FTType.Caption, color = FT.TextMuted)
            }
        }
    }
}
