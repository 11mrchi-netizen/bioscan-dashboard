package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.ExperimentResultRow
import com.bioscan.fieldterminal.data.ExperimentRow
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Analysis Layer 3 / Phase 5 (read-only). The person's registered experiments and the latest published result of each. While
// an experiment runs the numbers are interim and carry no verdict; the verdict appears when the last phase ends.
@Composable
fun ExperimentsSection(experiments: List<ExperimentRow>, results: Map<String, ExperimentResultRow>) {
    if (experiments.isEmpty()) return
    FTCard(
        title = "EXPERIMENTS",
        info = "Pre-registered supplement tests: you without it (A) versus with it (B), adjusted for day of week, with the smallest difference the design could see. It compares your own phases, so it cannot show cause for anyone else and a trend in the without-phase can mimic an effect (flagged when seen).",
        methodology = "supplement_experiment",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            experiments.filter { it.status != "abandoned" }.forEach { e ->
                val res = results[e.id]
                val primary = res?.payload?.metrics?.firstOrNull()
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${e.name} · ${e.design}", style = FTType.Label, color = FT.TextPrimary)
                    val phases = e.protocol?.phases.orEmpty()
                    if (phases.isNotEmpty()) Text("${phases.first().start} to ${phases.last().end} · status ${e.status}", style = FTType.Caption, color = FT.TextMuted)
                    when {
                        res == null -> Text("Not analysed yet. The nightly analysis publishes this.", style = FTType.BodySmall, color = FT.TextSecondary)
                        primary == null -> Text("No usable data yet.", style = FTType.BodySmall, color = FT.TextSecondary)
                        else -> {
                            Text(primary.summary, style = FTType.BodySmall, color = FT.TextSecondary, modifier = Modifier)
                            val a = res.payload.adherence
                            a.rate?.let { Text("Logged on ${a.interventionLogged} of ${a.interventionDays} with-phase days", style = FTType.Caption, color = FT.TextMuted) }
                            primary.notes.forEach { Text(it, style = FTType.Caption, color = FT.TextMuted) }
                        }
                    }
                }
            }
        }
    }
}
