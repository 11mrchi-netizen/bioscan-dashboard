package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Pick-from-a-list controls for forms with long option lists, where a row of chips would
// scroll off screen. An option is (key, label).

@Composable
private fun DropdownBox(text: String, placeholder: String, modifier: Modifier, menu: @Composable (dismiss: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(FT.RadiusSmall)
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                .border(FT.BorderWidth, FT.GlassBorder, shape).background(FT.GlassFill, shape)
                .clickable { open = true }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text.ifEmpty { placeholder }, style = FTType.Body, color = if (text.isEmpty()) FT.TextMuted else FT.TextPrimary, modifier = Modifier.weight(1f))
            Text("v", style = FTType.Label, color = FT.TextSecondary, modifier = Modifier.padding(start = 8.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = FT.Elevated) { menu { open = false } }
    }
}

@Composable
fun FTDropdown(options: List<Pair<String, String>>, selected: String?, placeholder: String, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    DropdownBox(options.firstOrNull { it.first == selected }?.second.orEmpty(), placeholder, modifier) { dismiss ->
        options.forEach { (key, label) ->
            DropdownMenuItem(
                text = { Text(label, style = FTType.Body, color = if (key == selected) FT.DomainTraining else FT.TextPrimary) },
                onClick = { onSelect(key); dismiss() },
            )
        }
    }
}

// Several choices from one list. The order of the selection is kept: callers use it as a rotation.
@Composable
fun FTMultiDropdown(options: List<Pair<String, String>>, selected: List<String>, placeholder: String, modifier: Modifier = Modifier, onChange: (List<String>) -> Unit) {
    val names = selected.mapNotNull { k -> options.firstOrNull { it.first == k }?.second }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var open by remember { mutableStateOf(false) }
        val shape = RoundedCornerShape(FT.RadiusSmall)
        Box {
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                    .border(FT.BorderWidth, FT.GlassBorder, shape).background(FT.GlassFill, shape)
                    .clickable { open = true }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(if (names.isEmpty()) placeholder else "${names.size} selected", style = FTType.Body, color = if (names.isEmpty()) FT.TextMuted else FT.TextPrimary, modifier = Modifier.weight(1f))
                Text("v", style = FTType.Label, color = FT.TextSecondary, modifier = Modifier.padding(start = 8.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = FT.Elevated) {
                options.forEach { (key, label) ->
                    val on = key in selected
                    DropdownMenuItem(
                        text = { Text((if (on) "[x] " else "[ ] ") + label, style = FTType.Body, color = if (on) FT.DomainTraining else FT.TextPrimary) },
                        onClick = { onChange(if (on) selected - key else selected + key) },
                    )
                }
            }
        }
        if (names.isNotEmpty()) Text(names.mapIndexed { i, n -> "${i + 1}. $n" }.joinToString("   "), style = FTType.Caption, color = FT.TextSecondary)
    }
}
