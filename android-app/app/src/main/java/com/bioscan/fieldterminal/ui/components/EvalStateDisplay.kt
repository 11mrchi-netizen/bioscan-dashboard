package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.bioscan.fieldterminal.domain.EvalState
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.JetBrainsMono
import com.bioscan.fieldterminal.ui.theme.Saira
import androidx.compose.ui.unit.sp

// Extracted from AnalysisScreen.kt (Phase A2) once the tile-page restructure
// (DAV-70, First feedback fixes) needed the exact same six-state vocabulary
// display on multiple tiles (Heart's HRV/RHR/Sleep/etc, Fuel's Weight/
// Nutrition) -- this is real shared domain semantics, not just visual
// similarity, unlike the smaller per-file StatLine copies this codebase
// otherwise keeps duplicated on purpose.
@Composable
fun StateRow(state: EvalState) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("State", style = TextStyle(fontFamily = Saira, fontSize = 14.5.sp), color = FieldColors.InkMuted)
        Text(stateLabel(state), style = TextStyle(fontFamily = JetBrainsMono, fontSize = 14.5.sp), color = stateColor(state))
    }
}

// Neutral labels per the Evaluation Method Spec's own explicit rule --
// "above your band," not "improved": a SHIFT_UP is not automatically good
// (chronically elevated HRV can mean parasympathetic saturation, not great
// recovery), so no color or word here implies a value judgement except
// UNSTABLE, which really is always worth a second look regardless of
// direction.
fun stateLabel(state: EvalState): String = when (state) {
    EvalState.NoData -> "NO DATA"
    EvalState.Building -> "BUILDING"
    EvalState.Stable -> "STABLE"
    EvalState.ShiftUp -> "ABOVE YOUR BAND"
    EvalState.ShiftDown -> "BELOW YOUR BAND"
    EvalState.Unstable -> "UNSTABLE"
}

fun stateColor(state: EvalState): Color = when (state) {
    EvalState.NoData, EvalState.Building -> FieldColors.InkMuted
    EvalState.Stable -> FieldColors.Green
    EvalState.ShiftUp, EvalState.ShiftDown -> FieldColors.Amber
    EvalState.Unstable -> FieldColors.Alert
}
