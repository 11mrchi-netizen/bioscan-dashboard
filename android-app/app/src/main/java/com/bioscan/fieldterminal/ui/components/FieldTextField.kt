package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.Saira

// Squared text input matching design/README.md's "Radius -- 0 everywhere" --
// Material3's TextField/OutlinedTextField both default to rounded corners
// that can't be squared without fighting their built-in shape defaults, so
// built directly on BasicTextField, same approach AmberButton already takes
// for the same reason.
@Composable
fun FieldTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
) {
    val textStyle = TextStyle(fontFamily = Saira, fontSize = 14.sp, color = FieldColors.Ink)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = textStyle,
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        cursorBrush = SolidColor(FieldColors.Amber),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, FieldColors.Hairline)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        decorationBox = { innerTextField ->
            Box {
                if (value.isEmpty()) {
                    Text(placeholder, style = textStyle, color = FieldColors.InkMuted)
                }
                innerTextField()
            }
        },
    )
}
