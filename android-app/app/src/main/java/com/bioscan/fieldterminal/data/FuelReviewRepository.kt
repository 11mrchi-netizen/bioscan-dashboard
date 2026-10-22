package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.events.AthleteEvent
import com.bioscan.fieldterminal.domain.fuel.EventSnapshot
import com.bioscan.fieldterminal.domain.fuel.FuelReviewOutcome
import com.bioscan.fieldterminal.domain.fuel.Intensity
import com.bioscan.fieldterminal.domain.fuel.SweatTest
import com.bioscan.fieldterminal.domain.fuel.StepSnapshot
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.time.LocalDate

// The review after an event (supabase/migrations/20261028_fuel_plan_reviews.sql; docs/fuel-planning/README.md phase 8):
// the plan's items marked taken, half or skipped go into fueling_intake_log for the session that recorded the event
// (the same single-counting ledger as any run fuel, so the day's nutrition totals include them); the review row keeps the
// totals, the gut and energy scores and the weigh-ins; with both weights the race is also saved as a sweat test.

enum class StepStatus(val key: String, val label: String, val share: Double) {
    TAKEN("consumed", "TAKEN", 1.0), HALF("partial", "HALF", 0.5), SKIPPED("skipped", "SKIPPED", 0.0)
}

@Serializable
private data class PlanJsonRow(val plan: JsonObject)

@Serializable
private data class SessionLengthRow(val id: Long, @SerialName("duration_min") val durationMin: Double? = null, val type: String? = null)

@Serializable
data class FuelReviewRow(
    @SerialName("event_id") val eventId: Long? = null,
    @SerialName("exercise_session_id") val exerciseSessionId: Long? = null,
    @SerialName("reviewed_on") val reviewedOn: String = LocalDate.now().toString(),
    @SerialName("duration_min") val durationMin: Double? = null,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("fluid_ml") val fluidMl: Double? = null,
    @SerialName("caffeine_mg") val caffeineMg: Double? = null,
    @SerialName("gi_score") val giScore: Int? = null,
    @SerialName("energy_score") val energyScore: Int? = null,
    @SerialName("weight_before_kg") val weightBeforeKg: Double? = null,
    @SerialName("weight_after_kg") val weightAfterKg: Double? = null,
    val notes: String? = null,
)

data class ReviewContext(val plan: EventSnapshot?, val sessionDurationMin: Double?, val existing: FuelReviewRow?)

private val json = Json { ignoreUnknownKeys = true }

class FuelReviewRepository(private val supabase: SupabaseClient) {

    suspend fun context(event: AthleteEvent): ReviewContext {
        val plan = runCatching {
            supabase.postgrest.from("fuel_plans")
                .select(Columns.list("plan")) {
                    filter { eq("kind", "event"); eq("ref_key", event.id.toString()) }
                    order("created_at", Order.DESCENDING)
                    limit(1)
                }
                .decodeList<PlanJsonRow>().firstOrNull()?.let { json.decodeFromJsonElement<EventSnapshot>(it.plan) }
        }.getOrNull()
        val duration = event.exerciseSessionId?.let { sid ->
            runCatching {
                supabase.postgrest.from("exercise_sessions").select(Columns.list("id,duration_min,type")) { filter { eq("id", sid) } }
                    .decodeList<SessionLengthRow>().firstOrNull()?.durationMin
            }.getOrNull()
        }
        val existing = runCatching {
            supabase.postgrest.from("fuel_plan_reviews").select { filter { eq("event_id", event.id!!) } }.decodeList<FuelReviewRow>().firstOrNull()
        }.getOrNull()
        return ReviewContext(plan, duration, existing)
    }

    /** Saves the review; returns its outcome for the tolerance suggestion. */
    suspend fun save(
        event: AthleteEvent,
        steps: List<Pair<StepSnapshot, StepStatus>>,
        durationMin: Double,
        manualCarbsG: Double?,
        manualFluidMl: Double?,
        giScore: Int,
        energyScore: Int?,
        weightBeforeKg: Double?,
        weightAfterKg: Double?,
        notes: String?,
    ): FuelReviewOutcome {
        val sessionId = event.exerciseSessionId
        if (sessionId != null) {
            val fueling = FuelingRepository(supabase)
            steps.forEach { (s, status) ->
                fueling.logIntake(
                    NewFuelingIntake(
                        exerciseSessionId = sessionId,
                        description = s.items.joinToString(", ").take(300),
                        status = status.key,
                        elapsedSeconds = s.elapsedMin * 60,
                        carbsG = s.carbsG * status.share,
                        fluidMl = s.fluidMl * status.share,
                        sodiumMg = s.sodiumMg * status.share,
                        caffeineMg = s.caffeineMg * status.share,
                        source = "fuel_plan",
                        clientKey = "plan:${event.id}:${s.elapsedMin}",
                    ),
                )
            }
        }
        val carbs = manualCarbsG ?: steps.sumOf { (s, st) -> s.carbsG * st.share }
        val fluid = manualFluidMl ?: steps.sumOf { (s, st) -> s.fluidMl * st.share }
        val caffeine = steps.sumOf { (s, st) -> s.caffeineMg * st.share }
        supabase.postgrest.from("fuel_plan_reviews").upsert(
            FuelReviewRow(
                eventId = event.id, exerciseSessionId = sessionId, durationMin = durationMin, carbsG = carbs, fluidMl = fluid,
                caffeineMg = caffeine, giScore = giScore, energyScore = energyScore, weightBeforeKg = weightBeforeKg,
                weightAfterKg = weightAfterKg, notes = notes?.trim()?.ifBlank { null },
            ),
        ) { onConflict = "user_id,event_id" }
        if (weightBeforeKg != null && weightAfterKg != null && durationMin >= 30) {
            runCatching {
                SweatTestsRepository(supabase).add(
                    SweatTest(event.date, event.sport.sessionTypes.first().let { if (it in setOf("run", "ride", "hike", "swim")) it else "other" },
                        Intensity.RACE, durationMin, weightBeforeKg, weightAfterKg, fluid),
                    sessionId, "From the review of ${event.name}",
                )
            }
        }
        return FuelReviewOutcome(event.date, if (durationMin > 0) carbs / (durationMin / 60) else 0.0, giScore, energyScore)
    }
}
