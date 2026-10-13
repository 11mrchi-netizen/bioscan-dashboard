package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.StreamCoverage
import com.bioscan.fieldterminal.domain.captureCoverage
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

// Analysis Layer 3 / R2 (2e). Reads the dates each capture stream has an entry for. Timestamps in this app are local
// wall-clock time labeled UTC, so the date is simply the first ten characters.
@Serializable
private data class WellbeingDateRow(val date: String, val energy: Int? = null, val mood: Int? = null, val stress: Int? = null, val soreness: Int? = null)

@Serializable
private data class DateRow(val date: String)

@Serializable
private data class TakenAtRow(@SerialName("taken_at") val takenAt: String)

@Serializable
private data class AlcoholItemRow(@SerialName("meal_id") val mealId: Long)

@Serializable
private data class MealLoggedRow(@SerialName("logged_at") val loggedAt: String)

class CaptureCoverageRepository(private val supabase: SupabaseClient) {

    suspend fun load(today: LocalDate = LocalDate.now(), windowDays: Int = 28): List<StreamCoverage> {
        val start = today.minusDays((windowDays - 1).toLong()).toString()
        val db = supabase.postgrest

        val wellbeing = db.from("wellbeing_daily")
            .select(columns = Columns.list("date,energy,mood,stress,soreness")) { filter { gte("date", start) } }
            .decodeList<WellbeingDateRow>()
            .filter { listOf(it.energy, it.mood, it.stress, it.soreness).any { v -> v != null } }
            .map { it.date }
        val hydration = db.from("hydration_daily")
            .select(columns = Columns.list("date")) { filter { gte("date", start); gt("ml", 0) } }
            .decodeList<DateRow>().map { it.date }
        val supplements = db.from("supplement_log")
            .select(columns = Columns.list("taken_at")) { filter { gte("taken_at", "${start}T00:00:00Z") } }
            .decodeList<TakenAtRow>().map { it.takenAt }
        val recovery = db.from("recovery_sessions")
            .select(columns = Columns.list("date")) { filter { gte("date", start) } }
            .decodeList<DateRow>().map { it.date }

        // meal_items carries the grams, meals carries the logged day; alcohol rows are few, so two plain queries.
        val alcoholMealIds = db.from("meal_items")
            .select(columns = Columns.list("meal_id")) { filter { gt("alcohol_g", 0) } }
            .decodeList<AlcoholItemRow>().map { it.mealId }.distinct()
        val alcohol = if (alcoholMealIds.isEmpty()) emptyList() else db.from("meals")
            .select(columns = Columns.list("logged_at")) { filter { isIn("id", alcoholMealIds); gte("logged_at", "${start}T00:00:00Z") } }
            .decodeList<MealLoggedRow>().map { it.loggedAt }

        fun dates(raw: List<String>) = raw.mapNotNull { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }.toSet()
        return captureCoverage(
            mapOf(
                "wellbeing" to dates(wellbeing),
                "hydration" to dates(hydration),
                "supplements" to dates(supplements),
                "recovery" to dates(recovery),
                "alcohol" to dates(alcohol),
            ),
            today,
            windowDays,
        )
    }
}
