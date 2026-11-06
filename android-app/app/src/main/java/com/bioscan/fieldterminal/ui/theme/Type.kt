package com.bioscan.fieldterminal.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.bioscan.fieldterminal.R

// Bundled font files (res/font/) -- see android-app/licenses/fonts/ for their OFL license text.
// design/FUTURISTIC_MATERIAL_DESIGN_CONTRACT.md (v1 "new look"): Nunito for numbers and headline metrics, Inter for
// interface language, IBM Plex Mono for micro-labels and telemetry. Inter and Nunito ship as variable fonts (one
// FontVariation.Settings instance per named weight); IBM Plex Mono ships as static files per weight.

private fun variable(resId: Int, weight: FontWeight) = Font(
    resId = resId,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val Inter = FontFamily(
    variable(R.font.inter_variable, FontWeight.Normal), // 400 -- body
    variable(R.font.inter_variable, FontWeight.Medium), // 500
    variable(R.font.inter_variable, FontWeight.SemiBold), // 600 -- section title, buttons
    variable(R.font.inter_variable, FontWeight.Bold), // 700 -- page title
)

/** Numbers: the hero value, gauge centres, stat tiles. */
val Nunito = FontFamily(
    variable(R.font.nunito_variable, FontWeight.Bold), // 700
    variable(R.font.nunito_variable, FontWeight.ExtraBold), // 800 -- metric values
    variable(R.font.nunito_variable, FontWeight.Black), // 900 -- headings on hero surfaces
)

/** Micro-labels and telemetry: units, timestamps, chips, eyebrows. */
val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_mono_bold, FontWeight.Bold),
)
