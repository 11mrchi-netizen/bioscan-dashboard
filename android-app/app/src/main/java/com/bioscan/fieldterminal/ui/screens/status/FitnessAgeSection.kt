package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.Vo2MaxRow
import com.bioscan.fieldterminal.domain.ActivitySession
import com.bioscan.fieldterminal.domain.BodyCheckinRecord
import com.bioscan.fieldterminal.domain.BodySite
import com.bioscan.fieldterminal.domain.FitnessAgeReading
import com.bioscan.fieldterminal.domain.WallClock
import com.bioscan.fieldterminal.domain.fitnessAgeOn
import com.bioscan.fieldterminal.domain.fitnessAgeWeeks
import com.bioscan.fieldterminal.domain.fitnessAgeWords
import com.bioscan.fieldterminal.domain.vo2MaxSeries
import com.bioscan.fieldterminal.ui.screens.detail.EmptyLine
import com.bioscan.fieldterminal.ui.screens.detail.Fmt
import com.bioscan.fieldterminal.ui.screens.detail.LocalRangeDays
import com.bioscan.fieldterminal.ui.screens.detail.Section
import com.bioscan.fieldterminal.ui.screens.detail.TrendChart
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

private const val FITNESS_AGE_INFO =
    "A fitness comparison in years, not a biological age: the age at which a typical person (resting heart rate 65, " +
        "moderate activity) would have the fitness your numbers suggest. From the HUNT study's non-exercise model (Nes 2011), " +
        "using your 7-day median resting heart rate and an activity index rebuilt from the sessions you logged in the 4 weeks " +
        "before: how often, how long and how hard. The model's error is large (about ±5.7 ml/kg/min of VO2peak for men), so " +
        "it is shown with a ±5-year band and updated weekly, each Saturday. Read the direction over weeks, not the number. " +
        "A waist measurement from a body check-in adds the VO2peak estimate itself."

private data class FitnessAgeData(val weekly: List<FitnessAgeReading>, val watchVo2: Pair<LocalDate, Double>?)

/** ME › BODY: Fitness Age, weekly, with the VO2peak estimate when a waist is measured and the watch's VO2max beside it. */
@Composable
internal fun FitnessAgeSection(sex: String?, birth: LocalDate?, checkins: List<BodyCheckinRecord>) {
    val days = LocalRangeDays.current
    val waist = checkins.sortedByDescending { it.occurredAt }.firstNotNullOfOrNull { it.value(BodySite.WAIST_NAVEL) ?: it.value(BodySite.WAIST_MIDPOINT) }
    val data by produceState<FitnessAgeData?>(null, sex, birth, waist) {
        if (sex == null || birth == null) { value = FitnessAgeData(emptyList(), null); return@produceState }
        val analysis = AnalysisRepository(SupabaseClientProvider.client)
        val zone = ZoneId.systemDefault()
        val rhr = runCatching { analysis.loadWearableDaily() }.getOrDefault(emptyList())
            .mapNotNull { r -> r.rhr?.let { v -> runCatching { LocalDate.parse(r.date) }.getOrNull()?.let { it to v } } }.toMap()
        val rows = runCatching { analysis.loadExerciseSessionsForTrainingLoad() }.getOrDefault(emptyList())
        val sessions = rows.mapNotNull { s ->
            // Session starts are logged times (local digits with a zero offset, domain/WallClock.kt).
            val day = WallClock.zoned(s.startTime, zone)?.toLocalDate() ?: return@mapNotNull null
            ActivitySession(day, s.durationMin ?: return@mapNotNull null, s.avgHr, s.rpe)
        }
        val today = LocalDate.now()
        val maxHr = rows.filter { s -> WallClock.zoned(s.startTime, zone)?.toLocalDate()?.isAfter(today.minusYears(1)) == true }.mapNotNull { it.maxHr }.maxOrNull()
        val weekly = fitnessAgeWeeks(today, 52).mapNotNull { fitnessAgeOn(it, sex == "male", birth, rhr, sessions, maxHr, waist) }
        val vo2 = runCatching {
            SupabaseClientProvider.client.postgrest.from("wearable_daily").select(Columns.list("date,vo2max")) {
                order("date", Order.DESCENDING); limit(60)
            }.decodeList<Vo2MaxRow>()
        }.getOrDefault(emptyList())
        value = FitnessAgeData(weekly, vo2MaxSeries(vo2).lastOrNull())
    }
    Section("FITNESS AGE", info = FITNESS_AGE_INFO, trailing = "WEEKLY") {
        val d = data
        when {
            sex == null || birth == null -> EmptyLine("Needs your date of birth and sex in Setup › Profile.")
            d == null -> EmptyLine("Working it out…")
            d.weekly.isEmpty() -> EmptyLine("Needs resting heart rate on at least 4 days of a week.")
            else -> {
                val r = d.weekly.last()
                Text("${fitnessAgeWords(r)} years", style = FTType.MetricMedium, color = FT.TextPrimary)
                Text("You are ${r.age.toInt()} · week to ${r.asOf.dayOfMonth} ${r.asOf.month.name.take(3).lowercase()}", style = FTType.Caption, color = FT.TextMuted)
                Text(
                    "Resting HR ${r.restingHr.roundToInt()} bpm (7-day median) · ${"%.1f".format(r.pa.perWeek)} sessions a week · activity index ${"%.1f".format(r.pa.value)} of 15",
                    style = FTType.Caption, color = FT.TextSecondary,
                )
                r.vo2peak?.let { Text("Estimated VO2peak ${it.roundToInt()} ml/kg/min (±${r.see.roundToInt()})", style = FTType.Caption, color = FT.TextSecondary) }
                    ?: Text("Add your waist at a body check-in to see the VO2peak estimate.", style = FTType.Caption, color = FT.TextMuted)
                d.watchVo2?.let { (date, v) -> Text("The watch's VO2max: ${"%.0f".format(v)} ml/kg/min (${date.dayOfMonth} ${date.month.name.take(3).lowercase()})", style = FTType.Caption, color = FT.TextSecondary) }
                TrendChart(d.weekly.associate { it.asOf to it.fitnessAge }, LocalDate.now(), days, null, Fmt.int, color = FT.DomainHeart)
            }
        }
    }
}
