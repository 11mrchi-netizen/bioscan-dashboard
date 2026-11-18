package com.bioscan.fieldterminal.ui.screens.user

import com.bioscan.fieldterminal.data.HealthspanRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TrainingCyclesRepository
import com.bioscan.fieldterminal.data.catching
import com.bioscan.fieldterminal.domain.meview.MeViewDoc
import com.bioscan.fieldterminal.domain.meview.healthspanMeView
import com.bioscan.fieldterminal.ui.screens.training.currentBlockProgress
import com.bioscan.fieldterminal.ui.screens.training.currentBlockTitle
import java.time.LocalDate

// Me › HEALTHSPAN for the web Dashboard, from the same reads as UserProfileScreen (through UserDataCache), for the
// background refresh (MeViewPublishWorker). Lives beside the screens because the levels and the block's words do.
object HealthspanMeSource {
    suspend fun build(today: LocalDate = LocalDate.now()): MeViewDoc {
        val aging = catching { UserDataCache.aging() }.getOrNull()
        val summary = aging?.let { o -> catching { HealthspanRepository(SupabaseClientProvider.client).load(o) }.getOrNull()?.summary }
        val levels = listOf(
            "Running" to catching { runningLevelOf(UserDataCache.running(), UserDataCache.vo2Percentile()) }.getOrNull(),
            "Strength" to catching { strengthLevelOf(UserDataCache.strength()) }.getOrNull(),
            "Conditioning" to catching { conditioningLevelOf(UserDataCache.conditioning()) }.getOrNull(),
        )
        val cycle = catching { TrainingCyclesRepository(SupabaseClientProvider.client).loadCycles() }.getOrNull()?.firstOrNull { it.isActiveOn(today) }
        return healthspanMeView(summary, aging, levels, cycle?.let { currentBlockTitle(it) to currentBlockProgress(it, today) })
    }
}
