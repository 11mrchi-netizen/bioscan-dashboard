package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles

// A BACK row above ScreenHeader, same placement SessionDetailScreen.kt
// already established for this app's one prior pushed route -- the 4 new
// tile pages (DAV-70, First feedback fixes) are pushed routes too, so this
// factors that pattern out into one shared header instead of copying
// SessionDetailScreen's inline Row four more times.
@Composable
fun TileHeader(title: String, context: String, onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().background(FieldColors.Panel).padding(horizontal = 22.dp, vertical = 10.dp)) {
        Text(
            "BACK",
            style = FieldTextStyles.subTabLabel,
            color = FieldColors.InkMuted,
            modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBack),
        )
    }
    ScreenHeader(title = title, context = context)
}
