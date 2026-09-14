package com.bioscan.fieldterminal.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Placeholder color scheme -- Step 4 (Phase B) replaces this with the real
// Field Terminal design system extracted from /design/README.md once mockups
// gate that work. This exists only so Step 1's launch screen has *a* theme.
private val PlaceholderScheme = darkColorScheme(
    background = PlaceholderBackground,
    surface = PlaceholderBackground,
    onBackground = PlaceholderInk,
    onSurface = PlaceholderInk,
)

@Composable
fun FieldTerminalTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = PlaceholderScheme,
        content = content,
    )
}
