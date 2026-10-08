package com.bioscan.fieldterminal.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.bioscan.fieldterminal.R

// Bundled font files (res/font/) -- see android-app/licenses/fonts/ for their
// OFL license text. Both families ship as variable fonts upstream (no static
// per-weight files), so each named weight below is one FontVariation.Settings
// instance over the same underlying file -- standard variable-font usage, not
// a workaround.

// design/FUTURISTIC_MATERIAL_DESIGN_CONTRACT.md's typography split: Inter for
// interface language, Roboto Mono for telemetry.
private fun inter(weight: FontWeight) = Font(
    resId = R.font.inter_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

private fun robotoMono(weight: FontWeight) = Font(
    resId = R.font.roboto_mono_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val Inter = FontFamily(
    inter(FontWeight.Normal), // 400 -- body
    inter(FontWeight.Medium), // 500
    inter(FontWeight.SemiBold), // 600 -- section title
    inter(FontWeight.Bold), // 700 -- page title
)

val RobotoMono = FontFamily(
    robotoMono(FontWeight.Normal), // 400 -- micro
    robotoMono(FontWeight.Bold), // 700 -- telemetry/label
)
