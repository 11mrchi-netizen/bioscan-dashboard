package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.ExerciseSessionRow
import com.bioscan.fieldterminal.domain.LiftSet
import com.bioscan.fieldterminal.domain.bestLifts
import com.bioscan.fieldterminal.domain.aging.GripReading
import com.bioscan.fieldterminal.domain.aging.latestGrip
import com.bioscan.fieldterminal.domain.analysis.ComparisonResult
import com.bioscan.fieldterminal.domain.analysis.ComparisonState
import com.bioscan.fieldterminal.domain.aging.DatedValue
import com.bioscan.fieldterminal.domain.aging.HealthspanInputs
import com.bioscan.fieldterminal.domain.aging.HealthspanSummary
import com.bioscan.fieldterminal.domain.aging.LabObservation
import com.bioscan.fieldterminal.domain.aging.healthspanSummary
import com.bioscan.fieldterminal.domain.aging.latestLab
import com.bioscan.fieldterminal.domain.SleepNight
import com.bioscan.fieldterminal.domain.comparison.PopulationContext
import com.bioscan.fieldterminal.domain.comparison.comparePopulation
import com.bioscan.fieldterminal.domain.comparison.resolveArtifact
import com.bioscan.fieldterminal.domain.evaluateSri
import com.bioscan.fieldterminal.domain.ratioWaist
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

@Serializable
private data class HsDrawRow(val id: Long, @SerialName("draw_date") val drawDate: String)

@Serializable
private data class HsResultRow(
    @SerialName("draw_id") val drawId: Long,
    @SerialName("marker_name") val markerName: String,
    val value: Double? = null,
    val unit: String? = null,
)

@Serializable
private data class HsGripRow(@SerialName("occurred_at") val occurredAt: String, val hand: String, val kg: Double)

data class HealthspanBundle(
    val summary: HealthspanSummary,
    // The VO2max placement used for the running level on the same page; null when no table fits.
    val vo2Comparison: ComparisonResult?,
)

private const val WINDOW_DAYS = 30L

private fun later(a: LocalDate, b: LocalDate?): LocalDate = if (b != null && b.isAfter(a)) b else a

// VO2max placement against the age- and sex-matched table, or null when the person's age/sex/VO2max is unknown or no table covers
// them (never the nearest table). Shared by the Healthspan cardio factor and the Running level on the User tab.
suspend fun vo2Placement(supabase: SupabaseClient, aging: AgingProfileOverview): ComparisonResult? {
    val sex = UserProfileRepository(supabase).loadProfile()?.sex?.takeIf { it == "male" || it == "female" }
    val age = aging.chronologicalAgeYears
    val vo2 = aging.latestVo2Max
    if (vo2 == null || age == null || sex == null) return null
    val artifacts = BenchmarkArtifactRepository(supabase).loadArtifacts("vo2max")
    val context = PopulationContext(ageYears = age, sex = sex, geography = null)
    val table = resolveArtifact("vo2max", context, artifacts)
    val fits = table != null && table.population.sex.equals(sex, ignoreCase = true) &&
        age >= (table.population.ageLow ?: Int.MAX_VALUE) && age <= (table.population.ageHigh ?: Int.MIN_VALUE)
    if (!fits) return null
    return comparePopulation("vo2max", vo2.second, context, artifacts).takeIf { it.state == ComparisonState.OK && it.confidence.met }
}

// Analysis Layer 3 / Phase 8. Gathers what the Healthspan factors need and hands it, unmodified, to the pure domain
// function. This class decides nothing about cut-offs; it only refuses to pass a value it cannot vouch for.
class HealthspanRepository(private val supabase: SupabaseClient) {

    suspend fun load(aging: AgingProfileOverview): HealthspanBundle {
        val asOf = LocalDate.now()
        val sex = UserProfileRepository(supabase).loadProfile()?.sex?.takeIf { it == "male" || it == "female" }
        val age = aging.chronologicalAgeYears
        val analysis = AnalysisRepository(supabase)
        val breakStarts = analysis.loadSeriesBreakStarts()
        val benchmarks = BenchmarkArtifactRepository(supabase)

        // VO2max against a table that actually covers this age and sex; never the nearest table.
        val vo2 = aging.latestVo2Max
        val vo2Comparison = vo2Placement(supabase, aging)

        // Sleep and steps: last 30 days, restarted at the last confirmed series break.
        val sleepRows = analysis.loadSleepDaily()
        val sleepFrom = later(asOf.minusDays(WINDOW_DAYS), breakStarts.start("sleep_hours"))
        val sleepInWindow = sleepRows.filter { LocalDate.parse(it.date) >= sleepFrom }
        val hours = sleepInWindow.mapNotNull { it.hours }
        val nights = sleepRows.mapNotNull { row ->
            val date = LocalDate.parse(row.date)
            if (date < (breakStarts.start("sleep_hours") ?: LocalDate.MIN)) return@mapNotNull null
            val bed = row.bedtime?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val wake = row.wakeTime?.let { runCatching { Instant.parse(it) }.getOrNull() }
            if (bed != null && wake != null) SleepNight(date, bed, wake) else null
        }

        val stepsFrom = later(asOf.minusDays(WINDOW_DAYS), breakStarts.start("steps"))
        // A 0-step day is the watch not being worn, not a day of no movement.
        val steps = analysis.loadWearableDaily()
            .filter { LocalDate.parse(it.date) >= stepsFrom }
            .mapNotNull { it.steps }
            .filter { it > 0 }
            .sorted()
        val stepsMedian = steps.takeIf { it.isNotEmpty() }?.let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2.0 }
        val stepsBand = stepsMedian?.let {
            comparePopulation("steps", it, PopulationContext(age, sex, null), benchmarks.loadArtifacts("steps")).bandLabel
        }

        // Strength: best estimated 1RM per lift over about six months, the latest body weight, the latest logged grip.
        val liftSets = supabase.postgrest.from("exercise_sessions")
            .select(columns = Columns.list("id,type,start_time,source,details")) {
                filter {
                    eq("type", "strength")
                    gte("start_time", asOf.minusDays(183).toString())
                }
                limit(1000)
            }
            .decodeList<ExerciseSessionRow>()
            .flatMap { session ->
                val date = runCatching { OffsetDateTime.parse(session.startTime).toLocalDate() }.getOrNull() ?: return@flatMap emptyList()
                session.details.exercises.orEmpty().flatMap { ex -> ex.sets.map { LiftSet(ex.name, it.weightKg, it.reps, date) } }
            }
        val bodyWeight = analysis.loadBodyMetrics()
            .filter { it.weightKg != null }
            .maxByOrNull { LocalDate.parse(it.date) }
        val grip = latestGrip(
            supabase.postgrest.from("grip_strength_log")
                .select(columns = Columns.list("occurred_at,hand,kg")) {
                    order("occurred_at", Order.DESCENDING)
                    limit(20)
                }
                .decodeList<HsGripRow>()
                .mapNotNull { r -> runCatching { OffsetDateTime.parse(r.occurredAt).toLocalDate() }.getOrNull()?.let { GripReading(r.kg, r.hand, it) } },
        )

        val labs = loadLabObservations()
        fun lab(vararg names: String) = latestLab(labs) { m -> names.any { m.equals(it, ignoreCase = true) } }
        // Waist from the monthly body check-in's tape (the WHO site, else the navel) when it is newer than a lab value;
        // height from Health Connect or the check-in when no lab has it (an adult height does not age out, so it is dated
        // today for the freshness gate).
        val checkins = BodyCheckinRepository(supabase)
        val tapeWaist = runCatching { checkins.loadHistory() }.getOrElse { emptyList() }
            .lastOrNull { ratioWaist(it) != null }
            ?.let { DatedValue(ratioWaist(it)!!, it.date, "cm") }
        val labWaist = lab("Waistline")
        val waist = listOfNotNull(labWaist, tapeWaist).maxByOrNull { it.date }
        val height = lab("Body Height") ?: runCatching { checkins.latestHeightCm() }.getOrNull()?.let { DatedValue(it, asOf, "cm") }
        return HealthspanBundle(
            summary = healthspanSummary(
                HealthspanInputs(
                    asOf = asOf,
                    sex = sex,
                    vo2max = vo2?.let { DatedValue(it.second, it.first, "mL/kg/min") },
                    vo2Percentile = vo2Comparison?.percentile,
                    phenoAge = aging.phenoAge,
                    lifts = bestLifts(liftSets),
                    bodyWeightKg = bodyWeight?.let { DatedValue(it.weightKg!!, LocalDate.parse(it.date), "kg") },
                    grip = grip,
                    waistCm = waist,
                    heightCm = height,
                    sleepMeanHours = hours.takeIf { it.isNotEmpty() }?.average(),
                    sleepNights = hours.size,
                    sri = evaluateSri(nights),
                    stepsMedian = stepsMedian,
                    stepsDays = steps.size,
                    stepsBand = stepsBand,
                    triglyceridesMgDl = lab("Triglyceride", "Triglycerides"),
                    hdlMgDl = lab("HDL Cholesterol", "HDL"),
                    hba1cPct = latestLab(labs) { it.equals("HbA1c", true) || it.contains("a1c", true) },
                    fastingGlucoseMgDl = lab("Fasting blood sugar", "Fasting glucose"),
                ),
            ),
            vo2Comparison = vo2Comparison,
        )
    }

    private suspend fun loadLabObservations(): List<LabObservation> {
        val draws = supabase.postgrest.from("lab_draws")
            .select(columns = Columns.list("id,draw_date"))
            .decodeList<HsDrawRow>()
            .mapNotNull { d -> runCatching { LocalDate.parse(d.drawDate) }.getOrNull()?.let { d.id to it } }
            .toMap()
        return supabase.postgrest.from("lab_results")
            .select(columns = Columns.list("draw_id,marker_name,value,unit"))
            .decodeList<HsResultRow>()
            .mapNotNull { r ->
                val date = draws[r.drawId] ?: return@mapNotNull null
                r.value?.let { LabObservation(r.markerName, it, r.unit, date) }
            }
    }
}
