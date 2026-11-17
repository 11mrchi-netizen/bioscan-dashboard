package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.PantryItem
import com.bioscan.fieldterminal.data.PantryRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import kotlinx.coroutines.CancellationException

// The pantry: what is in stock and what moved.
@Stable
internal class PantryState {
    private val repo = PantryRepository(SupabaseClientProvider.client)

    var items by mutableStateOf<List<PantryItem>?>(null)
    var error by mutableStateOf<String?>(null)

    suspend fun load() {
        try {
            items = repo.load()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Unknown error"
        }
    }
}
