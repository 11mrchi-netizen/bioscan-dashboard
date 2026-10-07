package com.bioscan.fieldterminal.domain.training

// DAV-345. Exercises are logged as free text ("Pull Up (Weighted)", "Weighted Pull Ups",
// "Standing Military Press") while definitions name them once ("Weighted Pull-up",
// "Overhead Press"). A movement key folds those spellings together so a max recorded under
// one name serves the others. It is deliberately conservative: unknown names keep their own
// key rather than being merged on a guess.

private val DROP = setOf("barbell", "standing", "and", "the", "a", "bb", "with")
private val WORD_MAP = mapOf(
    "military" to "overhead", "shoulder" to "overhead", "inclined" to "incline",
    "ups" to "up", "squats" to "squat", "presses" to "press", "rows" to "row", "dips" to "dip", "lunges" to "lunge", "deadlifts" to "deadlift",
)
private val PHRASE_MAP = listOf(
    "chest press" to "bench press",
    "bent over row" to "row",
    "bent over barbell row" to "row",
)

fun movementKey(name: String): String {
    var s = name.replace(Regex("\\([A-Z]{2,5}\\)"), " ").lowercase()
    s = s.replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    s = s.replace("pullups", "pull up").replace("pullup", "pull up").replace("chinups", "chin up").replace("chinup", "chin up")
    for ((from, to) in PHRASE_MAP) s = s.replace(from, to)
    val tokens = s.split(' ').filter { it.isNotBlank() && it !in DROP }.map { WORD_MAP[it] ?: it }
    // "Bench Press" = "Barbell Bench Press"; "Squat" = "Barbell Squat". Order never matters.
    return tokens.distinct().sorted().joinToString(" ")
}
