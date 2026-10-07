package com.bioscan.fieldterminal.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Field Terminal is a single deliberate dark theme (the Futuristic Material
// contract defines no light variant) -- not following system light/dark.
// Seeded from FuturisticMaterialTokens so any stock Material 3 component that
// isn't explicitly styled still lands on the same palette as the rest of the app.
private val FieldColorScheme = darkColorScheme(
    background = FuturisticMaterialTokens.Base,
    surface = FuturisticMaterialTokens.Surface,
    surfaceVariant = FuturisticMaterialTokens.Elevated,
    onBackground = FuturisticMaterialTokens.TextPrimary,
    onSurface = FuturisticMaterialTokens.TextPrimary,
    onSurfaceVariant = FuturisticMaterialTokens.TextSecondary,
    primary = FuturisticMaterialTokens.Emerald,
    onPrimary = FuturisticMaterialTokens.Base,
    secondary = FuturisticMaterialTokens.EmeraldHighlight,
    onSecondary = FuturisticMaterialTokens.Base,
    error = FuturisticMaterialTokens.Critical,
    onError = FuturisticMaterialTokens.Base,
    outline = FuturisticMaterialTokens.GlassBorder,
)

@Composable
fun FieldTerminalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FieldColorScheme,
        content = content,
    )
}
