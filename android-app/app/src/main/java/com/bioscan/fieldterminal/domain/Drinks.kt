package com.bioscan.fieldterminal.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

// Drinks as real products (the catalog's USDA, Taiwan FDA, Open Food Facts and scanned-label drinks): what a logged
// amount of one holds, the person's usual drinks, and the day's caffeine and alcohol for the scores.

/** A serving of a drink as the catalog names it ("1 can", "large cup"), in ml. */
@Serializable
data class DrinkServing(val name: String, val ml: Double)

/** One drink from public.search_drinks / public.drink_rows: nutrients per 100 ml, sizes in ml. */
@Serializable
data class DrinkMatch(
    val id: Long,
    val name: String,
    @SerialName("name_zh") val nameZh: String? = null,
    val brand: String? = null,
    val barcode: String? = null,
    val source: String? = null,
    @SerialName("beverage_class") val beverageClass: String? = null,
    @SerialName("beverage_subtype") val beverageSubtype: String? = null,
    @SerialName("drink_default") val drinkDefault: Boolean = false,
    val kcal: Double? = null,
    @SerialName("sugar_g") val sugarG: Double? = null,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("protein_g") val proteinG: Double? = null,
    @SerialName("fat_g") val fatG: Double? = null,
    @SerialName("sodium_mg") val sodiumMg: Double? = null,
    @SerialName("caffeine_mg") val caffeineMg: Double? = null,
    @SerialName("alcohol_g") val alcoholG: Double? = null,
    @SerialName("water_ml") val waterMl: Double? = null,
    @SerialName("caffeine_estimated") val caffeineEstimated: Boolean = false,
    val servings: List<DrinkServing> = emptyList(),
) {
    /** The name to show: brand first when it has one, the Chinese name beside the English one. */
    val title: String get() = listOfNotNull(brand?.takeIf { it.isNotBlank() && !name.contains(it, ignoreCase = true) }, name).joinToString(" ")
}

/** What [ml] of a drink holds. Null = the source does not say. */
data class DrinkAmounts(
    val ml: Double,
    val kcal: Double?,
    val sugarG: Double?,
    val carbsG: Double?,
    val proteinG: Double?,
    val fatG: Double?,
    val sodiumMg: Double?,
    val caffeineMg: Double?,
    val alcoholG: Double?,
    val waterMl: Double,
)

/**
 * [ml] of [drink]. An [abvPercent] the person typed replaces the catalog's alcohol (a beer is not every beer); with no
 * catalog drink at all only the volume and that alcohol are known.
 */
fun drinkAmounts(drink: DrinkMatch?, ml: Double, abvPercent: Double? = null): DrinkAmounts {
    val f = ml / 100.0
    fun per(v: Double?) = v?.let { round1(it * f) }
    val alcohol = abvPercent?.let { ethanolGrams(ml, it) } ?: per(drink?.alcoholG)
    return DrinkAmounts(
        ml = ml,
        kcal = per(drink?.kcal),
        sugarG = per(drink?.sugarG),
        carbsG = per(drink?.carbsG),
        proteinG = per(drink?.proteinG),
        fatG = per(drink?.fatG),
        sodiumMg = per(drink?.sodiumMg),
        caffeineMg = per(drink?.caffeineMg),
        alcoholG = alcohol,
        // Water in the drink; a drink whose source has no water figure counts its whole volume.
        waterMl = per(drink?.waterMl)?.coerceAtMost(ml) ?: ml,
    )
}

/** The sizes offered for a drink: its own servings first (deduplicated by volume), then the usual cups and bottles. */
fun drinkSizes(drink: DrinkMatch?, beverageClass: String?): List<DrinkServing> {
    val own = drink?.servings.orEmpty()
        // A spirit's 1 fl oz is a real measure; for everything else a size under 100 ml is a unit of the table, not a drink.
        .filter { it.ml <= 2000 && (it.ml >= 100 || beverageClass == "alcohol" || beverageClass == "coffee" && it.ml >= 25) }
        .distinctBy { (it.ml / 10).roundToInt() }
        .sortedBy { it.ml }
        .take(4)
    val generic = when (beverageClass) {
        "coffee" -> listOf(DrinkServing("espresso", 30.0), DrinkServing("small", 240.0), DrinkServing("medium", 360.0), DrinkServing("large", 480.0))
        "tea" -> listOf(DrinkServing("cup", 240.0), DrinkServing("medium", 500.0), DrinkServing("large", 700.0))
        "alcohol" -> when (drink?.beverageSubtype) {
            "wine" -> listOf(DrinkServing("glass", 150.0), DrinkServing("large glass", 250.0))
            "spirits" -> listOf(DrinkServing("shot", 30.0), DrinkServing("double", 60.0))
            else -> listOf(DrinkServing("can", 330.0), DrinkServing("bottle", 500.0), DrinkServing("pint", 568.0))
        }
        "soda", "electrolyte" -> listOf(DrinkServing("can", 330.0), DrinkServing("bottle", 600.0))
        else -> listOf(DrinkServing("glass", 250.0), DrinkServing("bottle", 500.0))
    }
    return (own + generic).distinctBy { (it.ml / 10).roundToInt() }.sortedBy { it.ml }.take(6)
}

/** A drink logged before (a beverage meal item), for the usual drinks. */
data class LoggedDrink(
    val at: Instant,
    val foodId: Long?,
    val description: String,
    val beverageClass: String?,
    val ml: Double?,
    val caffeineMg: Double?,
    val alcoholG: Double?,
    /** The water it counts for (its volume times its kind's retention factor). */
    val hydrationMl: Double? = null,
)

/** A usual drink: what it is, the amount usually had and how often. */
data class UsualDrink(val foodId: Long?, val description: String, val beverageClass: String?, val ml: Double, val times: Int, val lastAt: Instant)

/**
 * The drinks had at least twice in the [days] before [now], most often first: one per catalog food (or per name when it
 * has none), with the median amount.
 */
fun usualDrinks(logged: List<LoggedDrink>, now: Instant, days: Long = 60, max: Int = 6): List<UsualDrink> =
    logged.filter { !it.at.isAfter(now) && it.at.isAfter(now.minusSeconds(days * 86_400)) && (it.ml ?: 0.0) > 0 }
        .groupBy { it.foodId?.toString() ?: it.description.trim().lowercase() }
        .values
        .filter { it.size >= 2 }
        .map { g ->
            val last = g.maxBy { it.at }
            val mls = g.mapNotNull { it.ml }.sorted()
            UsualDrink(last.foodId, last.description, last.beverageClass, mls[mls.size / 2], g.size, last.at)
        }
        .sortedWith(compareByDescending<UsualDrink> { it.times }.thenByDescending { it.lastAt })
        .take(max)

/** A day's drinks as the scores read them. */
data class DrinkDay(val caffeineAfter14Mg: Double, val alcoholG: Double)

/** Logged drinks by local day: caffeine taken from 14:00 on, and all the alcohol. Times are logged wall-clock times. */
fun drinkDays(logged: List<LoggedDrink>, zone: ZoneId): Map<LocalDate, DrinkDay> =
    logged.groupBy { it.at.atZone(zone).toLocalDate() }.mapValues { (_, day) ->
        DrinkDay(
            caffeineAfter14Mg = day.filter { it.at.atZone(zone).hour >= 14 }.sumOf { it.caffeineMg ?: 0.0 },
            alcoholG = day.sumOf { it.alcoholG ?: 0.0 },
        )
    }

/** The water drinks count for, by local day (on top of the water logged as water). */
fun drinkHydrationByDay(logged: List<LoggedDrink>, zone: ZoneId): Map<LocalDate, Double> =
    logged.filter { (it.hydrationMl ?: 0.0) > 0 }.groupBy { it.at.atZone(zone).toLocalDate() }.mapValues { (_, d) -> d.sumOf { it.hydrationMl!! } }

/** Grams of pure alcohol in one standard drink (the WHO / Taiwan measure). */
const val STANDARD_DRINK_G = 10.0

/** A "yes, caffeine after 14:00" check-in answer with nothing logged stands for one cup of brewed coffee. */
const val CHECKIN_LATE_CAFFEINE_MG = 95.0

private fun round1(v: Double) = Math.round(v * 10.0) / 10.0
