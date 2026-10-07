package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Small "?" that opens a plain-language explanation. First help/popup pattern
// in the app (25/9 rework) -- kept to one dialog, no bottom-sheet machinery.
@Composable
fun InfoHelpButton(title: String, body: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(FT.GlassFill)
            .border(FT.BorderWidth, FT.GlassBorder, CircleShape)
            .clickable { open = true },
        contentAlignment = Alignment.Center,
    ) {
        Text("?", style = FTType.Label, color = FT.TextSecondary)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            containerColor = FT.Surface,
            title = { Text(title, style = FTType.SectionTitle, color = FT.TextPrimary) },
            text = { Text(body, style = FTType.BodySmall.copy(lineHeight = 20.sp), color = FT.TextSecondary) },
            confirmButton = { TextButton(onClick = { open = false }) { Text("GOT IT", color = FT.DomainTraining) } },
        )
    }
}
