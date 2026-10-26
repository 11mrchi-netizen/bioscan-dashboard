package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.aging.chronologicalAgeYears
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

// What the nutrition-goal setting needs to estimate maintenance (domain/NutritionTargets.kt): sex and age from the
// profile, the latest weight and height, and the training minutes of the last four weeks (walks left out: day-to-day
// walking is part of the baseline the activity factor scales).
data class MaintenanceInputs(
    val sex: String? = null,
    val ageYears: Int? = null,
    val heightCm: Double? = null,
    val weightKg: Double? = null,
    val weightDate: LocalDate? = null,
    val weeklyTrainingMin: Double? = null,
)

@Serializable
private data class WeightRow(val date: String, @SerialName("weight_kg") val weightKg: Double? = null)

@Serializable
private data class HeightOnlyRow(@SerialName("height_cm") val heightCm: Double? = null)

@Serializable
private data class TrainingMinutesRow(val type: String? = null, @SerialName("duration_min") val durationMin: Double? = null)

const val TRAINING_WINDOW_DAYS = 28L

class NutritionTargetsRepository(private val supabase: SupabaseClient) {

    suspend fun loadMaintenanceInputs(today: LocalDate = LocalDate.now()): MaintenanceInputs {
        val profile = runCatching { UserProfileRepository(supabase).loadProfile() }.getOrNull()
        val age = profile?.dateOfBirth?.let { runCatching { chronologicalAgeYears(LocalDate.parse(it), today) }.getOrNull() }
        val weight = supabase.postgrest.from("body_metrics")
            .select(Columns.list("date,weight_kg")) {
                filter { filterNot("weight_kg", FilterOperator.IS, "null") }
                order("date", Order.DESCENDING)
                limit(1)
            }
            .decodeList<WeightRow>().firstOrNull()
        val height = supabase.postgrest.from("body_metrics")
            .select(Columns.list("height_cm")) {
                filter { filterNot("height_cm", FilterOperator.IS, "null") }
                order("date", Order.DESCENDING)
                limit(1)
            }
            .decodeList<HeightOnlyRow>().firstOrNull()?.heightCm
        val sessions = supabase.postgrest.from("exercise_sessions")
            .select(Columns.list("type,duration_min")) {
                filter {
                    gte("start_time", today.minusDays(TRAINING_WINDOW_DAYS).toString())
                    gt("duration_min", 0)
                    neq("type", "walk")
                }
            }
            .decodeList<TrainingMinutesRow>()
        return MaintenanceInputs(
            sex = profile?.sex,
            ageYears = age,
            heightCm = height,
            weightKg = weight?.weightKg,
            weightDate = weight?.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            weeklyTrainingMin = sessions.sumOf { it.durationMin ?: 0.0 } / (TRAINING_WINDOW_DAYS / 7.0),
        )
    }
}
