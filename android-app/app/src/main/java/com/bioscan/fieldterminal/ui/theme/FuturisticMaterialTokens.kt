package com.bioscan.fieldterminal.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Tokenized Futuristic Material foundation for new and migrated analytical UI.
 * The only palette in the app: the legacy amber FieldColors/FieldTextStyles are gone.
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

    // Domain accents -- identify a section or nav destination, never a
    // health/warning/error state (state colors above are a separate,
    // non-interchangeable set even where a hex value happens to coincide,
    // e.g. DomainLog == Warning's hex but means something different).
    // Contract section 4's exact table; DomainTraining reuses Emerald
    // itself, since Training's own domain accent IS the primary signal.
    val DomainTraining = Emerald
    val DomainFuel = Color(0xFF22D3EE)
    val DomainHeart = Color(0xFFF43F5E)
    val DomainLabs = Color(0xFFA78BFA)
    val DomainMap = Color(0xFF60A5FA)
    val DomainLog = Color(0xFFF59E0B)
    // DAV-296: the User/Profile tab that replaces Map in the bottom bar.
    // Indigo -- distinct from every accent above (Map's own Blue included,
    // since MapScreen.kt stays in the codebase and keeps its accent).
    val DomainUser = Color(0xFF818CF8)

    // Category tier -- "what kind of thing is this" (log entry kind, chart
    // series, sub-domain). A third tier next to State ("how is it") and
    // Domain ("which section"); never interchangeable with either. Identity
    // marks only (dot, rail, icon, chip outline, series stroke): never on
    // state pills, gauge bands or range tracks. See contract section 4.
    class CategoryColor(val c300: Color, val c500: Color, val c700: Color, val c900: Color)

    object Category {
        val Sleep = CategoryColor(Color(0xFF8FA0F0), Color(0xFF4A5FD9), Color(0xFF3141A8), Color(0xFF1B2463))
        val Activity = CategoryColor(Color(0xFFFFB38A), Color(0xFFFF8040), Color(0xFFC75A22), Color(0xFF6B2E10))
        val Wellbeing = CategoryColor(Color(0xFF8DCBF3), Color(0xFF3FA9E8), Color(0xFF2578AB), Color(0xFF123E5C))
        val Arousal = CategoryColor(Color(0xFFF28BB8), Color(0xFFE11D74), Color(0xFFA0124F), Color(0xFF520A28))
        val Digestion = CategoryColor(Color(0xFFE3D0AE), Color(0xFFC9A876), Color(0xFF8F7549), Color(0xFF4A3B22))
        val Intake = CategoryColor(Color(0xFFB9F8CF), Color(0xFF7EF2A8), Color(0xFF3FB872), Color(0xFF1E5C3A))
    }

    // 4 px base scale (contract section 6).
    object Space {
        val XS = 4.dp
        val SM = 8.dp
        val MD = 12.dp
        val LG = 16.dp
        val XL = 20.dp
        val XXL = 24.dp
        val Huge = 32.dp
    }

    val GlassFill = Color(0x14FFFFFF)
    val GlassStrongFill = Color(0x1FFFFFFF)
    val GlassBorder = Color(0x1FFFFFFF)
    val GlassTrack = Color(0x1AE2E8F0)

    val RadiusSmall = 12.dp
    val RadiusModule = 16.dp
    val RadiusCard = 24.dp
    val RadiusCardLarge = 28.dp
    val BorderWidth = 1.dp

    // Modal bottom sheets: soft top corners at the primary-card radius.
    val SheetShape = RoundedCornerShape(topStart = RadiusCard, topEnd = RadiusCard)
}
