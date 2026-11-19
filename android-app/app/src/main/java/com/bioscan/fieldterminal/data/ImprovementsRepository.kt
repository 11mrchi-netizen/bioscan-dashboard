package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.risk.IMPROVEMENT_EVALUATE_DAYS
import com.bioscan.fieldterminal.domain.risk.IMPROVEMENT_SUBJECT
import com.bioscan.fieldterminal.domain.risk.StartedImprovement
import com.bioscan.fieldterminal.domain.risk.improvementKey
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.UUID

// Risk profile R5: a way to improve (air summary, analysis-core/airExposure.ts improvements()) started as a Lab
// intervention: domain lifestyle, subject_type risk_improvement, subject_key "air:<improvement id>", its `lowers` as the
// intended outcomes, evaluated 4 weeks after it starts. It then shows on Lab with every other intervention, and Me ›
// EXPOSURES reads it back against the air days.

@Serializable
private data class NewImprovementIntervention(
    val id: String,
    val domain: String,
    @SerialName("subject_type") val subjectType: String,
    @SerialName("subject_key") val subjectKey: String,
    val name: String,
    val mechanism: String,
    @SerialName("intended_outcomes") val intendedOutcomes: List<String>,
    @SerialName("lifecycle_state") val lifecycleState: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("evaluate_on") val evaluateOn: String,
    val source: String,
)

@Serializable
private data class ImprovementInterventionRow(
    val id: String,
    @SerialName("subject_key") val subjectKey: String,
    val name: String,
    @SerialName("lifecycle_state") val lifecycleState: String,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("evaluate_on") val evaluateOn: String? = null,
)

class ImprovementsRepository(private val supabase: SupabaseClient) {

    suspend fun load(): List<StartedImprovement> = catching {
        supabase.postgrest.from("interventions").select(Columns.list("id,subject_key,name,lifecycle_state,started_at,evaluate_on")) {
            filter { eq("subject_type", IMPROVEMENT_SUBJECT) }
        }.decodeList<ImprovementInterventionRow>()
    }.getOrElse { emptyList() }.map { r ->
        StartedImprovement(
            interventionId = r.id, improvementId = r.subjectKey.removePrefix("air:"), name = r.name, state = r.lifecycleState,
            started = r.startedAt?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            evaluateOn = r.evaluateOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        )
    }

    suspend fun start(improvement: AirImprovement, today: LocalDate = LocalDate.now()) {
        supabase.postgrest.from("interventions").insert(
            NewImprovementIntervention(
                id = UUID.randomUUID().toString(), domain = "lifestyle", subjectType = IMPROVEMENT_SUBJECT,
                subjectKey = improvementKey(improvement.id), name = improvement.title.take(80), mechanism = improvement.why,
                intendedOutcomes = improvement.lowers, lifecycleState = "active", startedAt = today.toString(),
                evaluateOn = today.plusDays(IMPROVEMENT_EVALUATE_DAYS).toString(), source = "risk_profile",
            ),
        )
    }

    suspend fun stop(interventionId: String, today: LocalDate = LocalDate.now()) {
        supabase.postgrest.from("interventions").update(mapOf("lifecycle_state" to "stopped", "ended_at" to today.toString())) {
            filter { eq("id", interventionId) }
        }
    }
}
