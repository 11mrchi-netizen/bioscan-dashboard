package com.bioscan.fieldterminal.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.bioscan.fieldterminal.R

// Bundled font files (res/font/) -- see android-app/licenses/fonts/ for their
// OFL license text. JetBrains Mono and Saira ship only as variable fonts
// upstream (no static per-weight files), so each named weight below is one
// FontVariation.Settings instance over the same underlying file -- this is
// standard variable-font usage, not a workaround. Saira Condensed ships a
// static Bold file upstream (matching design/README.md's "weight 700 only"
// token), so no variation settings are needed there.

private fun jetBrainsMono(weight: FontWeight) = Font(
    resId = R.font.jetbrains_mono_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

private fun saira(weight: FontWeight) = Font(
    resId = R.font.saira_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

// design/README.md: "JetBrains Mono -- ... Weights 500/600/700."
val JetBrainsMono = FontFamily(
    jetBrainsMono(FontWeight.Medium), // 500
    jetBrainsMono(FontWeight.SemiBold), // 600
    jetBrainsMono(FontWeight.Bold), // 700
)

// design/README.md: "Saira -- ... Weights 400/500/600."
val Saira = FontFamily(
    saira(FontWeight.Normal), // 400
    saira(FontWeight.Medium), // 500
    saira(FontWeight.SemiBold), // 600
)

// design/README.md: "Saira Condensed -- large numerics only. Weight 700."
val SairaCondensed = FontFamily(
    Font(resId = R.font.saira_condensed_bold, weight = FontWeight.Bold),
)

// design/FUTURISTIC_MATERIAL_DESIGN_CONTRACT.md's typography split: Inter for
// interface language, Roboto Mono for telemetry. Both ship as variable fonts
// upstream, same bundled-file + FontVariation.Settings pattern as the three
// families above -- not a new approach, just two more font files.
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
