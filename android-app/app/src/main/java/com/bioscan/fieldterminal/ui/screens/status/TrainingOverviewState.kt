package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TrainingOverview
import com.bioscan.fieldterminal.data.TrainingRepository
import kotlinx.coroutines.CancellationException

// PLAN › SESSIONS: every recorded session with its load.
@Stable
internal class TrainingOverviewState {
    var overview by mutableStateOf<TrainingOverview?>(null)
    var isLoading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)

    suspend fun load() {
        try {
            overview = TrainingRepository(SupabaseClientProvider.client).loadOverview()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Unknown error"
        }
        isLoading = false
    }
}
