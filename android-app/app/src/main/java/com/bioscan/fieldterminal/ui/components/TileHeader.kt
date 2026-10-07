package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// The one back header for every pushed screen. Tile pages (whose SubTabRow
// already says where you are) pass no title and get just the back arrow;
// detail pages (Session Detail, Aging Profile) pass a title and optional
// context line. Replaces the hand-rolled "BACK" text rows.
@Composable
fun TileHeader(onBack: () -> Unit, title: String? = null, subtitle: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().background(FT.Surface).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = FT.TextSecondary)
        }
        if (title != null) {
            Column(modifier = Modifier.padding(start = 4.dp, end = 12.dp)) {
                Text(title, style = FTType.SectionTitle, color = FT.TextPrimary)
                subtitle?.let { Text(it, style = FTType.MonoCaption, color = FT.TextSecondary) }
            }
        }
    }
}
