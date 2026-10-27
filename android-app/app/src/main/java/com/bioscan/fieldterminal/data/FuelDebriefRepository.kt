package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.events.AthleteEvent
import com.bioscan.fieldterminal.domain.fuel.BlockActual
import com.bioscan.fieldterminal.domain.fuel.FuelBlock
import com.bioscan.fieldterminal.domain.fuel.FuelReviewOutcome
import com.bioscan.fieldterminal.domain.fuel.FuelSymptom
import com.bioscan.fieldterminal.domain.fuel.Intensity
import com.bioscan.fieldterminal.domain.fuel.SweatTest
import com.bioscan.fieldterminal.domain.fuel.debriefKeyPrefix
import com.bioscan.fieldterminal.domain.fuel.debriefRows
import com.bioscan.fieldterminal.domain.fuel.scheduleAdherence
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

// The debrief after a race or long run (docs/race-fuel-rework/plan.md): what of the schedule was taken, any discomfort, how the
// person felt. It writes one fueling_intake_log row per intake (the single-counting ledger every other fuel entry uses), the
// review row, and, with both weigh-ins, a sweat test. The weekly OSTRC form and supplement_log are not touched: a pre-workout
// taken in the race is counted once, here.

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
    val symptoms: List<String> = emptyList(),
    @SerialName("fatigue_score") val fatigueScore: Int? = null,
    @SerialName("thirst_score") val thirstScore: Int? = null,
    @SerialName("schedule_held") val scheduleHeld: Double? = null,
    @SerialName("schedule_id") val scheduleId: Long? = null,
)

/** What a debrief is about: the activity (null until the recorded session is linked), the event if any, and the schedule that was made for it. */
data class DebriefTarget(
    val sessionId: Long?,
    val eventId: Long?,
    val title: String,
    val date: LocalDate,
    val type: String,
    val durationMin: Double?,
    val schedule: FuelScheduleRow?,
    val existing: FuelReviewRow?,
)

/** Everything the person answered besides what they took. */
data class DebriefAnswers(
    val durationMin: Double,
    val symptoms: Set<FuelSymptom>,
    val giScore: Int,
    val energyScore: Int?,
    val fatigueScore: Int?,
    val thirstScore: Int?,
    val weightBeforeKg: Double?,
    val weightAfterKg: Double?,
    val notes: String?,
    /** Totals typed in when there is no schedule to check. */
    val manualCarbsG: Double? = null,
    val manualFluidMl: Double? = null,
)

@Serializable
private data class SessionLinkRow(val id: Long, val name: String? = null, @SerialName("event_date") val eventDate: String? = null)

@Serializable
private data class PlannedLinkRow(val id: Long)

@Serializable
private data class KeyRow(@SerialName("client_key") val clientKey: String)

/** The gut score the tolerance suggestion should see: what was scored, raised by the discomfort that was ticked. */
fun effectiveGutScore(score: Int, symptoms: Set<FuelSymptom>): Int = maxOf(
    score,
    when {
        FuelSymptom.VOMITING in symptoms -> 7
        FuelSymptom.NAUSEA in symptoms || FuelSymptom.CRAMPS in symptoms -> 5
        symptoms.isNotEmpty() -> 3
        else -> 0
    },
)

class FuelDebriefRepository(private val supabase: SupabaseClient) {
    private val schedules = FuelScheduleRepository(supabase)

    /** The schedule for an activity: the event's, else the planned session linked to it, else one made on the activity itself. */
    private suspend fun scheduleFor(sessionId: Long?, eventId: Long?): FuelScheduleRow? {
        if (eventId != null) schedules.load("event", eventId.toString())?.let { return it }
        if (sessionId != null) {
            val planned = runCatching {
                supabase.postgrest.from("planned_sessions").select(Columns.list("id")) { filter { eq("exercise_session_id", sessionId) }; limit(1) }
                    .decodeList<PlannedLinkRow>().firstOrNull()
            }.getOrNull()
            if (planned != null) schedules.load("planned", "PLANNED_SESSION:${planned.id}")?.let { return it }
            return schedules.load("session", sessionId.toString())
        }
        return null
    }

    private suspend fun existingReview(sessionId: Long?, eventId: Long?): FuelReviewRow? = runCatching {
        supabase.postgrest.from("fuel_plan_reviews").select {
            filter { if (eventId != null) eq("event_id", eventId) else eq("exercise_session_id", sessionId ?: -1L) }
        }.decodeList<FuelReviewRow>().firstOrNull()
    }.getOrNull()

    suspend fun targetForSession(s: FuelingSessionRow): DebriefTarget {
        val event = runCatching {
            supabase.postgrest.from("athlete_events").select(Columns.list("id,name,event_date")) { filter { eq("exercise_session_id", s.id) }; limit(1) }
                .decodeList<SessionLinkRow>().firstOrNull()
        }.getOrNull()
        val date = runCatching { java.time.OffsetDateTime.parse(s.startTime).toLocalDate() }.getOrDefault(LocalDate.now())
        return DebriefTarget(
            s.id, event?.id, event?.name ?: s.type.replaceFirstChar { it.uppercase() }, date, s.type, s.durationMin,
            scheduleFor(s.id, event?.id), existingReview(s.id, event?.id),
        )
    }

    suspend fun targetForEvent(event: AthleteEvent): DebriefTarget {
        val sid = event.exerciseSessionId
        val duration = sid?.let { id ->
            runCatching {
                supabase.postgrest.from("exercise_sessions").select(Columns.list("id,duration_min")) { filter { eq("id", id) } }
                    .decodeList<DurationRow>().firstOrNull()?.durationMin
            }.getOrNull()
        } ?: event.plannedDurationMin
        return DebriefTarget(
            sid, event.id, event.name, event.date, event.sport.sessionTypes.first(), duration,
            scheduleFor(sid, event.id), existingReview(sid, event.id),
        )
    }

    @Serializable
    private data class DurationRow(val id: Long, @SerialName("duration_min") val durationMin: Double? = null)

    /** Saves the debrief; returns its outcome for the tolerance suggestion. */
    suspend fun save(target: DebriefTarget, blocks: List<FuelBlock>, actuals: List<BlockActual>, answers: DebriefAnswers): FuelReviewOutcome {
        val sessionId = target.sessionId
        val adherence = scheduleAdherence(blocks, actuals)
        if (sessionId != null && blocks.isNotEmpty()) {
            val rows = debriefRows(sessionId, blocks, actuals)
            val keys = rows.map { it.clientKey }.toSet()
            // What an earlier save of this debrief wrote and this one no longer does (fewer intakes, a removed extra), and the
            // intake rows the retired plan-step review wrote for this event, go; the new rows replace them.
            val prefixes = listOfNotNull(debriefKeyPrefix(sessionId), target.eventId?.let { "plan:$it:" })
            val stale = prefixes.flatMap { prefix ->
                supabase.postgrest.from("fueling_intake_log")
                    .select(Columns.list("client_key")) { filter { eq("exercise_session_id", sessionId); like("client_key", "$prefix%") } }
                    .decodeList<KeyRow>().map { it.clientKey }
            }.filter { it !in keys }
            if (stale.isNotEmpty()) {
                supabase.postgrest.from("fueling_intake_log").delete { filter { eq("exercise_session_id", sessionId); isIn("client_key", stale) } }
            }
            supabase.postgrest.from("fueling_intake_log").upsert(
                rows.map { r ->
                    NewFuelingIntake(
                        exerciseSessionId = sessionId, description = r.description.take(300), status = r.status, elapsedSeconds = r.elapsedSeconds,
                        quantity = r.quantity, carbsG = r.carbsG, fluidMl = r.fluidMl, sodiumMg = r.sodiumMg, caffeineMg = r.caffeineMg,
                        source = "fuel_debrief", clientKey = r.clientKey,
                    )
                },
            ) { onConflict = "user_id,client_key" }
        }
        val carbs = answers.manualCarbsG ?: adherence.takenCarbsG
        val fluid = answers.manualFluidMl ?: adherence.takenFluidMl
        val onConflict = if (target.eventId != null) "user_id,event_id" else "user_id,exercise_session_id"
        supabase.postgrest.from("fuel_plan_reviews").upsert(
            FuelReviewRow(
                eventId = target.eventId, exerciseSessionId = sessionId, reviewedOn = LocalDate.now().toString(), durationMin = answers.durationMin,
                carbsG = carbs, fluidMl = fluid, caffeineMg = adherence.takenCaffeineMg, giScore = answers.giScore, energyScore = answers.energyScore,
                weightBeforeKg = answers.weightBeforeKg, weightAfterKg = answers.weightAfterKg, notes = answers.notes?.trim()?.ifBlank { null },
                symptoms = answers.symptoms.map { it.key }, fatigueScore = answers.fatigueScore, thirstScore = answers.thirstScore,
                scheduleHeld = adherence.carbShare, scheduleId = target.schedule?.id,
            ),
        ) { this.onConflict = onConflict }
        if (answers.weightBeforeKg != null && answers.weightAfterKg != null && answers.durationMin >= 30) {
            runCatching {
                SweatTestsRepository(supabase).add(
                    SweatTest(
                        target.date, target.type.let { if (it in setOf("run", "ride", "hike", "swim")) it else "other" },
                        Intensity.RACE, answers.durationMin, answers.weightBeforeKg, answers.weightAfterKg, fluid,
                    ),
                    sessionId, "From the fuel debrief of ${target.title}",
                )
            }
        }
        return FuelReviewOutcome(
            target.date, if (answers.durationMin > 0) carbs / (answers.durationMin / 60) else 0.0,
            effectiveGutScore(answers.giScore, answers.symptoms), answers.energyScore,
        )
    }
}
