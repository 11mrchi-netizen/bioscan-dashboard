package com.bioscan.fieldterminal.ui.screens.me

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.MeViewPublisher
import com.bioscan.fieldterminal.data.MeViewSources
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.catching
import com.bioscan.fieldterminal.data.routine.RoutinePageData
import com.bioscan.fieldterminal.data.routine.RoutinePageRepository
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import kotlinx.coroutines.CancellationException

// ME › ROUTINE: the last 28 days of time, places, phone use and practices.
@Stable
internal class RoutinePageState {
    var data by mutableStateOf<RoutinePageData?>(null)
    var error by mutableStateOf<String?>(null)

    suspend fun load() {
        try {
            val loaded = RoutinePageRepository(SupabaseClientProvider.client).load()
            data = loaded
            error = null
            // The web Dashboard's Me › Routine shows this same page; a failed write never touches it.
            catching { MeViewPublisher.publish(SupabaseClientProvider.client, MeViewSources.routine(loaded)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = describeLoadFailure(e)
        }
    }
}
