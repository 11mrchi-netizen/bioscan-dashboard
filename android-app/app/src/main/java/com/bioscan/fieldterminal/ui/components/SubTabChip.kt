package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

// Extracted from StatusScreen.kt's own sub-tab rail; since the polish pass a thin name for the shared chip strip
// (FTChips.kt): it scrolls on a narrow phone, fades the edge with more tabs behind it, and keeps the selected tab in
// view, so the ME page's six tabs are all reachable at 360 dp.
@Composable
fun <T> SubTabRow(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) =
    FTChipRow(items, selected, label, onSelect, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp), spacing = 6.dp)

// `.ft-chip`: a mono pill; the selected one is platinum with dark ink.
@Composable
fun SubTabChip(label: String, selected: Boolean, onClick: () -> Unit) = FTChip(label, selected, onClick)
