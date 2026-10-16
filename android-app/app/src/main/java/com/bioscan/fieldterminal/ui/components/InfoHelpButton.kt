package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Small "?" that opens a plain-language explanation. First help/popup pattern
// in the app (25/9 rework) -- kept to one dialog, no bottom-sheet machinery.
//
// The dialog opens on the short explanation (`body`); the method, limits and references (`details`, usually from the
// metric registry) sit behind a toggle so the first view stays readable. The text scrolls inside the dialog, capped at
// a height that fits a small phone, with a "scroll for more" hint while there is more below: a long text used to be cut off with
// no way to reach the end. 420 dp of text plus the title and the button fits a small phone's screen.
private val MAX_TEXT_HEIGHT = 420.dp

@Composable
fun InfoHelpButton(title: String, body: String, modifier: Modifier = Modifier, details: String? = null) {
    var open by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(FT.GlassFill)
            .border(FT.BorderWidth, FT.GlassBorder, CircleShape)
            .ftClickable(shape = CircleShape, role = Role.Button, onClickLabel = "Explain") { open = true },
        contentAlignment = Alignment.Center,
    ) {
        Text("?", style = FTType.Label, color = FT.TextSecondary)
    }
    if (open) {
        HelpDialog(title, body, details) { open = false }
    }
}

@Composable
private fun HelpDialog(title: String, body: String, details: String?, onDismiss: () -> Unit) {
    var showDetails by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val textStyle = FTType.BodySmall.copy(lineHeight = 20.sp)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FT.Surface,
        title = { Text(title, style = FTType.SectionTitle, color = FT.TextPrimary) },
        text = {
            Column {
                Column(
                    modifier = Modifier.heightIn(max = MAX_TEXT_HEIGHT).verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(body, style = textStyle, color = FT.TextSecondary)
                    if (!details.isNullOrBlank()) {
                        Text(
                            if (showDetails) "HIDE METHOD, LIMITS AND REFERENCES ▴" else "METHOD, LIMITS AND REFERENCES ▾",
                            style = FTType.LabelCaps,
                            color = FT.DomainTraining,
                            modifier = Modifier
                                .ftClickable(role = Role.Button) { showDetails = !showDetails }
                                .padding(vertical = 6.dp),
                        )
                        if (showDetails) Text(details, style = textStyle, color = FT.TextSecondary)
                    }
                }
                if (scroll.canScrollForward) {
                    Text("SCROLL FOR MORE ▾", style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("GOT IT", color = FT.DomainTraining) } },
    )
}
