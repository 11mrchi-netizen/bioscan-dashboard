package com.bioscan.fieldterminal.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter

// Shared placeholder for Setup tabs with no feature behind them yet
// (Profile, Nutrition Goals, Notifications, Zepp) -- one card instead of
// repeating the same "not built yet" text per tab.
@Composable
fun ComingSoonCard(title: String, detail: String = "Not built yet -- coming soon.") {
    FTCard(title = title) {
        Text(detail, style = TextStyle(fontFamily = Inter, fontSize = 13.sp), color = FT.TextSecondary)
    }
}
