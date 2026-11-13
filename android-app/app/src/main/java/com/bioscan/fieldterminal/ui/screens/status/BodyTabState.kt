package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.BodyCheckinRepository
import com.bioscan.fieldterminal.data.ScaleCompositionPoint
import com.bioscan.fieldterminal.data.ScaleReadingRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.UserProfileRepository
import com.bioscan.fieldterminal.data.catching
import com.bioscan.fieldterminal.data.model.BodyMetricsAnalysisRow
import com.bioscan.fieldterminal.data.model.UserProfileRow
import com.bioscan.fieldterminal.domain.BodyCheckinRecord
import com.bioscan.fieldterminal.ui.components.LoadGuard

// ME › BODY: weight and composition, the body check-ins and the scale readings.
@Stable
internal class BodyTabState {
    val guard = LoadGuard()

    var bodyMetrics by mutableStateOf<List<BodyMetricsAnalysisRow>?>(null)
    var checkins by mutableStateOf<List<BodyCheckinRecord>>(emptyList())
    var heightCm by mutableStateOf<Double?>(null)
    var profile by mutableStateOf<UserProfileRow?>(null)
    var scaleSeries by mutableStateOf<List<ScaleCompositionPoint>>(emptyList())

    suspend fun load() {
        guard.run { bodyMetrics = AnalysisRepository(SupabaseClientProvider.client).loadBodyMetrics() }
        val repo = BodyCheckinRepository(SupabaseClientProvider.client)
        checkins = catching { repo.loadHistory() }.getOrElse { checkins }
        heightCm = catching { repo.latestHeightCm() }.getOrNull() ?: heightCm
        profile = catching { UserProfileRepository(SupabaseClientProvider.client).loadProfile() }.getOrNull() ?: profile
        scaleSeries = catching { ScaleReadingRepository(SupabaseClientProvider.client).loadCompositionSeries() }.getOrElse { scaleSeries }
    }
}
