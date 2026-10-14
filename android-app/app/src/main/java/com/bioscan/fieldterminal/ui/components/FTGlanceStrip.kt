// BringIntoViewRequester is experimental in older Compose Foundation releases (stable in newer ones); opting in is harmless.
@file:OptIn(ExperimentalFoundationApi::class)

package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.MetricState
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.launch

// Contract A3 (design/FUTURISTIC_MATERIAL_DESIGN_CONTRACT.md, "Card emphasis"): a long tab opens with a glance strip, one
// chip per card below carrying that card's state (or its headline when it has no state); tapping a chip scrolls to the
// card. Cards on a tab load independently, so each card announces its own chip with Modifier.glanceAnchor() once it has
// something to say, and the strip shows them in `order`.
data class GlanceChip(val order: Int, val label: String, val state: MetricState? = null, val headline: String? = null)

@Stable
class GlanceAnchors internal constructor() {
    internal val chips = mutableStateMapOf<String, GlanceChip>()
    internal val requesters = mutableMapOf<String, BringIntoViewRequester>()
}

@Composable
fun rememberGlanceAnchors(): GlanceAnchors = remember { GlanceAnchors() }

// Marks a card as a glance-strip target. Put it on the card itself (or a Box around it). A null `anchors` is a no-op, so a
// card shared with a page that has no strip needs no second code path.
@Composable
fun Modifier.glanceAnchor(anchors: GlanceAnchors?, key: String, chip: GlanceChip): Modifier {
    if (anchors == null) return this
    val requester = remember { BringIntoViewRequester() }
    DisposableEffect(anchors, key) {
        anchors.requesters[key] = requester
        onDispose {
            anchors.requesters.remove(key)
            anchors.chips.remove(key)
        }
    }
    LaunchedEffect(anchors, key, chip) { anchors.chips[key] = chip }
    return this.bringIntoViewRequester(requester)
}

// `contentPadding`: the default suits a strip at the page edge; inside an already padded column pass PaddingValues(0.dp).
@Composable
fun FTGlanceStrip(anchors: GlanceAnchors, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(horizontal = 22.dp, vertical = 10.dp)) {
    val chips = anchors.chips.entries.sortedBy { it.value.order }
    // One card needs no index.
    if (chips.size < 2) return
    val scope = rememberCoroutineScope()
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEach { (key, chip) ->
            GlanceChipView(chip) {
                // A target taller than any screen: the scroller then lines the card's top up with the top of the view
                // (BringIntoViewSpec's default) instead of stopping as soon as the card's bottom edge shows.
                scope.launch { anchors.requesters[key]?.bringIntoView(Rect(0f, 0f, 1f, 100_000f)) }
            }
        }
    }
}

@Composable
private fun GlanceChipView(chip: GlanceChip, onClick: () -> Unit) {
    val shape = RoundedCornerShape(FT.RadiusSmall)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(FT.GlassFill)
            .border(FT.BorderWidth, FT.GlassBorder, shape)
            .ftClickable(shape = shape, role = Role.Button, onClickLabel = "Go to ${chip.label.lowercase()}", onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chip.state?.let { Box(Modifier.size(8.dp).clip(CircleShape).background(metricStateColor(it))) }
        Text(chip.label, style = FTType.LabelCaps, color = FT.TextSecondary, maxLines = 1)
        val value = chip.headline ?: chip.state?.let { metricStateLabel(it) }
        value?.let { Text(it, style = FTType.Label, color = FT.TextPrimary, maxLines = 1) }
    }
}
