package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.LabWatch
import com.bioscan.fieldterminal.domain.formatAmount
import com.bioscan.fieldterminal.domain.labReadingWords
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.screens.detail.Section
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// The lab markers worth watching for what the person takes (domain/LabWatch.kt), each with the latest result or "never
// measured", the reason and advice in the source's terms, and the source one tap away. Shown on ME › BLOODWORK for the
// whole roster and in an item's sheet for that item.

@Composable
fun LabWatchSection(watches: List<LabWatch>, title: String = "WATCH FOR WHAT YOU TAKE", showItem: Boolean = true) {
    if (watches.isEmpty()) return
    Section(title, info = "Each line is a curated rule with its source: a lab marker worth watching while taking something " +
        "in your regimen. Rules marked unreviewed have not been confirmed by you yet (REVIEW SOURCES on the supplements page).") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { watches.forEach { WatchLine(it, showItem) } }
    }
}

@Composable
private fun WatchLine(w: LabWatch, showItem: Boolean) {
    val uri = LocalUriHandler.current
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.ftClickable { open = !open }) {
        val daily = w.dailyAmount?.let { " · ${formatAmount(Math.round(it * 10) / 10.0)} ${w.rule.amountUnit} a day" } ?: ""
        Text((if (showItem) "${w.itemName}$daily → " else "") + w.rule.markerLabel, style = FTType.RowTitle, color = FT.TextPrimary)
        if (w.latest.isEmpty()) {
            Text("Never measured", style = FTType.Caption, color = FT.Warn)
        } else {
            w.latest.take(3).forEach { Text(labReadingWords(it), style = FTType.Caption, color = FT.TextSecondary) }
        }
        Text(w.rule.advice, style = FTType.BodySmall, color = FT.TextSecondary)
        val tag = when {
            w.rule.disputedNote != null -> "Disputed by you" + (w.rule.disputedNote.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
            w.rule.reviewed -> "Reviewed"
            else -> "Unreviewed"
        }
        Text("$tag · ${if (open) "less" else "why and source ›"}", style = FTType.MonoCaption, color = if (w.rule.reviewed) FT.TextMuted else FT.Warn)
        if (open) {
            Text(w.rule.reason, style = FTType.Caption, color = FT.TextSecondary)
            w.rule.caveats?.let { Text("Caveats: $it", style = FTType.Caption, color = FT.TextMuted) }
            Text("Source: ${w.rule.source}" + (w.rule.pmidDoi?.let { " · $it" } ?: ""), style = FTType.Caption,
                color = if (w.rule.sourceUrl != null) FT.Ok else FT.TextMuted,
                modifier = Modifier.padding(top = 2.dp).ftClickable(enabled = w.rule.sourceUrl != null) { w.rule.sourceUrl?.let { runCatching { uri.openUri(it) } } })
        }
    }
}
