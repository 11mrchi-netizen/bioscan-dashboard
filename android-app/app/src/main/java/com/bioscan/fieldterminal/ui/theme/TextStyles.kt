package com.bioscan.fieldterminal.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// Named presets for the specific mono/saira combinations global chrome and
// Step 3's screens actually use -- not a full type-scale for content that
// doesn't exist yet (that gets added per-screen as Phase C/D/E build real
// content). Values are 1:1 with design/README.md's "Global chrome" and
// "Typography" sections; colors are applied at the call site (active/
// inactive states vary per component) rather than baked in here.
object FieldTextStyles {
    // Screen header: title (e.g. "STATUS") + context line underneath.
    val headerTitle = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 0.26.em,
    )
    val headerContext = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.04.em,
    )

    // Sync pill label ("SYNCED" / "SYNCING" / "OFFLINE").
    val syncLabel = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        letterSpacing = 0.12.em,
    )

    // Status sub-tab rail chips.
    val subTabLabel = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        letterSpacing = 0.14.em,
    )

    // Bottom tab bar labels.
    val tabBarLabel = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 9.5.sp,
        letterSpacing = 0.14.em,
    )

    // Generic placeholder body text for not-yet-built screen content.
    val placeholderBody = TextStyle(
        fontFamily = Saira,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    )
}
