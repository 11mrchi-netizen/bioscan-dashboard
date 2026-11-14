package com.bioscan.fieldterminal.ui.screens.detail

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.MapSettingsStore
import com.bioscan.fieldterminal.data.NutritionOverview
import com.bioscan.fieldterminal.data.NutritionRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TodaySweat
import com.bioscan.fieldterminal.data.softLoad
import com.bioscan.fieldterminal.data.todaySweatRate
import com.bioscan.fieldterminal.domain.WallClock
import com.bioscan.fieldterminal.ui.components.LoadGuard
import java.time.LocalDate

// The Hydration page's data: water and drinks, today's exercise, and the sweat rate that goes with it.
@Stable
internal class HydrationState {
    val guard = LoadGuard()

    var overview by mutableStateOf<NutritionOverview?>(null)
    var exerciseMinutes by mutableDoubleStateOf(0.0)
    var sweat by mutableStateOf<TodaySweat?>(null)

    suspend fun load(context: Context) {
        guard.run { overview = NutritionRepository(SupabaseClientProvider.client).loadOverview() }
        val today = LocalDate.now()
        exerciseMinutes = softLoad { AnalysisRepository(SupabaseClientProvider.client).loadExerciseSessionsForTrainingLoad() }
            ?.filter { WallClock.date(it.startTime) == today }?.sumOf { it.durationMin ?: 0.0 } ?: 0.0
        if (exerciseMinutes > 0) sweat = softLoad { todaySweatRate(SupabaseClientProvider.client, MapSettingsStore.getHome(context)) }
    }
}
