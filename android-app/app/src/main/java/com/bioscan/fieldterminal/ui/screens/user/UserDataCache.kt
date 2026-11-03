package com.bioscan.fieldterminal.ui.screens.user

import com.bioscan.fieldterminal.data.AgingProfileOverview
import com.bioscan.fieldterminal.data.AgingProfileRepository
import com.bioscan.fieldterminal.data.ConditioningHistory
import com.bioscan.fieldterminal.data.RunningHistory
import com.bioscan.fieldterminal.data.SkillHistoryRepository
import com.bioscan.fieldterminal.data.StrengthHistory
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.vo2Placement
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
    private var vo2: Entry<Double?>? = null

    private suspend fun <T> cached(read: () -> Entry<T>?, write: (Entry<T>) -> Unit, force: Boolean, load: suspend () -> T): T =
        mutex.withLock {
            val hit = read()
            if (!force && hit != null && hit.fresh()) return hit.value
            load().also { write(Entry(System.currentTimeMillis(), it)) }
        }

    suspend fun aging(force: Boolean = false): AgingProfileOverview =
        cached({ aging }, { aging = it }, force) { AgingProfileRepository(client).loadOverview() }

    // The sport histories go through the read cache, kept on disk, so the sport pages open at once from the last data.
    suspend fun running(force: Boolean = false): RunningHistory =
        if (force) skills.loadRunning() else com.bioscan.fieldterminal.data.ReadCache.get(
            "skills.running", tables = setOf("exercise_sessions", "wearable_daily", "zepp_workout_detail"),
            serializer = RunningHistory.serializer(),
        ) { skills.loadRunning() }

    suspend fun strength(force: Boolean = false): StrengthHistory =
        if (force) skills.loadStrength() else com.bioscan.fieldterminal.data.ReadCache.get(
            "skills.strength", tables = setOf("exercise_sessions", "body_metrics"),
            serializer = StrengthHistory.serializer(),
        ) { skills.loadStrength() }

    suspend fun conditioning(force: Boolean = false): ConditioningHistory =
        if (force) skills.loadConditioning() else com.bioscan.fieldterminal.data.ReadCache.get(
            "skills.conditioning", tables = null,
            serializer = ConditioningHistory.serializer(),
        ) { skills.loadConditioning() }

    // VO2max percentile against the age- and sex-matched table (null when no table fits); the Running level's population basis.
    suspend fun vo2Percentile(force: Boolean = false): Double? {
        // Read aging first, outside the cache lock: the mutex is not reentrant.
        val overview = aging(force)
        return cached({ vo2 }, { vo2 = it }, force) { vo2Placement(client, overview)?.percentile }
    }
}
