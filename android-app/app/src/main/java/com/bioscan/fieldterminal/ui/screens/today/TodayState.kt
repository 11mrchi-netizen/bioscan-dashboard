package com.bioscan.fieldterminal.ui.screens.today

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.EveningCheckinRepository
import com.bioscan.fieldterminal.data.FuelPlanRepository
import com.bioscan.fieldterminal.data.NutritionRepository
import com.bioscan.fieldterminal.data.ReadCache
import com.bioscan.fieldterminal.data.ScoresRepository
import com.bioscan.fieldterminal.data.StatusRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TodayFuelResult
import com.bioscan.fieldterminal.data.catching
import com.bioscan.fieldterminal.domain.EveningCheckin
import com.bioscan.fieldterminal.ui.components.LoadGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime

// TODAY's data and how it is kept current. TodayHomeScreen draws it and holds only what is open on screen (a sheet, an
// overlay). The page is shown at once from the kept copy (TodayStore: memory, then disk) and rebuilt in the background
// when that copy went stale; a rebuild that fails leaves what was on screen.

@Stable
internal class TodayState(kept: TodaySnapshot?) {
    /** TODAY decides itself when to rebuild (TodayStore), so it does not reload on every cache refresh. */
    val guard = LoadGuard()

    var view by mutableStateOf(kept?.view)
        private set
    var nut by mutableStateOf(kept?.nut)
        private set
    var fuel by mutableStateOf(kept?.fuel)
        private set
    var previews by mutableStateOf(kept?.previews)
        private set
    var closed by mutableStateOf(kept?.closed ?: emptySet())
        private set
    // The plan and SO FAR as last drawn: shown (from disk, after Android closed the app) until the fuel plan has loaded.
    var keptRows by mutableStateOf(kept?.rows)
        private set
    var keptSoFar by mutableStateOf(kept?.soFar)
        private set
    var loading by mutableStateOf(kept == null)
        private set
    private var scoresKey = kept?.key

    /** Rebuild the page now: after something was logged from it. */
    fun reload() = guard.retry()

    /** True when the page on screen should be rebuilt: a sync landed, the day changed, or it is 15 minutes old. */
    fun isStale(): Boolean = !loading && !TodayStore.isFresh(TodayStore.snapshot, LocalDate.now())

    /** One build of the page; runs for every attempt of [guard]. */
    suspend fun load(context: Context) {
        // First open of the tab with a fresh kept page: nothing to load.
        if (guard.attempt == 0 && TodayStore.isFresh(TodayStore.snapshot, LocalDate.now())) {
            loading = false
            return
        }
        val day = LocalDate.now()
        // After Android closed the app: the page kept on disk for today goes on screen at once.
        if (view == null) {
            TodayStore.readDisk(context, day)?.let { k ->
                view = k.view
                scoresKey = k.key
                keptRows = k.rows
                keptSoFar = k.soFar
                closed = k.closed
            }
        }
        loading = true
        guard.run {
            var partFailed = false
            // Its rebuild must see what was just written (a sync, a log): reads wait for the network, never an old copy.
            withContext(ReadCache.RequireFresh) { coroutineScope {
                val client = SupabaseClientProvider.client
                val status = StatusRepository(client)
                // The parts around the scores (plan, fuel, nutrition, check-ins) each load on their own. One that fails keeps
                // what the page already showed for this day instead of going blank, and leaves the page marked stale so the
                // next minute tries again.
                val sameDay = TodayStore.snapshot?.day == day
                suspend fun <T : Any> part(shown: T?, load: suspend () -> T?): T? =
                    catching { load() }.getOrElse { partFailed = true; shown.takeIf { sameDay } }
                // The overview only supplies the open illness / injury that caps CHARGE: without it the scores still load.
                val overviewD = async { part(null) { status.loadOverview() } }
                val previewsD = async { part(previews) { status.loadTodayPreviews() } }
                val n = part(nut) { NutritionRepository(client).loadOverview() }
                nut = n
                val fuelD = async { part(fuel) { (FuelPlanRepository(client).loadToday(context, n, ZonedDateTime.now()) as? TodayFuelResult.Ready)?.fuel } }
                val closedD = async {
                    part(closed) { EveningCheckinRepository(client).loadSince(LocalDate.now().minusDays(1)).map(EveningCheckin::date).toSet() } ?: emptySet()
                }
                val o = overviewD.await()
                // The score engine only runs again when what it reads changed (equal key: the kept scores stand).
                val keyed = ScoresRepository(client).loadKeyed(context, n, o?.healthFlag, previous = view, previousKey = scoresKey)
                view = keyed.view
                scoresKey = keyed.key
                previews = previewsD.await()
                fuel = fuelD.await()
                closed = closedD.await()
            } }
            // Keep the finished page, stamped after the load so the load's own bookkeeping writes don't count: in memory,
            // and on disk for the next time the app is opened today.
            val drawnAt = LocalDateTime.now()
            val rows = planRows(drawnAt, fuel, previews, view?.scores?.charge?.score, day.minusDays(1) in closed, day in closed, view?.sleepTarget)
            val sf = soFarOf(nut, fuel)
            keptRows = rows
            keptSoFar = sf
            TodayStore.snapshot = TodaySnapshot(day, view, nut, fuel, previews, closed, if (partFailed) 0L else System.currentTimeMillis(), ReadCache.writeStamp(TodayStore.TABLES), scoresKey, rows, sf)
            val v = view
            val k = scoresKey
            if (v != null && k != null && !partFailed) TodayStore.writeDisk(context, KeptToday(TodayStore.FORMAT, day, System.currentTimeMillis(), k, v, rows, sf, closed))
        }
        loading = false
    }
}

@Composable
internal fun rememberTodayState(): TodayState {
    val context = LocalContext.current
    val page = remember { TodayState(TodayStore.forDay(LocalDate.now())) }
    val view = page.view
    // Once TODAY has its scores, the reads behind the pages most opened from it (Sleep, Cardio, Load, Body) are warmed
    // in the background, so those pages open from memory. Each read is cached (data/ReadCache.kt); a failure is ignored.
    LaunchedEffect(view?.day) {
        if (view == null) return@LaunchedEffect
        delay(1_500)
        val repo = AnalysisRepository(SupabaseClientProvider.client)
        withContext(Dispatchers.IO) {
            listOf<suspend () -> Unit>(
                { repo.loadExpectedBands() }, { repo.loadHeartRateDay() }, { repo.loadHeartRateRanges() },
                { repo.loadBodyMetrics() }, { repo.loadFitnessFatigue() },
            ).forEach { catching { it() } }
        }
    }
    LaunchedEffect(Unit) { catching { EveningCheckinRepository(SupabaseClientProvider.client).recordDailyContext() } }

    // While TODAY is on screen: once a minute, rebuild in the background if the kept page went stale. The page on screen
    // stays until the new one is ready.
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            if (page.isStale()) page.reload()
        }
    }
    LaunchedEffect(page.guard.attempt) { page.load(context) }
    return page
}
