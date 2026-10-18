package com.bioscan.fieldterminal.domain.fuel

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

// What a meal's targets look like on the plate (fuel planning phase 10). Deterministic: a fixed food table (the
// person's own catalogue foods and the built-in staples, StapleFoods.kt), a template per meal, portions solved by
// least squares on carbohydrate, protein and fat, rounded to real servings, and a fixed score. No model is involved;
// the same inputs always give the same plates.
//
//   main meal     a carbohydrate + a protein (+ a fat) (+ vegetables at lunch and dinner)
//   breakfast     a carbohydrate + a protein (+ a fat)
//   snack         a carbohydrate (+ a protein)
//   pre-session   a carbohydrate (+ a lean protein), low in fat and fibre
//   recovery      a carbohydrate + a protein
//
// The foods the person already eats rank first (logged catalogue foods, then staples their meal descriptions mention);
// the rest are staples. A plate that cannot come within 25 % of a target is not offered.

enum class FoodRole { CARB, PROTEIN, FAT, MIXED, VEG }

/** Savoury and sweet foods are not put on one plate (rice with yoghurt); neutral ones go with either. */
enum class Taste { SAVOURY, SWEET, NEUTRAL }

/** A named portion: "1 banana" = 118 g, counted in steps of [step] (0.5 = halves). */
data class Serving(val singular: String, val plural: String, val grams: Double, val step: Double)

data class FoodOption(
    val id: String,
    val name: String,
    val role: FoodRole,
    val slots: Set<MealSlotKind>,
    val carbPerG: Double,
    val proteinPerG: Double,
    val fatPerG: Double,
    val fibrePerG: Double,
    val kcalPerG: Double,
    val typicalG: Double,
    val minG: Double,
    val maxG: Double,
    val stepG: Double = 10.0,
    val serving: Serving? = null,
    val unit: String = "g",
    val aliases: List<String> = emptyList(),
    val taste: Taste = Taste.NEUTRAL,
    /** True for a food from the person's own logged catalogue foods. */
    val yours: Boolean = false,
    /** How often the person has eaten it, by meal slot (catalogue foods and staples their descriptions mention). */
    val uses: Map<MealSlotKind, Int> = emptyMap(),
) {
    fun usesIn(kind: MealSlotKind): Int = uses[kind] ?: 0
    val totalUses: Int get() = uses.values.sum()
    /** Usual for the person: their own catalogue food, or a staple seen at least twice in their meals. */
    val usual: Boolean get() = yours || totalUses >= 2
    /** Low enough in fat and fibre to eat in the hours before a key session (per 100 g: fat <= 5 g, fibre <= 3 g). */
    val easyBeforeTraining: Boolean get() = fatPerG <= 0.05 && fibrePerG <= 0.03
}

/** The role of a catalogue food from its share of energy: carbohydrate >= 55 %, protein >= 40 %, fat >= 60 %. */
fun roleFor(carbPerG: Double, proteinPerG: Double, fatPerG: Double): FoodRole {
    val c = 4 * carbPerG; val p = 4 * proteinPerG; val f = 9 * fatPerG
    val e = c + p + f
    if (e <= 0) return FoodRole.VEG
    return when {
        e < 0.6 && c / e > 0.4 -> FoodRole.VEG // under 60 kcal per 100 g: vegetables and the like
        c / e >= 0.55 -> FoodRole.CARB
        p / e >= 0.40 -> FoodRole.PROTEIN
        f / e >= 0.60 -> FoodRole.FAT
        else -> FoodRole.MIXED
    }
}

data class Portion(val food: FoodOption, val grams: Double) {
    val carbG: Double get() = food.carbPerG * grams
    val proteinG: Double get() = food.proteinPerG * grams
    val fatG: Double get() = food.fatPerG * grams
    val fibreG: Double get() = food.fibrePerG * grams
    val kcal: Double get() = food.kcalPerG * grams

    /** "1½ bananas (177 g)", "180 g cooked brown rice", "250 ml soy milk". */
    val label: String get() {
        val s = food.serving
        if (s != null) {
            val n = grams / s.grams
            val count = formatCount(n)
            val name = if (abs(n - 1.0) < 1e-6 || n < 1.0) s.singular else s.plural
            return "$count $name (${grams.roundToInt()} ${food.unit})"
        }
        return "${grams.roundToInt()} ${food.unit} ${food.name}"
    }
}

private fun formatCount(n: Double): String {
    val whole = floor(n + 1e-6).toInt()
    val frac = n - whole
    val f = when {
        abs(frac) < 0.05 -> ""
        abs(frac - 0.25) < 0.05 -> "¼"
        abs(frac - 0.5) < 0.05 -> "½"
        abs(frac - 0.75) < 0.05 -> "¾"
        else -> return "%.1f".format(n)
    }
    return if (whole == 0 && f.isNotEmpty()) f else "$whole$f"
}

data class Plate(val portions: List<Portion>, val gaps: List<String>) {
    val carbG: Double get() = portions.sumOf { it.carbG }
    val proteinG: Double get() = portions.sumOf { it.proteinG }
    val fatG: Double get() = portions.sumOf { it.fatG }
    val fibreG: Double get() = portions.sumOf { it.fibreG }
    val kcal: Double get() = portions.sumOf { it.kcal }
    val within: Boolean get() = gaps.isEmpty()
    /** The food giving the plate most of its carbohydrate. */
    val carbSource: FoodOption? get() = portions.maxByOrNull { it.carbG }?.food
    val label: String get() = portions.joinToString(" · ") { it.label }
}

data class MealSuggestion(val plates: List<Plate>, val note: String?)

/** The targets a plate is built for: a coded meal's carbohydrate range, protein and fat. */
data class PlateTarget(
    val kind: MealSlotKind,
    val carbLowG: Double,
    val carbHighG: Double,
    val proteinG: Double,
    val fatG: Double?,
    val beforeKeySession: Boolean,
) {
    val carbMidG: Double get() = (carbLowG + carbHighG) / 2
}

fun CodedMeal.plateTarget() = PlateTarget(kind, carbLowG, carbHighG, proteinG, fatG, beforeKeySession)

private const val CANDIDATES_PER_ROLE = 6
private const val FEASIBLE = 0.25
private const val RIDGE = 0.15
private const val FAT_WEIGHT = 0.6
const val PRE_SESSION_FIBRE_CAP_G = 5.0
const val MAIN_BEFORE_SESSION_FIBRE_CAP_G = 8.0
const val MEAL_FIBRE_SOFT_CAP_G = 15.0
const val EXTRA_CARB_FROM_G = 110.0

private fun ownRank(f: FoodOption, kind: MealSlotKind): Int = (if (f.yours) 1000 else 0) + 10 * f.usesIn(kind) + f.totalUses

private fun candidates(foods: List<FoodOption>, kind: MealSlotKind, roles: Set<FoodRole>, lowFat: Boolean): List<FoodOption> =
    foods.filter { it.role in roles && kind in it.slots && (!lowFat || it.easyBeforeTraining) }
        .sortedWith(compareByDescending<FoodOption> { ownRank(it, kind) }.thenBy { it.id })
        .take(CANDIDATES_PER_ROLE)

/** The food combinations a meal of [t]'s kind is built from, each as (variable foods, fixed vegetables). */
private fun combinations(t: PlateTarget, foods: List<FoodOption>): List<Pair<List<FoodOption>, FoodOption?>> {
    val lowFat = t.beforeKeySession || t.kind == MealSlotKind.PRE_SESSION
    // Lunch and dinner are built on a weighed starch (rice, pasta, potato); bread only comes as a side.
    val mainMeal = t.kind == MealSlotKind.LUNCH || t.kind == MealSlotKind.DINNER
    val carbs = candidates(foods.filter { !mainMeal || it.serving == null }, t.kind, setOf(FoodRole.CARB, FoodRole.MIXED), lowFat)
    val proteins = candidates(foods, t.kind, setOf(FoodRole.PROTEIN, FoodRole.MIXED), lowFat)
    val fats = if (lowFat || t.fatG == null) emptyList() else candidates(foods, t.kind, setOf(FoodRole.FAT), false).take(3)
    val veg = if (t.kind == MealSlotKind.LUNCH || t.kind == MealSlotKind.DINNER) {
        candidates(foods, t.kind, setOf(FoodRole.VEG), lowFat).take(2)
    } else emptyList()
    val needsProtein = when (t.kind) {
        MealSlotKind.SNACK, MealSlotKind.PRE_SESSION -> false
        else -> true
    }
    // A big carbohydrate meal may take a second, neutral carbohydrate (bread, juice): 175 g is more rice than a plate.
    val extras: List<FoodOption?> = if (t.carbMidG > EXTRA_CARB_FROM_G) {
        candidates(foods, t.kind, setOf(FoodRole.CARB), lowFat).filter { it.taste == Taste.NEUTRAL }.take(2) + listOf(null)
    } else listOf(null)
    val out = mutableListOf<Pair<List<FoodOption>, FoodOption?>>()
    val vegOptions: List<FoodOption?> = veg.ifEmpty { listOf(null) }
    for (c in carbs) for (x in extras.filter { it?.id != c.id }) {
        val proteinOptions: List<FoodOption?> = proteins.filter { it.id != c.id } + (if (needsProtein && c.role != FoodRole.MIXED) emptyList() else listOf(null))
        for (p in proteinOptions) {
            val fatOptions: List<FoodOption?> = fats + listOf(null)
            for (f in fatOptions) for (v in vegOptions) {
                val plate = listOfNotNull(c, x, p, f, v)
                if (plate.any { it.taste == Taste.SAVOURY } && plate.any { it.taste == Taste.SWEET }) continue
                out += listOfNotNull(c, x, p, f) to v
            }
        }
    }
    return out
}

/** How far [v] is outside [lo]..[hi], 0 inside. */
private fun outside(v: Double, lo: Double, hi: Double): Double = if (v < lo) lo - v else if (v > hi) v - hi else 0.0

private fun proteinBand(t: PlateTarget) = t.proteinG * 0.85 to t.proteinG * 1.15
private fun fatBand(t: PlateTarget) = t.fatG!!.let { it * 0.75 - 2 to it * 1.25 + 2 }

private fun score(t: PlateTarget, portions: List<Portion>, recent: Set<String>): Double {
    val c = portions.sumOf { it.carbG }; val p = portions.sumOf { it.proteinG }; val f = portions.sumOf { it.fatG }
    var s = (outside(c, t.carbLowG, t.carbHighG) / maxOf(t.carbMidG, 10.0)).let { it * it }
    val (pl, ph) = proteinBand(t)
    s += (outside(p, pl, ph) / maxOf(t.proteinG, 10.0)).let { it * it }
    if (t.fatG != null) {
        val (fl, fh) = fatBand(t)
        s += FAT_WEIGHT * (outside(f, fl, fh) / maxOf(t.fatG, 8.0)).let { it * it }
    }
    if (t.beforeKeySession || t.kind == MealSlotKind.PRE_SESSION) {
        // Before a key session: no more fat than the meal's own target allows, and little fibre (more for a main meal).
        if (f > maxOf(PRE_SESSION_FAT_CAP_G, (t.fatG ?: 0.0) * 1.25 + 2)) s += 1.0
        if (portions.sumOf { it.fibreG } > (if (t.kind.main) MAIN_BEFORE_SESSION_FIBRE_CAP_G else PRE_SESSION_FIBRE_CAP_G)) s += 1.0
    }
    // A meal of beans and lentils can reach 35 g of fibre: kept moderate (about 15 g a meal).
    val fibre = portions.sumOf { it.fibreG }
    if (fibre > MEAL_FIBRE_SOFT_CAP_G) s += ((fibre - MEAL_FIBRE_SOFT_CAP_G) / 30).let { it * it }
    // A second carbohydrate only when one cannot carry the meal.
    if (portions.count { it.food.role == FoodRole.CARB } > 1) s += 0.01
    // Variety and the person's own foods: small nudges, never above a target miss.
    s += 0.02 * portions.count { it.food.id in recent }
    s -= 0.01 * portions.count { it.food.usual } + 0.01 * portions.count { it.food.yours || it.food.usesIn(t.kind) > 0 }
    return s
}

private fun gaps(t: PlateTarget, plate: List<Portion>): List<String> {
    val out = mutableListOf<String>()
    fun gap(name: String, v: Double, lo: Double, hi: Double) {
        if ((lo - v).roundToInt() >= 1) out += "$name ${(lo - v).roundToInt()} G SHORT"
        else if ((v - hi).roundToInt() >= 1) out += "$name ${(v - hi).roundToInt()} G OVER"
    }
    gap("CARBS", plate.sumOf { it.carbG }, t.carbLowG, t.carbHighG)
    if (t.proteinG >= 10) proteinBand(t).let { (lo, hi) -> gap("PROTEIN", plate.sumOf { it.proteinG }, lo, hi) }
    if (t.fatG != null && t.fatG >= 8) fatBand(t).let { (lo, hi) -> gap("FAT", plate.sumOf { it.fatG }, lo, hi) }
    return out
}

private fun feasible(t: PlateTarget, plate: List<Portion>): Boolean {
    if (outside(plate.sumOf { it.carbG }, t.carbLowG, t.carbHighG) > FEASIBLE * t.carbMidG) return false
    if (t.proteinG >= 10) proteinBand(t).let { (lo, hi) -> if (outside(plate.sumOf { it.proteinG }, lo, hi) > FEASIBLE * t.proteinG) return false }
    if (t.fatG != null && t.fatG >= 8) fatBand(t).let { (lo, hi) -> if (outside(plate.sumOf { it.fatG }, lo, hi) > FEASIBLE * t.fatG + 3) return false }
    return true
}

/** Solves A x = b in the least-squares sense for a small system (normal equations, Gaussian elimination). */
private fun leastSquares(rows: List<DoubleArray>, rhs: List<Double>, n: Int): DoubleArray? {
    val m = Array(n) { DoubleArray(n) }
    val v = DoubleArray(n)
    rows.forEachIndexed { r, a ->
        for (i in 0 until n) {
            v[i] += a[i] * rhs[r]
            for (j in 0 until n) m[i][j] += a[i] * a[j]
        }
    }
    for (col in 0 until n) {
        val piv = (col until n).maxByOrNull { abs(m[it][col]) }!!
        if (abs(m[piv][col]) < 1e-12) return null
        if (piv != col) { val t = m[piv]; m[piv] = m[col]; m[col] = t; val tv = v[piv]; v[piv] = v[col]; v[col] = tv }
        for (r in 0 until n) if (r != col) {
            val k = m[r][col] / m[col][col]
            for (c in col until n) m[r][c] -= k * m[col][c]
            v[r] -= k * v[col]
        }
    }
    return DoubleArray(n) { v[it] / m[it][it] }
}

/** Grams for [foods] that come closest to [t] (after the fixed [fixed] portions), within each food's bounds. */
private fun solveGrams(t: PlateTarget, foods: List<FoodOption>, fixed: List<Portion>): DoubleArray {
    val cT = t.carbMidG - fixed.sumOf { it.carbG }
    val pT = t.proteinG - fixed.sumOf { it.proteinG }
    val fT = t.fatG?.let { it - fixed.sumOf { p -> p.fatG } }
    val sc = maxOf(t.carbMidG, 10.0); val sp = maxOf(t.proteinG, 10.0); val sf = maxOf(t.fatG ?: 8.0, 8.0)
    val x = DoubleArray(foods.size) { foods[it].typicalG }
    val free = foods.indices.toMutableList()
    repeat(foods.size + 1) {
        if (free.isEmpty()) return x
        val fixedPart = foods.indices.filter { it !in free }
        val rows = mutableListOf<DoubleArray>(); val rhs = mutableListOf<Double>()
        fun macroRow(per: (FoodOption) -> Double, target: Double, scale: Double, w: Double) {
            val k = kotlin.math.sqrt(w) / scale
            rows += DoubleArray(free.size) { per(foods[free[it]]) * k }
            rhs += (target - fixedPart.sumOf { per(foods[it]) * x[it] }) * k
        }
        macroRow({ it.carbPerG }, cT, sc, 1.0)
        macroRow({ it.proteinPerG }, pT, sp, 1.0)
        if (fT != null) macroRow({ it.fatPerG }, fT, sf, FAT_WEIGHT)
        // A pull towards the usual portion keeps the system well posed and the plates ordinary.
        free.forEachIndexed { i, fi ->
            rows += DoubleArray(free.size) { j -> if (j == i) RIDGE / foods[fi].typicalG else 0.0 }
            rhs += RIDGE
        }
        val sol = leastSquares(rows, rhs, free.size) ?: return x
        free.forEachIndexed { i, fi -> x[fi] = sol[i] }
        // Out of bounds: the furthest goes to its bound and the rest is solved again.
        val worst = free.maxByOrNull { fi -> maxOf(foods[fi].minG - x[fi], x[fi] - foods[fi].maxG) / foods[fi].typicalG }!!
        val over = maxOf(foods[worst].minG - x[worst], x[worst] - foods[worst].maxG)
        if (over <= 1e-9) return x
        x[worst] = x[worst].coerceIn(foods[worst].minG, foods[worst].maxG)
        free -= worst
    }
    return x
}

private fun stepOf(f: FoodOption): Double = f.serving?.let { it.grams * it.step } ?: f.stepG

/** The rounded portions nearest [x], each food at a whole step within its bounds, best scored. */
private fun rounded(t: PlateTarget, foods: List<FoodOption>, x: DoubleArray, fixed: List<Portion>, recent: Set<String>): List<Portion> {
    val options = foods.indices.map { i ->
        val step = stepOf(foods[i])
        val lo = (floor(x[i] / step) * step).coerceIn(foods[i].minG, foods[i].maxG)
        val hi = (ceil(x[i] / step) * step).coerceIn(foods[i].minG, foods[i].maxG)
        listOf(lo, hi).distinct()
    }
    var best: List<Portion>? = null
    var bestScore = Double.MAX_VALUE
    fun walk(i: Int, acc: List<Portion>) {
        if (i == foods.size) {
            val all = acc + fixed
            val s = score(t, all, recent)
            if (s < bestScore - 1e-12) { bestScore = s; best = all }
            return
        }
        for (g in options[i]) walk(i + 1, acc + Portion(foods[i], g))
    }
    walk(0, emptyList())
    return best!!
}

/**
 * Up to [count] plates for [t] from [foods], best first, each with a different main carbohydrate source. [recent] are
 * foods already suggested at the meal before (a small nudge for variety).
 */
fun suggestPlates(t: PlateTarget, foods: List<FoodOption>, recent: Set<String> = emptySet(), count: Int = 2): MealSuggestion {
    if (t.carbMidG < 5 && t.proteinG < 5) return MealSuggestion(emptyList(), null)
    val scored = combinations(t, foods).map { (vars, veg) ->
        val fixed = listOfNotNull(veg?.let { Portion(it, it.typicalG) })
        val x = solveGrams(t, vars, fixed)
        val plate = rounded(t, vars, x, fixed, recent)
        Triple(plate, score(t, plate, recent), plate.joinToString("+") { it.food.id })
    }.filter { feasible(t, it.first) }
        .sortedWith(compareBy<Triple<List<Portion>, Double, String>> { it.second }.thenBy { it.third })
    val chosen = mutableListOf<List<Portion>>()
    for ((plate, _, _) in scored) {
        val src = plate.maxByOrNull { it.carbG }?.food?.id
        if (chosen.none { c -> c.maxByOrNull { it.carbG }?.food?.id == src }) chosen += plate
        if (chosen.size == count) break
    }
    if (chosen.isEmpty()) {
        return MealSuggestion(emptyList(), "No plate from your foods and the staples comes within 25 % of this meal's targets.")
    }
    return MealSuggestion(chosen.map { Plate(it, gaps(t, it)) }, null)
}

/** Plates for every meal of a coded day, each meal nudged away from the foods of the meal before. */
fun suggestDay(day: CodedDay, foods: List<FoodOption>): List<MealSuggestion> {
    var recent = emptySet<String>()
    return day.meals.map { m ->
        val s = suggestPlates(m.plateTarget(), foods, recent)
        recent = s.plates.firstOrNull()?.portions?.map { it.food.id }?.toSet() ?: emptySet()
        s
    }
}

/**
 * The day's carbohydrate in everyday terms: "≈ 950 g cooked white rice + 3 bananas", three quarters from the person's
 * most eaten main-meal carbohydrate (else rice) and a quarter as fruit (bananas).
 */
fun daySummary(carbG: Double, foods: List<FoodOption>): String {
    val main = foods.filter { it.role == FoodRole.CARB && it.serving == null && (MealSlotKind.LUNCH in it.slots || MealSlotKind.DINNER in it.slots) }
        .sortedWith(compareByDescending<FoodOption> { ownRank(it, MealSlotKind.DINNER) + ownRank(it, MealSlotKind.LUNCH) }.thenBy { it.id })
        .firstOrNull() ?: return "${carbG.roundToInt()} g carbs"
    val banana = foods.firstOrNull { it.id == "staple:banana" }
    val fruitG = if (banana != null) carbG * 0.25 else 0.0
    val mainG = ((carbG - fruitG) / main.carbPerG / 50).roundToInt() * 50
    val parts = mutableListOf("${"%,d".format(mainG)} ${main.unit} ${main.name}")
    if (banana != null) {
        val n = (fruitG / (banana.carbPerG * banana.serving!!.grams) * 2).roundToInt() / 2.0
        if (n > 0) parts += "${formatCount(n)} ${if (n <= 1.0) banana.serving.singular else banana.serving.plural}"
    }
    return "≈ " + parts.joinToString(" + ")
}

/**
 * The staples, ranked by the person's meals: [descriptions] are their logged meal and item descriptions with the slot
 * each was eaten in; a staple counts once per description that mentions it.
 */
fun staplesWithUses(descriptions: List<Pair<MealSlotKind, String>>, staples: List<FoodOption> = STAPLE_FOODS): List<FoodOption> {
    val counts = mutableMapOf<String, MutableMap<MealSlotKind, Int>>()
    for ((slot, text) in descriptions) {
        for (id in staplesMentioned(text, staples)) {
            val m = counts.getOrPut(id) { mutableMapOf() }
            m[slot] = (m[slot] ?: 0) + 1
        }
    }
    return staples.map { s -> counts[s.id]?.let { s.copy(uses = it) } ?: s }
}

/** The slot a meal eaten at [time] belongs to: the nearest of the person's meal times within 2 hours, else a snack. */
fun slotForTime(time: java.time.LocalTime, times: MealTimes): MealSlotKind {
    val slots = listOfNotNull(
        MealSlotKind.BREAKFAST to times.breakfast, MealSlotKind.LUNCH to times.lunch,
        times.snack?.let { MealSlotKind.SNACK to it }, MealSlotKind.DINNER to times.dinner,
    )
    val minute = time.toSecondOfDay() / 60
    return slots.map { (k, t) -> k to abs(t.toSecondOfDay() / 60 - minute) }
        .filter { it.second <= 120 }
        .minByOrNull { it.second }?.first ?: MealSlotKind.SNACK
}

/** One logged catalogue food: its slot and amount in grams (or ml). */
data class LoggedFood(val foodId: Long, val slot: MealSlotKind, val grams: Double)

/** Per-100 g values and servings of a catalogue food, as stored. */
data class CatalogueFood(
    val id: Long,
    val name: String,
    val carbsPer100: Double?,
    val proteinPer100: Double?,
    val fatPer100: Double?,
    val fibrePer100: Double?,
    val kcalPer100: Double?,
    val unit: String = "g",
)

const val OWN_FOOD_MIN_USES = 3

/**
 * The person's own foods: catalogue foods logged at least [OWN_FOOD_MIN_USES] times, each with the median amount as its
 * usual portion and the slots it was eaten in. The numbers are the catalogue's, never an estimate's.
 */
fun ownFoods(logged: List<LoggedFood>, catalogue: Map<Long, CatalogueFood>): List<FoodOption> =
    logged.groupBy { it.foodId }.filter { it.value.size >= OWN_FOOD_MIN_USES }.mapNotNull { (id, rows) ->
        val f = catalogue[id] ?: return@mapNotNull null
        val c = f.carbsPer100 ?: return@mapNotNull null
        val p = f.proteinPer100 ?: return@mapNotNull null
        val fat = f.fatPer100 ?: return@mapNotNull null
        val grams = rows.map { it.grams }.sorted()
        val median = grams[grams.size / 2].coerceAtLeast(5.0)
        val step = if (median < 60) 5.0 else 10.0
        val uses = rows.groupingBy { it.slot }.eachCount()
        FoodOption(
            id = "food:$id", name = f.name, role = roleFor(c / 100, p / 100, fat / 100), slots = uses.keys,
            carbPerG = c / 100, proteinPerG = p / 100, fatPerG = fat / 100, fibrePerG = (f.fibrePer100 ?: 0.0) / 100,
            kcalPerG = (f.kcalPer100 ?: (4 * c + 4 * p + 9 * fat)) / 100,
            typicalG = median, minG = (median * 0.5 / step).roundToInt().coerceAtLeast(1) * step, maxG = median * 2,
            stepG = step, unit = f.unit, yours = true, uses = uses,
        )
    }.sortedBy { it.id }
