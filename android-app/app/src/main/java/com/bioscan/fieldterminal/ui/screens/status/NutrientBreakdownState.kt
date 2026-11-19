package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.NutrientBreakdownData
import com.bioscan.fieldterminal.data.NutrientBreakdownRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.ui.components.LoadGuard

// Every nutrient against its reference intake, over the range picked.
@Stable
internal class NutrientBreakdownState {
    val guard = LoadGuard()

    var data by mutableStateOf<NutrientBreakdownData?>(null)
    var isLoading by mutableStateOf(true)

    suspend fun load(days: Int) {
        isLoading = true
        guard.run { data = NutrientBreakdownRepository(SupabaseClientProvider.client).loadNutrients(days) }
        isLoading = false
    }
}
