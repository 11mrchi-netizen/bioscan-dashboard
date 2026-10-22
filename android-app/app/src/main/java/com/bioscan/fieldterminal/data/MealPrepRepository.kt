package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.fuel.BreakfastItem
import com.bioscan.fieldterminal.domain.fuel.PrepPicks
import com.bioscan.fieldterminal.domain.fuel.PrepSlot
import com.bioscan.fieldterminal.domain.fuel.Recipe
import com.bioscan.fieldterminal.domain.fuel.StoredContainer
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

// Meal prep storage (fuel planning phase 11; supabase/migrations/20261029_meal_prep.sql): one row per prep week
// (picks, recipes, meals eaten out, the containers once cooked) and the fixed breakfast. The numbers are worked out by
// domain/fuel/MealPrep.kt each time; only the person's choices and the cooked grams are stored.

data class MealPrepWeek(
    val weekStart: LocalDate,
    val picks: PrepPicks,
    val recipes: List<Recipe>,
    val skipped: Set<String>,
    val containers: List<StoredContainer>?,
) {
    val cooked: Boolean get() = containers != null
}

@Serializable
private data class PicksJson(val proteins: List<String> = emptyList(), val vegetables: List<String> = emptyList(), val carbs: List<String> = emptyList())

@Serializable
private data class RecipeJson(
    val letter: String,
    val slot: String,
    val proteins: List<String> = emptyList(),
    val carbs: List<String> = emptyList(),
    val vegetables: List<String> = emptyList(),
    val oil: Boolean = true,
)

@Serializable
private data class ContainerJson(val date: String, val slot: String, val letter: String, val grams: Map<String, Double>)

@Serializable
private data class PrepWeekRow(
    @SerialName("week_start") val weekStart: String,
    val picks: PicksJson = PicksJson(),
    val recipes: List<RecipeJson> = emptyList(),
    val skipped: List<String> = emptyList(),
    val containers: List<ContainerJson>? = null,
    val status: String = "planned",
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
private data class BreakfastItemJson(@SerialName("food_id") val foodId: String, val grams: Double)

@Serializable
private data class BreakfastRow(val items: List<BreakfastItemJson>, @SerialName("updated_at") val updatedAt: String? = null)

private fun PrepWeekRow.toDomain(): MealPrepWeek? {
    val start = runCatching { LocalDate.parse(weekStart) }.getOrNull() ?: return null
    return MealPrepWeek(
        start,
        PrepPicks(picks.proteins, picks.vegetables, picks.carbs),
        recipes.mapNotNull { r ->
            val slot = PrepSlot.entries.firstOrNull { it.name == r.slot } ?: return@mapNotNull null
            Recipe(r.letter.firstOrNull() ?: return@mapNotNull null, slot, r.proteins, r.carbs, r.vegetables, r.oil)
        },
        skipped.toSet(),
        if (status == "cooked") containers.orEmpty().mapNotNull { c ->
            val date = runCatching { LocalDate.parse(c.date) }.getOrNull() ?: return@mapNotNull null
            val slot = PrepSlot.entries.firstOrNull { it.name == c.slot } ?: return@mapNotNull null
            StoredContainer(date, slot, c.letter.firstOrNull() ?: return@mapNotNull null, c.grams)
        } else null,
    )
}

private fun MealPrepWeek.toRow() = PrepWeekRow(
    weekStart = weekStart.toString(),
    picks = PicksJson(picks.proteins, picks.vegetables, picks.carbs),
    recipes = recipes.map { RecipeJson(it.letter.toString(), it.slot.name, it.proteins, it.carbs, it.vegetables, it.oil) },
    skipped = skipped.sorted(),
    containers = containers?.map { ContainerJson(it.date.toString(), it.slot.name, it.letter.toString(), it.grams) },
    status = if (cooked) "cooked" else "planned",
    updatedAt = java.time.OffsetDateTime.now().toString(),
)

class MealPrepRepository(private val supabase: SupabaseClient) {

    /** The prep weeks starting on [weekStarts]; missing weeks are left out. */
    suspend fun loadWeeks(weekStarts: List<LocalDate>): List<MealPrepWeek> =
        ReadCache.get("fuel.prep.${weekStarts.joinToString(",")}", tables = setOf(WEEKS)) {
            supabase.postgrest.from(WEEKS)
                .select(Columns.list("week_start,picks,recipes,skipped,containers,status")) { filter { isIn("week_start", weekStarts.map { it.toString() }) } }
                .decodeList<PrepWeekRow>().mapNotNull { it.toDomain() }
        }

    suspend fun loadWeek(weekStart: LocalDate): MealPrepWeek? = loadWeeks(listOf(weekStart)).firstOrNull()

    suspend fun saveWeek(week: MealPrepWeek) {
        supabase.postgrest.from(WEEKS).upsert(week.toRow()) { onConflict = "user_id,week_start" }
    }

    suspend fun loadBreakfast(): List<BreakfastItem> = ReadCache.get("fuel.breakfast", tables = setOf(BREAKFAST)) {
        supabase.postgrest.from(BREAKFAST).select(Columns.list("items")).decodeList<BreakfastRow>()
            .firstOrNull()?.items.orEmpty().map { BreakfastItem(it.foodId, it.grams) }
    }

    suspend fun saveBreakfast(items: List<BreakfastItem>) {
        supabase.postgrest.from(BREAKFAST).upsert(BreakfastRow(items.map { BreakfastItemJson(it.foodId, it.grams) }, java.time.OffsetDateTime.now().toString())) {
            onConflict = "user_id"
        }
    }

    private companion object {
        const val WEEKS = "meal_prep_weeks"
        const val BREAKFAST = "breakfast_templates"
    }
}
