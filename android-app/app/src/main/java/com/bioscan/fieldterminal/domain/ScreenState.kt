package com.bioscan.fieldterminal.domain

// Tier 5: unified loading/error/success container for screen-level data.
// Screens that replace bare `var data by remember { mutableStateOf<T?>(null) }` +
// a separate `var error` with this type get a single state variable whose
// exhaustive `when` already covers every case the UI must render.
sealed interface ScreenState<out T> {
    data object Loading : ScreenState<Nothing>
    data class Error(val message: String) : ScreenState<Nothing>
    data class Success<T>(val data: T) : ScreenState<T>
}
