package com.bioscan.fieldterminal.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// Contract section 5 type scale: a closed set of roles. Inter = interface
// language, Roboto Mono = telemetry. Screens use these instead of
// hand-written TextStyle(...) so sizes and weights cannot drift screen to
// screen; if a screen needs something not here, add a role here (and to the
// contract) rather than inventing a one-off.
object FTType {
    // ---- Metrics (Roboto Mono) ----
    val DisplayMetric = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Bold, fontSize = 36.sp)
    val MetricMedium = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Bold, fontSize = 24.sp)

    // ---- Interface language (Inter) ----
    val PageTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 24.sp)
    val SectionTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
    val CardTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    val RowTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    val Body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp)
    val BodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp)
    val Caption = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp)
    val CaptionStrong = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)

    // ---- Telemetry (Roboto Mono) ----
    val Value = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Normal, fontSize = 14.sp)
    val Telemetry = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    val Label = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    val LabelCaps = Label.copy(letterSpacing = 0.14.em)
    val MonoCaption = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Normal, fontSize = 11.sp)
    val Micro = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Normal, fontSize = 10.sp)
}
