package com.bioscan.fieldterminal.ui.screens

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.AgingProfileOverview
import com.bioscan.fieldterminal.data.AgingProfileRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.ui.components.LoadGuard

// The Aging page: each biological-age model and its history.
@Stable
internal class AgingProfileState {
    val guard = LoadGuard()

    var overview by mutableStateOf<AgingProfileOverview?>(null)

    suspend fun load() {
        guard.run { overview = AgingProfileRepository(SupabaseClientProvider.client).loadOverview() }
    }
}
