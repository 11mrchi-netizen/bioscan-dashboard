package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Run session page: one metric as a tile -- its label (with an optional "?"), the value and unit, and one secondary
// line (the metric that belongs with it: max HR under average HR, total calories under active calories), and an
// optional mark (an arrow and a word against the usual) between the value and that line.
data class StatTileSpec(
    val label: String,
    val value: String,
    val unit: String? = null,
    val secondary: String? = null,
    val helpTitle: String? = null,
    val helpBody: String? = null,
    val mark: TileMark? = null,
)

// The colour backs the word, never replaces it: every mark has its arrow and its word.
data class TileMark(val text: String, val color: Color)

@Composable
fun FTStatTile(spec: StatTileSpec, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(FT.RadiusModule)
    Column(
        modifier = modifier
            .clip(shape)
            .background(FT.GlassFill, shape)
            .border(FT.BorderWidth, FT.GlassBorder, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(spec.label, style = FTType.LabelCaps, color = FT.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (spec.helpTitle != null && spec.helpBody != null) InfoHelpButton(spec.helpTitle, spec.helpBody, modifier = Modifier.padding(start = 4.dp))
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(spec.value, style = FTType.MetricMedium, color = FT.TextPrimary, maxLines = 1)
            spec.unit?.let { Text(it, style = FTType.Label, color = FT.TextSecondary, modifier = Modifier.padding(bottom = 3.dp)) }
        }
        spec.mark?.let { Text(it.text, style = FTType.Label, color = it.color, maxLines = 2) }
        spec.secondary?.let { Text(it, style = FTType.Caption, color = FT.TextSecondary, maxLines = 2) }
    }
}

/** Tiles two to a row (never more), each row the height of its taller tile; an odd last tile takes half the width. */
@Composable
fun TileGrid(tiles: List<StatTileSpec>, modifier: Modifier = Modifier) {
    if (tiles.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { FTStatTile(it, Modifier.weight(1f).fillMaxHeight()) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
