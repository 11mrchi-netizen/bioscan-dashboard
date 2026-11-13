package com.bioscan.fieldterminal.ui.screens.fuel

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.FuelPlanRepository
import com.bioscan.fieldterminal.data.MealPrepRepository
import com.bioscan.fieldterminal.data.MealPrepWeek
import com.bioscan.fieldterminal.data.NutritionOverview
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.WeekPlan
import com.bioscan.fieldterminal.data.catching
import com.bioscan.fieldterminal.domain.fuel.BreakfastItem
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

// PLAN › FOOD › meal prep: the week's plan, what is prepped, and the breakfast list.
@Stable
internal class MealPrepPageState {
    private val repo = MealPrepRepository(SupabaseClientProvider.client)

    var plan by mutableStateOf<WeekPlan?>(null)
    var week by mutableStateOf<MealPrepWeek?>(null)
    var breakfast by mutableStateOf<List<BreakfastItem>>(emptyList())
    var error by mutableStateOf<String?>(null)
    var loaded by mutableStateOf(false)

    suspend fun load(context: Context, overview: NutritionOverview?, weekStart: LocalDate) {
        error = null
        if (plan == null) loaded = false
        try {
            plan = FuelPlanRepository(SupabaseClientProvider.client).loadWeekPlan(context, overview, weekStart)
            week = catching { repo.loadWeek(weekStart) }.getOrNull()
            breakfast = catching { repo.loadBreakfast() }.getOrDefault(emptyList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = describeLoadFailure(e)
        }
        loaded = true
    }
}
