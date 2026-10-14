package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.domain.SleepNight
import com.bioscan.fieldterminal.domain.healthspan.HealthspanAxis
import com.bioscan.fieldterminal.domain.healthspan.HealthspanAxisInput
import com.bioscan.fieldterminal.domain.healthspan.bodyCompositionAxisInput
import com.bioscan.fieldterminal.domain.healthspan.cardioAxisInput
import com.bioscan.fieldterminal.domain.healthspan.movementAxisInput
import com.bioscan.fieldterminal.domain.healthspan.nutritionAxisInput
import com.bioscan.fieldterminal.domain.healthspan.recoveryAxisInput
import com.bioscan.fieldterminal.domain.healthspan.sleepAxisInput
import com.bioscan.fieldterminal.domain.healthspan.strengthAxisInput
import com.bioscan.fieldterminal.domain.levels.ratedLifts
import com.bioscan.fieldterminal.domain.liftE1rmSeries
import io.github.jan.supabase.SupabaseClient
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// Feeds the User tab's Healthspan heptagon. Reuses the loaders the tile screens already use (no second read
// path for any series), loads each shared source once, and reports each axis the moment it resolves so the
// card fills in progressively instead of waiting for the slowest query. A source that fails to load leaves
// its axes "unavailable" with a reason; it never fails the whole card.
class HealthspanInputsRepository(supabase: SupabaseClient) {
    private val analysis = AnalysisRepository(supabase)
    private val profileRepo = UserProfileRepository(supabase)
    private val skills = SkillHistoryRepository(supabase)

    // `aging` is the User page's already-loading AgingProfileOverview (shared, so the Cardio axis does not
    // trigger a second lab/VO2 read). `onAxis` is called once per axis, from the calling scope.
    suspend fun load(
        aging: suspend () -> AgingProfileOverview?,
        calorieGoalKcal: Double?,
        asOf: LocalDate = LocalDate.now(),
        onAxis: (HealthspanAxisInput) -> Unit,
    ) = coroutineScope {
        val wearable = async { runCatching { analysis.loadWearableDaily() }.getOrNull() }
        val sleep = async { runCatching { analysis.loadSleepDaily() }.getOrNull() }
        val body = async { runCatching { analysis.loadBodyMetrics() }.getOrNull() }
        val sessions = async { runCatching { analysis.loadExerciseSessionsForTrainingLoad() }.getOrNull() }
        val nutrition = async { runCatching { analysis.loadDailyNutrition() }.getOrNull() }
        val profile = async { runCatching { profileRepo.loadProfile() }.getOrNull() }
        val strength = async { runCatching { skills.loadStrength() }.getOrNull() }

        fun failed(axis: HealthspanAxis) = HealthspanAxisInput.unavailable(axis, "Couldn't load this data — check connection")

        launch {
            val s = sleep.await()
            onAxis(if (s == null) failed(HealthspanAxis.SLEEP) else sleepAxis(s, asOf))
        }
        launch {
            val w = wearable.await()
            val sess = sessions.await()
            onAxis(
                if (w == null) failed(HealthspanAxis.MOVEMENT)
                else movementAxisInput(
                    w.series { it.steps },
                    sess.orEmpty().mapNotNull { runCatching { OffsetDateTime.parse(it.startTime) }.getOrNull() },
                    asOf,
                ),
            )
        }
        launch {
            val w = wearable.await()
            onAxis(if (w == null) failed(HealthspanAxis.RECOVERY) else recoveryAxisInput(w.series { it.hrv }, w.series { it.rhr }, asOf))
        }
        launch { onAxis(cardioAxisInput(runCatching { aging() }.getOrNull()?.cardioAge)) }
        launch {
            val st = strength.await()
            onAxis(
                if (st == null) failed(HealthspanAxis.STRENGTH)
                else strengthAxisInput(ratedLifts(liftE1rmSeries(st.sets), st.bodyWeightKg, asOf), bodyWeightMissing = st.bodyWeightKg == null),
            )
        }
        launch {
            val b = body.await()
            val sex = profile.await()?.sex
            onAxis(
                if (b == null) failed(HealthspanAxis.BODY_COMPOSITION)
                else bodyCompositionAxisInput(b.mapNotNull { row -> row.bodyFatPct?.let { v -> parseDate(row.date)?.to(v) } }, sex, asOf),
            )
        }
        launch {
            val n = nutrition.await()
            onAxis(if (n == null) failed(HealthspanAxis.NUTRITION) else nutritionAxisInput(n, calorieGoalKcal, asOf))
        }
    }

    private fun sleepAxis(s: List<SleepAnalysisRow>, asOf: LocalDate): HealthspanAxisInput {
        val hours = s.mapNotNull { r -> r.hours?.let { parseDate(r.date)?.to(it) } }
        val deep = s.mapNotNull { r -> r.deepMin?.let { parseDate(r.date)?.to(it) } }
        val resp = s.mapNotNull { r -> r.respiratoryRate?.let { parseDate(r.date)?.to(it) } }
        val nights = s.mapNotNull { r ->
            val bed = r.bedtime?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val wake = r.wakeTime?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val date = parseDate(r.date)
            if (bed != null && wake != null && date != null) SleepNight(date, bed, wake) else null
        }
        return sleepAxisInput(hours, deep, resp, nights, asOf)
    }

    private fun List<WearableAnalysisRow>.series(pick: (WearableAnalysisRow) -> Double?): List<Pair<LocalDate, Double>> =
        mapNotNull { r -> pick(r)?.let { v -> parseDate(r.date)?.to(v) } }

    private fun parseDate(s: String): LocalDate? = runCatching { LocalDate.parse(s) }.getOrNull()
}
