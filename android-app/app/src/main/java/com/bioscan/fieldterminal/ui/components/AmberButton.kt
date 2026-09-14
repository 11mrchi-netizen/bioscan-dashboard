package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles

// Squared amber action button -- Material3's Button defaults to a rounded
// shape, which doesn't match design/README.md's "Radius -- 0 everywhere"
// rule, so built directly rather than restyled from it. Not itself from a
// specific mockup (none of the 13 committed screens are a button in
// isolation), but uses the same tokens/type as everything that is.
@Composable
fun AmberButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .border(1.dp, FieldColors.Amber)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(text = label, style = FieldTextStyles.subTabLabel, color = FieldColors.Amber)
    }
}
