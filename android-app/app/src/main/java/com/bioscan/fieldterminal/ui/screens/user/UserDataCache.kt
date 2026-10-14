package com.bioscan.fieldterminal.ui.screens.user

import com.bioscan.fieldterminal.data.AgingProfileOverview
import com.bioscan.fieldterminal.data.AgingProfileRepository
import com.bioscan.fieldterminal.data.ConditioningHistory
import com.bioscan.fieldterminal.data.HealthspanInputsRepository
import com.bioscan.fieldterminal.data.RunningHistory
import com.bioscan.fieldterminal.data.SkillHistoryRepository
import com.bioscan.fieldterminal.data.StrengthHistory
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.healthspan.HealthspanAxis
import com.bioscan.fieldterminal.domain.healthspan.HealthspanAxisInput
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Short-lived in-memory cache for the User tab and its skill pages. The app has no ViewModels (screens load in
// LaunchedEffect), so leaving the User tab for a skill page and coming back would otherwise re-run every query.
// Entries live for a few minutes; `force` always refetches. Nothing is persisted.
object UserDataCache {
    private const val TTL_MS = 5 * 60_000L

    private class Entry<T>(val at: Long, val value: T) {
        fun fresh() = System.currentTimeMillis() - at < TTL_MS
    }

    private val mutex = Mutex()
    private val client get() = SupabaseClientProvider.client
    private val skills by lazy { SkillHistoryRepository(client) }

    private var aging: Entry<AgingProfileOverview>? = null
    private var running: Entry<RunningHistory>? = null
    private var strength: Entry<StrengthHistory>? = null
    private var conditioning: Entry<ConditioningHistory>? = null
    private var healthspan: Entry<Map<HealthspanAxis, HealthspanAxisInput>>? = null

    private suspend fun <T> cached(read: () -> Entry<T>?, write: (Entry<T>) -> Unit, force: Boolean, load: suspend () -> T): T =
        mutex.withLock {
            val hit = read()
            if (!force && hit != null && hit.fresh()) return hit.value
            load().also { write(Entry(System.currentTimeMillis(), it)) }
        }

    suspend fun aging(force: Boolean = false): AgingProfileOverview =
        cached({ aging }, { aging = it }, force) { AgingProfileRepository(client).loadOverview() }

    suspend fun running(force: Boolean = false): RunningHistory =
        cached({ running }, { running = it }, force) { skills.loadRunning() }

    suspend fun strength(force: Boolean = false): StrengthHistory =
        cached({ strength }, { strength = it }, force) { skills.loadStrength() }

    suspend fun conditioning(force: Boolean = false): ConditioningHistory =
        cached({ conditioning }, { conditioning = it }, force) { skills.loadConditioning() }

    // Replays a fresh cached set of axes immediately; otherwise loads them, reporting each axis as it resolves.
    suspend fun loadHealthspan(calorieGoalKcal: Double?, onAxis: (HealthspanAxisInput) -> Unit) {
        val hit = healthspan
        if (hit != null && hit.fresh()) {
            hit.value.values.forEach(onAxis)
            return
        }
        val collected = mutableMapOf<HealthspanAxis, HealthspanAxisInput>()
        HealthspanInputsRepository(client).load(
            aging = { runCatching { aging() }.getOrNull() },
            calorieGoalKcal = calorieGoalKcal,
        ) { input ->
            collected[input.axis] = input
            onAxis(input)
        }
        healthspan = Entry(System.currentTimeMillis(), collected.toMap())
    }
}
