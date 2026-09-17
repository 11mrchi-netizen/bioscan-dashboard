package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles

enum class SyncState(val label: String, val dotColor: androidx.compose.ui.graphics.Color) {
    Synced("SYNCED", FieldColors.Green),
    Syncing("SYNCING", FieldColors.Green),
    Offline("OFFLINE", FieldColors.Amber),
}

// design/README.md's "Screen header": title + context line, bottom border
// 2dp amber, a sync pill on the right. Shared across all 4 top-level screens
// -- one implementation, not copy-pasted per screen.
@Composable
fun ScreenHeader(
    title: String,
    context: String,
    syncState: SyncState = SyncState.Synced,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(FieldColors.Panel)
            .padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 14.dp)
            .drawBehind {
                val strokeWidth = 2.dp.toPx()
                drawLine(
                    color = FieldColors.Amber,
                    start = Offset(0f, size.height - strokeWidth / 2),
                    end = Offset(size.width, size.height - strokeWidth / 2),
                    strokeWidth = strokeWidth,
                )
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        // weight(1f) here, not an unconstrained Column -- a long context
        // string (the new tile pages' "NUTRITION + HYDRATION + SUPPLEMENTS"
        // vs. the original 4 screens' short "ALL SYSTEMS" etc.) otherwise
        // squeezes SyncPill's own width down toward zero instead of
        // wrapping itself, a real layout bug caught live on the Fuel tile.
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = FieldTextStyles.headerTitle, color = FieldColors.Amber)
            Text(
                text = context,
                style = FieldTextStyles.headerContext,
                color = FieldColors.InkMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        SyncPill(syncState)
    }
}

@Composable
private fun SyncPill(state: SyncState) {
    Row(
        modifier = Modifier
            .border(1.dp, FieldColors.Hairline)
            .background(FieldColors.RaisedSurface)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlowDot(state.dotColor)
        Text(text = state.label, style = FieldTextStyles.syncLabel, color = FieldColors.InkMuted)
    }
}

// Approximates the mockup's `box-shadow:0 0 8px` glow -- a soft radial
// gradient behind the solid square rather than a true blur (native recreation
// of the visual intent, not a pixel-identical CSS box-shadow port).
@Composable
private fun GlowDot(color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .size(14.dp)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0f))),
                    blendMode = BlendMode.Plus,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(color),
        )
    }
}
