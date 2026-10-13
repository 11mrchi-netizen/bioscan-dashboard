package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.DetectedInteraction
import com.bioscan.fieldterminal.domain.InteractionStatus
import com.bioscan.fieldterminal.domain.interactionEffectText
import com.bioscan.fieldterminal.domain.interactionStatusText
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Analysis Layer 3 / Phase 6. Read-only. Pairs from the curated interaction registry that apply to the active roster.
// Unreviewed entries always carry "(unreviewed)" next to the effect.

private fun DetectedInteraction.title(): String =
    if (b == null) a.name else "${a.name} + ${b.name}"

private fun DetectedInteraction.tone(): Color = when {
    !needsAttention -> FT.TextMuted
    record.severity == "major" -> FT.Critical
    record.severity == "moderate" -> FT.Warning
    else -> FT.TextSecondary
}

private fun InteractionStatus.tag(): String = when (this) {
    InteractionStatus.SAME_SLOT -> "SAME SLOT"
    InteractionStatus.TOO_CLOSE -> "TOO CLOSE"
    InteractionStatus.TIMING_UNKNOWN -> "AS NEEDED"
    InteractionStatus.ALWAYS -> "ANY TIMING"
    InteractionStatus.MISSING_PARTNER -> "CHECK"
    InteractionStatus.SPACED -> "SPACED"
    InteractionStatus.TOGETHER_OK -> "GOOD PAIR"
}

@Composable
fun InteractionsSection(detections: List<DetectedInteraction>) {
    if (detections.isEmpty()) return
    FTCard(
        title = "INTERACTIONS",
        info = "Known pairs among what you take, from a hand-curated list with a source for each. It is not a complete list and not medical advice; a missing flag does not mean a combination is safe. Entries nobody has checked against their source yet say \"(unreviewed)\".",
        methodology = "supplement_interactions",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            detections.forEach { InteractionRow(it) }
        }
    }
}

// Compact variant for the add/edit sheet: only what needs attention.
@Composable
fun InteractionNotice(detections: List<DetectedInteraction>) {
    val attention = detections.filter { it.needsAttention }
    if (attention.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("INTERACTIONS", style = FTType.LabelCaps, color = FT.Warning)
        attention.forEach { InteractionRow(it) }
    }
}

@Composable
private fun InteractionRow(d: DetectedInteraction) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("${d.title()} · ${d.status.tag()}", style = FTType.Label, color = d.tone())
        Text(interactionStatusText(d), style = FTType.Caption, color = FT.TextMuted)
        Text(interactionEffectText(d.record), style = FTType.BodySmall, color = FT.TextSecondary)
        if (d.needsAttention) Text(d.record.advice, style = FTType.BodySmall, color = FT.TextPrimary)
        val cite = d.record.pmidDoi?.takeIf { it.isNotBlank() } ?: d.record.source.takeIf { it.isNotBlank() }
        cite?.let { Text(it, style = FTType.MonoCaption, color = FT.TextMuted, modifier = Modifier) }
    }
}
