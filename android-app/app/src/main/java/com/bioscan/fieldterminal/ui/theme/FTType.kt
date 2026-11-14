package com.bioscan.fieldterminal.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// Contract section 5 type scale: a closed set of roles. v1 "new look": Nunito = numbers, Inter = interface language,
// IBM Plex Mono = micro-labels and telemetry. Screens use these instead of hand-written TextStyle(...) so sizes and
// weights cannot drift screen to screen; if a screen needs something not here, add a role here (and to the contract)
// rather than inventing a one-off.
object FTType {
    // ---- Numbers (Nunito) ----
    val DisplayMetric = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp, letterSpacing = (-0.02).em)
    val MetricMedium = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, letterSpacing = (-0.01).em)
    /** `.ft-stat__v`: the value of a stat tile. */
    val StatValue = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp)
    /** Row values and emphasised data. */
    val Value = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    val Telemetry = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
    /** A heading on a hero surface (gauge state word such as PRIMED). */
    val HeroHeading = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.Black, fontSize = 18.sp)

    // ---- Interface language (Inter) ----
    // Titles break into balanced lines (no lone word on the second line); body text uses paragraph-quality breaking.
    val PageTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineBreak = LineBreak.Heading)
    val SectionTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineBreak = LineBreak.Heading)
    val CardTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineBreak = LineBreak.Heading)
    val RowTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineBreak = LineBreak.Heading)
    val Body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineBreak = LineBreak.Paragraph)
    val BodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineBreak = LineBreak.Paragraph)
    val Caption = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineBreak = LineBreak.Paragraph)
    val CaptionStrong = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    // `.ft-btn`: actions and choices read as words (FTButton, add-entry type tiles), not as telemetry.
    val ButtonLabel = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 0.02.em)

    // ---- Micro-labels (IBM Plex Mono) ----
    val Label = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.06.em)
    /** Chips, segments, dock tabs, badges, card eyebrows (`.ft-chip`, `.ft-stat__k`). */
    val LabelCaps = Label.copy(letterSpacing = 0.14.em)
    val MonoCaption = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Normal, fontSize = 11.sp)
    val Micro = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Normal, fontSize = 10.sp)
}
