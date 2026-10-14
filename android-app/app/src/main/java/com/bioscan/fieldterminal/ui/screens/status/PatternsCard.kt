package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.bioscan.fieldterminal.domain.PatternsView
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import androidx.compose.ui.unit.dp

// Analysis Layer 3 / Phase 3 (read-only). Results of the fixed relationship catalogue published by the analysis-run Edge
// Function. Only relationships that survive the correction are listed; the others are counted.
@Composable
fun PatternsCard(view: PatternsView) {
    FTCard(
        title = "PATTERNS",
        info = "A fixed set of questions about your own data, each tested once and adjusted for day of week. A pattern is listed only if it survives correction for the number of questions asked. Observational: this cannot show cause.",
        methodology = "relationship_engine",
    ) {
        when {
            view.total == 0 -> Text("Not analysed yet. The nightly analysis publishes these.", style = FTType.BodySmall, color = FT.TextSecondary)
            view.supported.isEmpty() -> Text("No relationship has survived correction yet.", style = FTType.BodySmall, color = FT.TextSecondary)
            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                view.supported.forEach { row ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(row.payload.label, style = FTType.Label, color = FT.TextPrimary)
                        Text(row.payload.summary, style = FTType.BodySmall, color = FT.TextSecondary)
                    }
                }
            }
        }
        if (view.total > 0) {
            Text(
                "${view.notDistinguishable} tested, not distinguishable from noise · ${view.insufficient} need more data",
                style = FTType.Caption,
                color = FT.TextMuted,
            )
        }
    }
}
