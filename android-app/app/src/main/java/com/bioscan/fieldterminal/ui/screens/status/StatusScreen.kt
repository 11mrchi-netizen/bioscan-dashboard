package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.nav.StatusSubTab

// Step 3 scaffold: navigation mechanism only. Real content (readiness
// overview, per-sub-tab live data) is Phase C -- Steps 5-10 -- and real
// visual styling is Phase B (Step 4), gated on /design/ mockups.
@Composable
fun StatusScreen() {
    var selectedSubTab by remember { mutableStateOf(StatusSubTab.Nutrition) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusSubTab.entries.forEach { subTab ->
                FilterChip(
                    selected = subTab == selectedSubTab,
                    onClick = { selectedSubTab = subTab },
                    label = { Text(subTab.label) },
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Placeholder per sub-tab -- Steps 5-10 replace each of these
            // with real Supabase-backed content.
            Text("${selectedSubTab.label} — placeholder")
        }
    }
}
