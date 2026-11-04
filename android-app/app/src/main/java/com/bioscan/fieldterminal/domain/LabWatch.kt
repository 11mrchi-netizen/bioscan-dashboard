@file:kotlinx.serialization.UseSerializers(com.bioscan.fieldterminal.domain.LocalDateSerializer::class, com.bioscan.fieldterminal.domain.InstantSerializer::class, com.bioscan.fieldterminal.domain.LocalTimeSerializer::class, com.bioscan.fieldterminal.domain.LocalDateTimeSerializer::class, com.bioscan.fieldterminal.domain.ZonedDateTimeSerializer::class, com.bioscan.fieldterminal.domain.OffsetDateTimeSerializer::class, com.bioscan.fieldterminal.domain.DurationSerializer::class, com.bioscan.fieldterminal.domain.YearMonthSerializer::class, com.bioscan.fieldterminal.domain.ZoneIdSerializer::class)
package com.bioscan.fieldterminal.domain

import java.time.LocalDate

// Lab watch (docs/regimen/README.md, phase 2; migration 20261117_lab_watch_rules.sql): which lab marker is worth watching
// while taking what. A rule applies to a roster item by name (whole words) or ingredient key, and, when the rule has a
// threshold, only when the item's planned daily amount reaches it. Each applied rule carries the person's latest result
// for its markers, or says it has never been measured. The rule's words come from its cited source.

@kotlinx.serialization.Serializable

data class LabWatchRule(
    val id: Long,
    val version: Int?,
    val substanceKeys: Set<String>,
    val substanceAliases: List<String>,
    val minDailyAmount: Double?,
    val amountUnit: String?,
    val markerLabel: String,
    val markerAliases: List<String>,
    val reason: String,
    val advice: String,
    val source: String,
    val pmidDoi: String?,
    val sourceUrl: String?,
    val caveats: String?,
    val reviewed: Boolean,
    val disputedNote: String? = null,
)

@kotlinx.serialization.Serializable

data class LabReading(val marker: String, val value: Double?, val valueText: String?, val unit: String?, val flag: String?, val date: LocalDate)

data class WatchItem(
    val id: Long,
    val name: String,
    val ingredientKeys: Set<String>,
    val spec: RegimenSpec,
    /** One dose's ingredients: a rule's threshold is read from the ingredient it is about (zinc in a zinc capsule). */
    val ingredients: List<DoseIngredient> = emptyList(),
)

/** The daily amount of the rule's substance: from the matching ingredient, else from the dose itself when its unit fits. */
fun ruleDailyAmount(rule: LabWatchRule, item: WatchItem, today: LocalDate): Double? {
    val unit = rule.amountUnit ?: return null
    val ing = item.ingredients.firstOrNull { i -> i.keys.any { it in rule.substanceKeys } || rule.substanceAliases.any { containsWord(i.name.lowercase(), it) } }
    if (ing != null) {
        val perDose = convertAmount(ing.amount, ing.unit, unit) ?: return null
        val dayFactor = item.spec.dosesPerDay() * ((item.spec.amountOn(today) ?: 1.0) / (item.spec.amount ?: 1.0))
        return perDose * dayFactor
    }
    return dailyAmountIn(item.spec, unit, today)
}

data class LabWatch(
    val rule: LabWatchRule,
    val itemId: Long,
    val itemName: String,
    /** The planned daily amount compared with the rule's threshold, in the rule's unit; null when the rule has none. */
    val dailyAmount: Double?,
    /** The latest result of each matching marker, newest first; empty when never measured. */
    val latest: List<LabReading>,
)

private fun containsWord(haystack: String, word: String): Boolean =
    Regex("""(?<![a-z0-9])${Regex.escape(word.lowercase())}(?![a-z0-9])""").containsMatchIn(haystack)

/** Daily amount in [unit]: one dose × planned doses a day; mg and g convert, other units must match. */
fun dailyAmountIn(spec: RegimenSpec, unit: String, today: LocalDate): Double? {
    val dose = spec.amountOn(today) ?: return null
    val u = spec.unit ?: return null
    val perDose = when {
        u.equals(unit, ignoreCase = true) -> dose
        u == "g" && unit == "mg" -> dose * 1000
        u == "mg" && unit == "g" -> dose / 1000
        u == "mcg" && unit == "mg" -> dose / 1000
        u == "mg" && unit == "mcg" -> dose * 1000
        else -> return null
    }
    return perDose * spec.dosesPerDay()
}

fun matchLabWatch(rules: List<LabWatchRule>, items: List<WatchItem>, results: List<LabReading>, today: LocalDate): List<LabWatch> {
    val latestByMarker = results.groupBy { it.marker.lowercase() }.mapValues { (_, r) -> r.maxBy { it.date } }
    val out = mutableListOf<LabWatch>()
    for (item in items) {
        val name = item.name.replace(Regex("""\([^)]*\)"""), " ").lowercase()
        for (rule in rules) {
            val byName = rule.substanceAliases.any { containsWord(name, it) }
            val byKey = item.ingredientKeys.any { it in rule.substanceKeys }
            if (!byName && !byKey) continue
            var daily: Double? = null
            if (rule.minDailyAmount != null && rule.amountUnit != null) {
                daily = ruleDailyAmount(rule, item, today)
                // A known daily amount under the threshold: the rule does not apply. Unknown: shown, the threshold stated.
                if (daily != null && daily < rule.minDailyAmount) continue
            }
            val latest = latestByMarker.filterKeys { m -> rule.markerAliases.any { m.contains(it.lowercase()) } }.values
                .sortedByDescending { it.date }
            out += LabWatch(rule, item.id, item.name, daily, latest)
        }
    }
    return out.distinctBy { it.rule.id to it.itemId }
}

/** "Copper: never measured" / "Hematocrit 46 % · 25 Apr 2026 · normal". */
fun labReadingWords(r: LabReading): String {
    val v = r.value?.let { formatAmount(Math.round(it * 100) / 100.0) } ?: r.valueText ?: "—"
    val flag = r.flag?.takeIf { it.isNotBlank() && it != "normal" }?.let { " · $it" } ?: ""
    return "${r.marker} $v${r.unit?.let { " $it" } ?: ""} · ${r.date.dayOfMonth} ${r.date.month.name.take(3).lowercase()} ${r.date.year}$flag"
}
