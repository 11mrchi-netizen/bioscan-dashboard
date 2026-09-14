package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.components.ScreenHeader
import com.bioscan.fieldterminal.ui.nav.StatusSubTab
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles

// Step 3 nav mechanism + Step 4 visual styling. Real per-sub-tab content is
// Phase C (Steps 5-10) -- still placeholders here.
@Composable
fun StatusScreen() {
    var selectedSubTab by remember { mutableStateOf(StatusSubTab.Nutrition) }

    Column(modifier = Modifier
        .fillMaxSize()
        .background(FieldColors.Ground)) {
        ScreenHeader(title = "STATUS", context = "ALL SYSTEMS")

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusSubTab.entries.forEach { subTab ->
                SubTabChip(
                    label = subTab.label,
                    selected = subTab == selectedSubTab,
                    onClick = { selectedSubTab = subTab },
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Placeholder per sub-tab -- Steps 5-10 replace each of these
            // with real Supabase-backed content.
            Text(
                text = "${selectedSubTab.label} — placeholder",
                style = FieldTextStyles.placeholderBody,
                color = FieldColors.InkMuted,
            )
        }
    }
}

// design/README.md's "Status sub-tab rail" chip -- square, not Material3's
// default rounded FilterChip, so built directly rather than restyled from it.
@Composable
private fun SubTabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val (textColor, borderColor, background) = if (selected) {
        Triple(FieldColors.Amber, FieldColors.Amber, FieldColors.Amber.copy(alpha = 0.1f))
    } else {
        Triple(FieldColors.InkMuted, FieldColors.Hairline, androidx.compose.ui.graphics.Color.Transparent)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .defaultMinSize(minHeight = 48.dp) // was ~30dp before -- under Android's 48dp minimum touch target
            .border(1.dp, borderColor)
            .background(background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text = label, style = FieldTextStyles.subTabLabel, color = textColor)
    }
}
