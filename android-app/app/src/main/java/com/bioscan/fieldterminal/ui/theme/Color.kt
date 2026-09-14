package com.bioscan.fieldterminal.ui.theme

import androidx.compose.ui.graphics.Color

// Design tokens, 1:1 with design/README.md's "Design Tokens" table -- the
// single source of truth for this palette. Don't add colors here that aren't
// in that table; don't dim FieldInkMuted further (it's the AA-checked value).
object FieldColors {
    val Ground = Color(0xFF14161A)
    val Panel = Color(0xFF0E1013) // header, tab bar, figure field, inset panels
    val RaisedSurface = Color(0xFF1A1D22) // cards, tiles, next-up bar
    val Ink = Color(0xFFE9EDF2)
    val InkMuted = Color(0xC7E9EDF2) // rgba(233,237,242,.78) -- AA-checked, don't dim further
    val Hairline = Color(0x24E9EDF2) // rgba(233,237,242,.14)
    val HairlineFaint = Color(0x14E9EDF2) // rgba(233,237,242,.08)
    val Track = Color(0x1FE9EDF2) // rgba(233,237,242,.12)
    val Amber = Color(0xFFFFB02E) // signal amber -- primary accent, active state, fuel
    val Green = Color(0xFF7EF2A8) // nominal / done / supplements
    val Cyan = Color(0xFF6FD8FF) // water, labs
    val Alert = Color(0xFFFF6B4A) // flags, out-of-range, errors
}
