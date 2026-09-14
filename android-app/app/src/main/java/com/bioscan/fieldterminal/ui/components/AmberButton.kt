package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles

// Squared amber action button -- Material3's Button defaults to a rounded
// shape, which doesn't match design/README.md's "Radius -- 0 everywhere"
// rule, so built directly rather than restyled from it. Not itself from a
// specific mockup (none of the 13 committed screens are a button in
// isolation), but uses the same tokens/type as everything that is.
//
// `indication = null` (needed to suppress Material's bouncy ripple, which
// design/README.md's "no spring, no scale bounce" motion rule rules out)
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

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .border(1.dp, FieldColors.Amber)
            .background(FieldColors.Amber.copy(alpha = if (isPressed) 0.18f else 0f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(text = label, style = FieldTextStyles.subTabLabel, color = FieldColors.Amber)
    }
}
