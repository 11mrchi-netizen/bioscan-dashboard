package com.bioscan.fieldterminal.ui.screens.detail

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.NutritionOverview
import com.bioscan.fieldterminal.data.NutritionRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.softLoad
import com.bioscan.fieldterminal.ui.components.LoadGuard
import java.time.LocalDate

// The Nutrition page's data: intake against targets, and the weights its energy maths uses.
@Stable
internal class NutritionState {
    val guard = LoadGuard()

    var overview by mutableStateOf<NutritionOverview?>(null)
    var weights by mutableStateOf<List<Pair<LocalDate, Double>>>(emptyList())
    /** Energy availability per day (derived_daily, written nightly), kcal per kg of fat-free mass. */
    var energyAvailability by mutableStateOf<Map<LocalDate, Double>>(emptyMap())

    suspend fun load() {
        guard.run { overview = NutritionRepository(SupabaseClientProvider.client).loadOverview() }
        weights = softLoad { AnalysisRepository(SupabaseClientProvider.client).loadBodyMetrics() }
            ?.mapNotNull { r -> r.weightKg?.let { LocalDate.parse(r.date) to it } }.orEmpty()
        energyAvailability = softLoad { AnalysisRepository(SupabaseClientProvider.client).loadDerivedDaily("energy_availability") }.orEmpty()
    }
}
