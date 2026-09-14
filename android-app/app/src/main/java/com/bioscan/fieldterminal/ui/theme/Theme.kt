package com.bioscan.fieldterminal.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Field Terminal is a single deliberate dark theme (design/README.md has no
// light variant) -- not following system light/dark, matching the mockups'
// own fixed "machined instrument" look.
private val FieldColorScheme = darkColorScheme(
    background = FieldColors.Ground,
    surface = FieldColors.Ground,
    surfaceVariant = FieldColors.RaisedSurface,
    onBackground = FieldColors.Ink,
    onSurface = FieldColors.Ink,
    primary = FieldColors.Amber,
    onPrimary = FieldColors.Ground,
    secondary = FieldColors.Green,
    error = FieldColors.Alert,
    outline = FieldColors.Hairline,
)

@Composable
fun FieldTerminalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FieldColorScheme,
        content = content,
    )
}
