package com.bioscan.fieldterminal.ui.screens.fuel

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.FuelPlanRepository
import com.bioscan.fieldterminal.data.NutritionOverview
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TodayFuelResult
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import kotlinx.coroutines.CancellationException

// PLAN › FOOD: the week's fuel plan.
@Stable
internal class FuelPlanPageState {
    var result by mutableStateOf<TodayFuelResult?>(null)
    var error by mutableStateOf<String?>(null)

    suspend fun load(context: Context, overview: NutritionOverview?) {
        error = null
        try {
            result = FuelPlanRepository(SupabaseClientProvider.client).loadToday(context, overview)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = describeLoadFailure(e)
        }
    }
}
