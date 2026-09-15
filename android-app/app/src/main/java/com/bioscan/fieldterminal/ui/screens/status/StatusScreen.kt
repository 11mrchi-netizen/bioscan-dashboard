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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.StatusOverview
import com.bioscan.fieldterminal.data.StatusRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.ui.components.ScreenHeader
import com.bioscan.fieldterminal.ui.nav.StatusSubTab
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles

// Step 5 (Phase C): the "3d — Body console" launch screen (user's pick, see
// ROADMAP.md P8) now loads real readiness/HRV/RHR/sleep/health-flag data,
// shown above the sub-tab rail per design/README.md's own layout note ("the
// launch screen sits above the rail as the default Status view"). The 5
// sub-tabs below it are still Step 3/4 placeholders -- Steps 6-10.
@Composable
fun StatusScreen() {
    var selectedSubTab by remember { mutableStateOf(StatusSubTab.Nutrition) }
    var overview by remember { mutableStateOf<StatusOverview?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        overview = StatusRepository(SupabaseClientProvider.client).loadOverview()
        isLoading = false
    }

    Column(modifier = Modifier
        .fillMaxSize()
        .background(FieldColors.Ground)
        .verticalScroll(rememberScrollState())) {
        ScreenHeader(title = "STATUS", context = "ALL SYSTEMS")

        BodyConsole(overview = overview, isLoading = isLoading)

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

        // Fixed height, not fillMaxSize() -- the parent Column now scrolls
        // (needed once BodyConsole made this screen taller than one page),
        // and a scrolling container measures children with unbounded height.
        when (selectedSubTab) {
            StatusSubTab.Nutrition -> NutritionHydrationScreen()
            StatusSubTab.Training -> TrainingScreen()
            else -> Box(modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                // Placeholder per remaining sub-tab -- Steps 8-10 replace
                // each of these with real Supabase-backed content.
                Text(
                    text = "${selectedSubTab.label} — placeholder",
                    style = FieldTextStyles.placeholderBody,
                    color = FieldColors.InkMuted,
                )
            }
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
