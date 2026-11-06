package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Contract A2 (design/FUTURISTIC_MATERIAL_DESIGN_CONTRACT.md, "Buttons"): the shared button, formerly AmberButton, which
// drew every action the same way (SAVE, PANTRY, diagnostics and DELETE alike). Emphasis says what kind of action it is:
//   Primary     - the one main action of a screen or sheet (v1 platinum fill, dark ink)
//   Secondary   - alternatives and navigation-like actions (faint glass, hairline, light ink; the default)
//   Tertiary    - low-emphasis inline actions (label only)
//   Destructive - deleting or overwriting (critical outline); always followed by FTConfirmDestructive, never one tap
// Labels are Inter (`FTType.ButtonLabel`, contract A7). 48dp minimum touch target; press/focus feedback via ftClickable.
// Disabled = muted border and label, not clickable (a blocked action must look blocked, not broken).
enum class FTButtonEmphasis { Primary, Secondary, Tertiary, Destructive }

@Composable
fun FTButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasis: FTButtonEmphasis = FTButtonEmphasis.Secondary,
    onClick: () -> Unit,
) {
    ButtonBox(label, enabled, emphasis, filled = emphasis == FTButtonEmphasis.Primary, modifier, onClick)
}

// The inline confirm step of a destructive action: what will happen, CANCEL, and a filled critical CONFIRM.
@Composable
fun FTConfirmDestructive(
    message: String,
    confirmLabel: String,
    busy: Boolean = false,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(message, style = FTType.BodySmall, color = FT.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ButtonBox("CANCEL", enabled = !busy, FTButtonEmphasis.Secondary, filled = false, Modifier.weight(1f), onCancel)
            ButtonBox(if (busy) "WORKING…" else confirmLabel, enabled = !busy, FTButtonEmphasis.Destructive, filled = true, Modifier.weight(1f), onConfirm)
        }
    }
}

@Composable
private fun ButtonBox(
    label: String,
    enabled: Boolean,
    emphasis: FTButtonEmphasis,
    filled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(FT.RadiusSmall)
    // .ft-btn (secondary), .ft-btn--primary (platinum), .ft-btn--ghost (tertiary); destructive keeps the bad status hue.
    val critical = emphasis == FTButtonEmphasis.Destructive
    val background: Modifier = when {
        !enabled -> Modifier
        critical && filled -> Modifier.background(FT.Bad, shape)
        filled -> Modifier.background(FT.Platinum, shape)
        emphasis == FTButtonEmphasis.Secondary -> Modifier.background(Color(0x0DFFFFFF), shape)
        else -> Modifier
    }
    val border = when {
        !enabled -> FT.TextMuted.copy(alpha = 0.5f)
        filled || emphasis == FTButtonEmphasis.Tertiary -> Color.Transparent
        critical -> FT.Bad
        else -> FT.Line
    }
    val text = when {
        !enabled -> FT.TextMuted
        filled && critical -> FT.Base
        filled -> FT.PlatinumInk
        critical -> FT.Bad
        emphasis == FTButtonEmphasis.Tertiary -> Color(0xFFE4E9F1)
        else -> FT.TextPrimary
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .then(background)
            .border(FT.BorderWidth, border, shape)
            .ftClickable(enabled = enabled, shape = shape, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (emphasis == FTButtonEmphasis.Tertiary) 8.dp else 20.dp, vertical = 12.dp),
    ) {
        Text(text = label, style = if (filled && !critical) FTType.ButtonLabel.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) else FTType.ButtonLabel, color = text)
    }
}
