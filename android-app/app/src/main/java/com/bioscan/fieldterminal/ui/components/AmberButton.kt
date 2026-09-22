package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.RobotoMono

// Shared primary-action button, used across every screen's add/edit/connect
// flows -- migrated to the Futuristic Material contract in one place (DAV-108
// follow-up) rather than per call site: emerald as the primary signal
// (contract section 15 DO: "Use emerald as the primary system signal"),
// 12dp corners per the radius scale's "buttons, chips, small modules" band
// instead of the old square corners.
//
// `indication = null` (needed to suppress Material's bouncy ripple, which
// this app's own motion rule rules out -- "no spring, no scale bounce")
// originally meant NO feedback at all -- the tap was actually registering
// the whole time (confirmed via logcat reaching Credential Manager), it just
// looked and felt broken with zero visual response. Fixed with an instant
// (no animation) background tint on press instead -- mechanical, not bouncy,
// but still visible. defaultMinSize also brings this up to Android's 48dp
// minimum touch target, which the original padding-only sizing fell short of.
@Composable
fun AmberButton(label: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val shape = RoundedCornerShape(FT.RadiusSmall)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .border(FT.BorderWidth, FT.Emerald, shape)
            .background(FT.Emerald.copy(alpha = if (isPressed) 0.18f else 0f), shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(text = label, style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, letterSpacing = 0.14f.em), color = FT.Emerald)
    }
}
