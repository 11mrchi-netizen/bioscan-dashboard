package com.bioscan.fieldterminal.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Tokenized Futuristic Material foundation for new and migrated analytical UI.
 * Existing legacy FieldColors stay intact until their owning screens migrate.
 */
object FuturisticMaterialTokens {
    val Base = Color(0xFF0A0C0E)
    val Surface = Color(0xFF11161A)
    val Elevated = Color(0xFF171E23)
    val TextPrimary = Color(0xFFE2E8F0)
    val TextSecondary = Color(0xFFA4AFBA)
    val TextMuted = Color(0xFF66717C)

    val Emerald = Color(0xFF10B981)
    val EmeraldHighlight = Color(0xFF6EE7B7)
    val EmeraldAtmosphere = Color(0xFF064E3B)

    val Info = Color(0xFF60A5FA)
    val Warning = Color(0xFFF59E0B)
    val Critical = Color(0xFFF87171)
    val Analysis = Color(0xFFA78BFA)

    val GlassFill = Color(0x14FFFFFF)
    val GlassStrongFill = Color(0x1FFFFFFF)
    val GlassBorder = Color(0x1FFFFFFF)
    val GlassTrack = Color(0x1AE2E8F0)

    val RadiusSmall = 12.dp
    val RadiusModule = 16.dp
    val RadiusCard = 24.dp
    val RadiusCardLarge = 28.dp
    val BorderWidth = 1.dp
}
