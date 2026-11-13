package com.bioscan.fieldterminal.ui.screens.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.CaptureCoverageRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.StreamCoverage
import kotlinx.coroutines.CancellationException

// Setup › Capture: days logged per stream over the last 28 days.
@Stable
internal class CaptureCoverageState {
    private val repo = CaptureCoverageRepository(SupabaseClientProvider.client)

    var rows by mutableStateOf<List<StreamCoverage>?>(null)
    var error by mutableStateOf<String?>(null)

    suspend fun load() {
        try {
            rows = repo.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Could not load coverage"
        }
    }
}
