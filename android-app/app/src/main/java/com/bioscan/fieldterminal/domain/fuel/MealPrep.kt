package com.bioscan.fieldterminal.domain.fuel

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil
import kotlin.math.roundToInt

// Sunday meal prep (fuel planning phase 11). The person picks up to five proteins, vegetables and carbohydrates; four
// recipes are built from them (A and B for lunch, C and D for dinner); Monday to Friday get A-B-A-B-A at lunch and
// C-D-C-D-C at dinner, less the meals marked as eaten out. Each container is portioned for its own day's lunch or dinner
// targets from the day engine, so the container before a long run carries more starch than a deload day's. The
// shopping list adds the containers and a week of the person's fixed breakfast, converted from cooked to raw weights by
// USDA cooking yields and rounded to what is bought. Once cooked, the containers are fixed: breakfast (within its
// bounds) and the snack take up any change in the plan. Deterministic throughout; no model is involved.

enum class PrepSlot(val kind: MealSlotKind, val label: String) { LUNCH(MealSlotKind.LUNCH, "LUNCH"), DINNER(MealSlotKind.DINNER, "DINNER") }

data class PrepPicks(val proteins: List<String>, val vegetables: List<String>, val carbs: List<String>) {
    val ready: Boolean get() = proteins.isNotEmpty() && vegetables.isNotEmpty() && carbs.isNotEmpty()
}

const val MAX_PICKS_PER_GROUP = 5
const val PREP_DAYS = 5
const val PREP_VEG_G = 150.0

/** A prep recipe: its letter, the meal it is for, and its foods (ids); oil is added to reach the fat target. */
data class Recipe(
    val letter: Char,
    val slot: PrepSlot,
    val proteins: List<String>,
    val carbs: List<String>,
    val vegetables: List<String>,
    val oil: Boolean = true,
)

/** A food can go into lunch and dinner prep: a main-meal food that is not sweet. */
fun preppable(f: FoodOption): Boolean =
    (MealSlotKind.LUNCH in f.slots || MealSlotKind.DINNER in f.slots) && f.taste != Taste.SWEET && f.serving == null

/** The foods offered in each group of the PICK step, the person's usual ones first. */
fun prepChoices(foods: List<FoodOption>): Triple<List<FoodOption>, List<FoodOption>, List<FoodOption>> {
    val ok = foods.filter(::preppable).sortedWith(compareByDescending<FoodOption> { (if (it.yours) 1000 else 0) + it.totalUses }.thenBy { it.id })
    return Triple(
        ok.filter { it.role == FoodRole.PROTEIN || it.role == FoodRole.MIXED },
        ok.filter { it.role == FoodRole.VEG },
        ok.filter { it.role == FoodRole.CARB || it.role == FoodRole.MIXED },
    )
}

/**
 * Four recipes from [picks]: one protein, one carbohydrate and one vegetable each, rotated so lunch and dinner of the
 * same day differ where the picks allow, every pick used at least once (a fifth protein or carbohydrate joins a recipe
 * as a second one; vegetables beyond four as a second vegetable).
 */
fun buildRecipes(picks: PrepPicks): List<Recipe> {
    // Recipe order A, B, C, D; Monday is A + C, Tuesday B + D.
    // Proteins differ between the lunch and dinner of a day; carbohydrates rotate on another pattern, so with two of
    // each the four recipes still pair them four different ways.
    fun rotate(items: List<String>, crossed: Boolean = false): List<MutableList<String>> {
        val n = items.size
        val idx = when (n) {
            0 -> return List(4) { mutableListOf() }
            1 -> listOf(0, 0, 0, 0)
            2 -> if (crossed) listOf(0, 1, 0, 1) else listOf(0, 1, 1, 0)
            3 -> if (crossed) listOf(0, 1, 2, 1) else listOf(0, 1, 2, 0)
            else -> if (crossed) listOf(0, 1, 3, 2) else listOf(0, 1, 2, 3)
        }
        val out = idx.map { mutableListOf(items[it]) }
        items.drop(4).forEachIndexed { i, extra -> out[i % 4] += extra }
        return out
    }
    val p = rotate(picks.proteins.take(MAX_PICKS_PER_GROUP))
    val c = rotate(picks.carbs.take(MAX_PICKS_PER_GROUP), crossed = true)
    val v = rotate(picks.vegetables.take(MAX_PICKS_PER_GROUP))
    return listOf('A', 'B', 'C', 'D').mapIndexed { i, letter ->
        Recipe(letter, if (i < 2) PrepSlot.LUNCH else PrepSlot.DINNER, p[i], c[i], v[i])
    }
}

/** The Monday of the week being prepped: the coming one (from Sunday, tomorrow). */
fun upcomingPrepWeek(today: LocalDate): LocalDate = today.plusDays(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))

/** The Monday of the week [today] is in (Monday to Sunday). */
fun currentPrepWeek(today: LocalDate): LocalDate = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

/** "2026-11-06:DINNER": how a skipped meal is kept. */
fun slotKey(date: LocalDate, slot: PrepSlot): String = "$date:${slot.name}"

/** The recipe letter a weekday's lunch or dinner gets: A-B-A-B-A and C-D-C-D-C from Monday. */
fun letterFor(weekStart: LocalDate, date: LocalDate, slot: PrepSlot): Char? {
    val d = java.time.temporal.ChronoUnit.DAYS.between(weekStart, date).toInt()
    if (d !in 0 until PREP_DAYS) return null
    return when (slot) {
        PrepSlot.LUNCH -> if (d % 2 == 0) 'A' else 'B'
        PrepSlot.DINNER -> if (d % 2 == 0) 'C' else 'D'
    }
}

data class Container(val date: LocalDate, val slot: PrepSlot, val letter: Char, val plate: Plate, val target: PlateTarget)

/** A cooked container as stored: grams per food id. */
data class StoredContainer(val date: LocalDate, val slot: PrepSlot, val letter: Char, val grams: Map<String, Double>)

private fun oilFor(foods: Map<String, FoodOption>): FoodOption? =
    foods["staple:olive_oil"]?.copy(minG = 0.0, maxG = 20.0, typicalG = 5.0, stepG = 5.0, serving = null)

/**
 * The containers of the week: one per weekday lunch and dinner not in [skipped], portioned by [solvePortions] for
 * [mealFor] (the day's coded lunch or dinner). Vegetables are a fixed [PREP_VEG_G] g, split between a recipe's
 * vegetables; the starch, protein and oil are solved.
 */
fun planContainers(
    weekStart: LocalDate,
    recipes: List<Recipe>,
    skipped: Set<String>,
    foods: Map<String, FoodOption>,
    mealFor: (LocalDate, PrepSlot) -> CodedMeal?,
): List<Container> {
    val byLetter = recipes.associateBy { it.letter }
    val out = mutableListOf<Container>()
    for (d in 0 until PREP_DAYS) {
        val date = weekStart.plusDays(d.toLong())
        for (slot in PrepSlot.entries) {
            if (slotKey(date, slot) in skipped) continue
            val recipe = byLetter[letterFor(weekStart, date, slot)] ?: continue
            val meal = mealFor(date, slot) ?: continue
            val target = meal.plateTarget()
            val veg = recipe.vegetables.mapNotNull { foods[it] }.let { v -> v.map { Portion(it, PREP_VEG_G / v.size) } }
            val vars = (recipe.carbs + recipe.proteins).distinct().mapNotNull { foods[it] } +
                listOfNotNull(if (recipe.oil && target.fatG != null) oilFor(foods) else null)
            val plate = solvePortions(target, vars, veg)
            out += Container(date, slot, recipe.letter, plate.copy(portions = plate.portions.filter { it.grams > 0.0 }), target)
        }
    }
    return out
}

/** A stored (cooked) container read back as a plate against the day's target. */
fun containerFromStored(s: StoredContainer, foods: Map<String, FoodOption>, target: PlateTarget): Container {
    val portions = s.grams.mapNotNull { (id, g) -> foods[id]?.let { Portion(it, g) } }.sortedBy { it.food.id }
    return Container(s.date, s.slot, s.letter, Plate(portions, emptyList()), target)
}

// ---- Breakfast ------------------------------------------------------------------------------------------------------

/** One food of the person's fixed breakfast and its usual amount. */
data class BreakfastItem(val foodId: String, val grams: Double)

const val BREAKFAST_MIN_SCALE = 0.5
const val BREAKFAST_MAX_SCALE = 1.75

/**
 * The fixed breakfast for one day's [target]: the same foods, each between half and 1.75 times its usual amount, solved
 * to come closest to the target (mostly by moving the carbohydrate foods). Null without a template.
 */
fun scaleBreakfast(template: List<BreakfastItem>, target: PlateTarget, foods: Map<String, FoodOption>): Plate? {
    val items = template.mapNotNull { i ->
        foods[i.foodId]?.copy(typicalG = i.grams, minG = i.grams * BREAKFAST_MIN_SCALE, maxG = i.grams * BREAKFAST_MAX_SCALE)
    }
    if (items.isEmpty()) return null
    return solvePortions(target, items)
}

// ---- A prepped day --------------------------------------------------------------------------------------------------

data class Remainder(val carbG: Double, val proteinG: Double, val fatG: Double?)

data class PreppedDay(
    val date: LocalDate,
    val breakfast: Plate?,
    val lunch: Container?,
    val dinner: Container?,
    val skipped: Set<PrepSlot>,
    /** What the snacks (and pre-session or recovery snacks) are left with after breakfast and the containers. */
    val snack: Remainder,
    val notes: List<String>,
)

private fun PlateTarget.plus(dc: Double, dp: Double, df: Double): PlateTarget =
    copy(carbLowG = (carbLowG + dc).coerceAtLeast(0.0), carbHighG = (carbHighG + dc).coerceAtLeast(0.0), proteinG = (proteinG + dp).coerceAtLeast(0.0), fatG = fatG?.let { (it + df).coerceAtLeast(0.0) })

/**
 * A day of the prep week: breakfast scaled to its target plus whatever the containers miss (fixed once cooked), and the
 * snack left with the rest of the day's meals. Skipped meals keep their targets out of both sides (eaten out).
 */
fun prepDay(day: CodedDay, template: List<BreakfastItem>, containers: List<Container>, skipped: Set<PrepSlot>, foods: Map<String, FoodOption>): PreppedDay {
    val lunch = containers.firstOrNull { it.date == day.date && it.slot == PrepSlot.LUNCH }
    val dinner = containers.firstOrNull { it.date == day.date && it.slot == PrepSlot.DINNER }
    val fixed = listOfNotNull(lunch, dinner)
    val dc = fixed.sumOf { it.target.carbMidG - it.plate.carbG }
    val dp = fixed.sumOf { it.target.proteinG - it.plate.proteinG }
    val df = fixed.sumOf { (it.target.fatG ?: 0.0) - it.plate.fatG }
    val notes = mutableListOf<String>()
    val bMeal = day.meals.firstOrNull { it.kind == MealSlotKind.BREAKFAST }
    val breakfast = bMeal?.let { scaleBreakfast(template, it.plateTarget().plus(dc, dp, df), foods) }
    // What breakfast could not take up goes to the snacks (all of it when there is no breakfast template).
    val (rc, rp, rf) = if (breakfast != null && bMeal != null) {
        Triple(bMeal.carbMidG + dc - breakfast.carbG, bMeal.proteinG + dp - breakfast.proteinG, (bMeal.fatG ?: 0.0) + df - breakfast.fatG)
    } else {
        Triple(dc, dp, df)
    }
    val small = day.meals.filter { !it.kind.main }
    val hasFat = day.meals.any { it.fatG != null }
    val snack = Remainder(
        carbG = (small.sumOf { it.carbMidG } + rc).coerceAtLeast(0.0),
        proteinG = (small.sumOf { it.proteinG } + rp).coerceAtLeast(0.0),
        fatG = if (hasFat) (small.sumOf { it.fatG ?: 0.0 } + rf).coerceAtLeast(0.0) else null,
    )
    if (fixed.isNotEmpty() && kotlin.math.abs(dc) >= 15) {
        val word = if (dc > 0) "short of" else "over"
        notes += "The containers are ${kotlin.math.abs(dc).roundToInt()} g of carbohydrate $word today's plan: breakfast and the snack take it up."
    }
    return PreppedDay(day.date, breakfast, lunch, dinner, skipped, snack, notes)
}

// ---- Shopping and cooking -------------------------------------------------------------------------------------------

// Raw (or dry) weight per cooked weight, rounded from USDA cooking yields; foods not listed are bought as eaten.
private val RAW_PER_COOKED = mapOf(
    "staple:rice_white" to 0.36, "staple:rice_brown" to 0.40, "staple:pasta" to 0.45, "staple:noodles_wheat" to 0.50,
    "staple:noodles_rice" to 0.40, "staple:couscous" to 0.40, "staple:sweet_potato" to 1.20, "staple:lentils" to 0.40,
    "staple:chickpeas" to 0.45, "staple:beans" to 0.45, "staple:chicken_breast" to 1.35, "staple:beef_lean" to 1.35,
    "staple:pork_loin" to 1.35, "staple:turkey" to 1.35, "staple:salmon" to 1.25, "staple:white_fish" to 1.25,
    "staple:shrimp" to 1.30, "staple:greens" to 1.20, "staple:broccoli" to 1.10, "staple:mixed_veg" to 1.15,
)
private val RAW_WORD = mapOf(
    "staple:rice_white" to "white rice, dry", "staple:rice_brown" to "brown rice, dry", "staple:pasta" to "pasta, dry",
    "staple:noodles_wheat" to "wheat noodles, dry", "staple:noodles_rice" to "rice noodles, dry", "staple:couscous" to "couscous, dry",
    "staple:lentils" to "lentils, dry", "staple:chickpeas" to "chickpeas, dry", "staple:beans" to "beans, dry",
    "staple:chicken_breast" to "chicken breast, raw", "staple:beef_lean" to "lean beef, raw", "staple:pork_loin" to "pork loin, raw",
    "staple:turkey" to "turkey breast, raw", "staple:salmon" to "salmon, raw", "staple:white_fish" to "white fish, raw",
    "staple:shrimp" to "shrimp, raw", "staple:sweet_potato" to "sweet potato, raw", "staple:greens" to "leafy greens, raw",
    "staple:broccoli" to "broccoli, raw", "staple:mixed_veg" to "mixed vegetables, raw",
)

fun rawPerCooked(foodId: String): Double = RAW_PER_COOKED[foodId] ?: 1.0

data class ShoppingItem(val food: FoodOption, val eatenG: Double, val rawG: Double, val label: String)

private const val TBSP_OIL_G = 13.5

/** What to buy: [plates] added up per food, converted to raw weight and rounded up to what is bought. */
fun shoppingList(plates: List<Plate>): List<ShoppingItem> {
    val byFood = plates.flatMap { it.portions }.filter { it.grams > 0 }.groupBy { it.food.id }
    return byFood.map { (id, ps) ->
        val food = ps.first().food
        val eaten = ps.sumOf { it.grams }
        val raw = eaten * rawPerCooked(id)
        val s = food.serving
        val label = when {
            id == "staple:olive_oil" -> "${ceil(raw / TBSP_OIL_G).toInt()} tbsp olive oil"
            s != null -> {
                val n = ceil(raw / s.grams - 1e-6).toInt()
                "$n ${if (n == 1) s.singular else s.plural}"
            }
            else -> {
                val rounded = (ceil(raw / 50.0) * 50).toInt()
                val name = RAW_WORD[id] ?: food.name
                if (rounded >= 1000) "%.2f kg %s".format(rounded / 1000.0, name).replace(".00 kg", " kg") else "$rounded ${food.unit} $name"
            }
        }
        ShoppingItem(food, eaten, raw, label)
    }.sortedWith(compareBy<ShoppingItem> { it.food.role.ordinal }.thenBy { it.food.name })
}

data class CookStep(val recipe: Recipe, val food: FoodOption, val rawG: Double, val cookedG: Double, val split: List<Pair<Container, Double>>)

/** Per recipe and food: how much to cook (raw and cooked) and how to split it across that recipe's containers. */
fun cookSheet(recipes: List<Recipe>, containers: List<Container>): List<CookStep> = recipes.flatMap { r ->
    val mine = containers.filter { it.letter == r.letter }
    val foods = mine.flatMap { c -> c.plate.portions.map { it.food } }.distinctBy { it.id }
    foods.map { f ->
        val split = mine.map { c -> c to (c.plate.portions.firstOrNull { it.food.id == f.id }?.grams ?: 0.0) }
        val cooked = split.sumOf { it.second }
        CookStep(r, f, cooked * rawPerCooked(f.id), cooked, split)
    }.filter { it.cookedG > 0 }
}

/** The recipe's name from its foods: "chicken breast · cooked brown rice · broccoli". */
fun recipeName(r: Recipe, foods: Map<String, FoodOption>): String =
    (r.proteins + r.carbs + r.vegetables).mapNotNull { foods[it]?.name }.joinToString(" · ")

/** The shopping list and cook sheet as plain text, for SHARE. */
fun prepText(weekStart: LocalDate, recipes: List<Recipe>, containers: List<Container>, shopping: List<ShoppingItem>, foods: Map<String, FoodOption>): String {
    val sb = StringBuilder()
    sb.appendLine("MEAL PREP · WEEK OF $weekStart")
    sb.appendLine()
    sb.appendLine("SHOPPING")
    shopping.forEach { sb.appendLine("- ${it.label}") }
    sb.appendLine()
    sb.appendLine("RECIPES")
    recipes.forEach { sb.appendLine("${it.letter} (${it.slot.label.lowercase()}): ${recipeName(it, foods)}") }
    sb.appendLine()
    sb.appendLine("COOK")
    cookSheet(recipes, containers).forEach { s ->
        val raw = if (rawPerCooked(s.food.id) != 1.0) "${s.rawG.roundToInt()} g ${RAW_WORD[s.food.id] ?: s.food.name} → " else ""
        sb.appendLine("${s.recipe.letter}: $raw${s.cookedG.roundToInt()} g ${s.food.name}, split ${s.split.joinToString(" / ") { it.second.roundToInt().toString() }}")
    }
    sb.appendLine()
    sb.appendLine("CONTAINERS")
    containers.forEach { c ->
        sb.appendLine("${c.date.dayOfWeek.name.take(3)} ${c.slot.label} (${c.letter}): ${c.plate.label}")
    }
    return sb.toString().trimEnd()
}
