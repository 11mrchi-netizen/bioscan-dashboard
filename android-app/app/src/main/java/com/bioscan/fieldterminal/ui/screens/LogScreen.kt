package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.bioscan.fieldterminal.ui.components.ScreenHeader
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles

// Placeholder content. Real content is Phase D (Steps 11-13): the unified
// feed, then the add-entry flow, then photo-based nutrition estimation.
@Composable
fun LogScreen() {
    Column(modifier = Modifier.fillMaxSize().background(FieldColors.Ground)) {
        ScreenHeader(title = "LOG", context = "PLACEHOLDER")
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "LOG — placeholder", style = FieldTextStyles.placeholderBody, color = FieldColors.InkMuted)
        }
    }
}
