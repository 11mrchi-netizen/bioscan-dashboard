package com.bioscan.fieldterminal.ui.screens.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.model.SessionFuelRow
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// FUEL on a session's page: the food around it (session_fuel, written nightly from analysis-core trainingFuel.ts,
// docs/nutrition/food-and-training.md). Read-only rows: nothing here looks tappable.

private const val FUEL_INFO = "From the meals logged around this session. Fasted means no meal of 50 kcal or more in the 4 h " +
    "before and none for 8 h. Before a key session 1–4 g of carbs per kg in the 1–4 h before helps; in a long one 30–60 g an " +
    "hour. After, about 0.3 g of protein per kg in the 2 h after. Filled in by the nightly run, so today's session shows tomorrow."

/** The lines of the card, as plain text, so they can be tested without Compose. */
internal data class FuelLine(val label: String, val value: String, val note: String?, val ink: Color?, val warn: Boolean = false)

internal fun sessionFuelLines(f: SessionFuelRow): List<FuelLine> = buildList {
    val since = f.hoursSinceMeal?.let { "%.1f h".format(it) } ?: "none in 18 h"
    add(FuelLine("Since the last meal", since, if (f.fasted) "Started fasted" else null, null, warn = f.fasted && f.long))
    add(FuelLine("Carbs in the 4 h before", "${f.carbsBeforeG} g", null, FT.Metric.Carbs))
    add(FuelLine("Carbs during", f.carbsDuringG?.let { "$it g" } ?: "none logged", if (f.long && f.carbsDuringG == null) "A long session: 30–60 g an hour helps" else null,
        if (f.carbsDuringG != null) FT.Metric.Carbs else null, warn = f.long && f.fasted && f.carbsDuringG == null))
    add(FuelLine("Protein in the 2 h after", "${f.proteinAfterG} g", if (f.proteinAfterG >= f.proteinDoseG) "Reached your ${f.proteinDoseG} g" else "Under your ${f.proteinDoseG} g",
        FT.Metric.Protein))
    add(FuelLine("Carbs in the 2 h after", "${f.carbsAfterG} g", null, FT.Metric.Carbs))
}

@Composable
fun SessionFuelCard(fuel: SessionFuelRow) {
    FTCard(title = "FUEL", info = FUEL_INFO) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            sessionFuelLines(fuel).forEach { l ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(l.label, style = FTType.Body, color = FT.TextSecondary)
                        Text(l.value, style = FTType.Value, color = l.ink ?: FT.TextPrimary, textAlign = TextAlign.End, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    }
                    l.note?.let { Text(it, style = FTType.Caption, color = if (l.warn) FT.Warn else FT.TextMuted) }
                }
            }
        }
    }
}
