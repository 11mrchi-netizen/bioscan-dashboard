package com.bioscan.fieldterminal.domain.fuel

import java.time.ZonedDateTime
import kotlin.math.roundToInt

// Scoring system (docs/scoring-system/02-page-mockups.md §1): logging a planned meal straight from TODAY'S PLAN.
//
// A meal's choices are what the plan already puts on it: the prepped breakfast or container when a prep week is set up,
// else the example plates. LOG THIS writes the chosen plate's macros as one meal. Its time is now when now falls in that
// meal's slot (mealForLog matches logs to the nearest meal within two hours), else the planned time, so the meal always
// lands in the slot it was planned for (logging lunch at 16:00 would otherwise count as the snack).

data class PlateChoice(val heading: String, val plate: Plate)

fun plateChoices(meal: CodedMeal, suggestion: MealSuggestion?, prep: PreppedDay?): List<PlateChoice> {
    val prepped = when (meal.kind) {
        MealSlotKind.BREAKFAST -> prep?.breakfast?.let { listOf(PlateChoice("YOUR BREAKFAST", it)) }
        MealSlotKind.LUNCH -> prep?.lunch?.let { listOf(PlateChoice("CONTAINER ${it.letter}", it.plate)) }
        MealSlotKind.DINNER -> prep?.dinner?.let { listOf(PlateChoice("CONTAINER ${it.letter}", it.plate)) }
        else -> null
    }
    if (prepped != null) return prepped
    return suggestion?.plates.orEmpty().mapIndexed { i, plate -> PlateChoice(if (i == 0) "ON THE PLATE" else "OR", plate) }
}

/** When a planned meal logged now is recorded: now if now matches that meal's slot, else its planned time. */
fun logTimeFor(meal: CodedMeal, dayMeals: List<CodedMeal>, now: ZonedDateTime): ZonedDateTime =
    if (mealForLog(dayMeals, now) == meal) now else meal.time

/** The meal's description in the log: "Lunch: 180 g cooked brown rice · 150 g chicken breast". */
fun plannedMealDescription(meal: CodedMeal, plate: Plate): String =
    "${meal.kind.label.lowercase().replaceFirstChar { it.uppercase() }}: ${plate.label}"

/** The meal's targets in one line: "Carbs 60-75 g · protein 26-35 g · fat ~15 g · ~610 kcal". */
fun mealTargetLine(meal: CodedMeal): String {
    fun g(v: Double) = (v / 5).roundToInt() * 5
    return listOfNotNull(
        "Carbs ${g(meal.carbLowG)}-${g(meal.carbHighG)} g",
        "protein ${g(meal.proteinLowG)}-${g(meal.proteinHighG)} g",
        meal.fatG?.let { "fat ~${it.roundToInt()} g" },
        meal.kcal?.let { "~${it.roundToInt()} kcal" },
    ).joinToString(" · ")
}
