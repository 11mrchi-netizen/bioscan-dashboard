package com.bioscan.fieldterminal.ui.screens.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.AgentRepository
import com.bioscan.fieldterminal.data.AgentShareGroup
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import kotlinx.coroutines.CancellationException

// Setup › Assistant: what the assistant may read.
@Stable
internal class AssistantState {
    private val repo = AgentRepository(SupabaseClientProvider.client)

    var enabled by mutableStateOf<Boolean?>(null)
    var groups by mutableStateOf(emptySet<AgentShareGroup>())
    var error by mutableStateOf<String?>(null)

    suspend fun load() {
        try {
            val s = repo.loadSettings()
            enabled = s.enabled
            groups = AgentShareGroup.entries.filter { it.key in s.optIn }.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = describeLoadFailure(e)
        }
    }
}
