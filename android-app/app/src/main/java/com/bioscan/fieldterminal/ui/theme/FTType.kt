package com.bioscan.fieldterminal.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Contract section 5 type scale. Inter = interface language, Roboto Mono =
// telemetry. New and migrated UI uses these instead of hand-written
// TextStyle(...) so sizes and weights cannot drift screen to screen.
object FTType {
    val DisplayMetric = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Bold, fontSize = 36.sp)
    val PageTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 24.sp)
    val SectionTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    val Body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp)
    val BodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp)
    val Telemetry = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    val Label = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    val Micro = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Normal, fontSize = 10.sp)
}
