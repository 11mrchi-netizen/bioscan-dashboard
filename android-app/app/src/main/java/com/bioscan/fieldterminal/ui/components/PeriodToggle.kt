package com.bioscan.fieldterminal.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bioscan.fieldterminal.domain.TotalsPeriod

// Shared 1D/7D/30D/90D selector for the Training and Nutrition running-
// totals cards -- a thin binding of SegmentedToggle to TotalsPeriod, so there
// is exactly one toggle implementation in the app.
@Composable
fun PeriodToggle(selected: TotalsPeriod, onSelect: (TotalsPeriod) -> Unit, modifier: Modifier = Modifier) {
    SegmentedToggle(
        options = TotalsPeriod.entries,
        selected = selected,
        labelOf = { it.label },
        onSelect = onSelect,
        modifier = modifier,
    )
}
