package com.bioscan.fieldterminal.ui.screens.detail

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.BenchmarkArtifactRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.domain.DayRange
import com.bioscan.fieldterminal.domain.comparison.BenchmarkArtifact
import com.bioscan.fieldterminal.ui.components.LoadGuard
import com.bioscan.fieldterminal.ui.components.parallel
import java.time.LocalDate

// The Cardio page's data: the daily heart and breathing readings, sleep beside them, and the day's ranges.
@Stable
internal class CardioState {
    val guard = LoadGuard()

    var wearable by mutableStateOf<List<WearableAnalysisRow>?>(null)
    var sleep by mutableStateOf<List<SleepAnalysisRow>?>(null)
    var ranges by mutableStateOf<List<Pair<LocalDate, DayRange>>>(emptyList())
    var steps by mutableStateOf<List<BenchmarkArtifact>>(emptyList())

    suspend fun load() {
        guard.run {
            val repo = AnalysisRepository(SupabaseClientProvider.client)
            parallel(
                { wearable = repo.loadWearableDaily() },
                { sleep = repo.loadSleepDaily() },
                { ranges = repo.loadHeartRateRanges() },
                { steps = BenchmarkArtifactRepository(SupabaseClientProvider.client).loadArtifacts("steps") },
            )
        }
    }
}
