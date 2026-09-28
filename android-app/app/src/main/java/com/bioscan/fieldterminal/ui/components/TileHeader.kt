package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// DAV-218 (24/9 fixes): the title/context text band (ScreenHeader, now
// removed) is gone -- every tile screen's SubTabRow already names where you
// are, so the header's only remaining job is back-navigation. Replaces the
// old "BACK" text row + full ScreenHeader stack with a small icon button.
@Composable
fun TileHeader(onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().background(FT.Surface).padding(horizontal = 10.dp, vertical = 4.dp)) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = FT.TextSecondary)
        }
    }
}
