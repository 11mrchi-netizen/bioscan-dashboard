package com.bioscan.fieldterminal.domain

// Reference cards (docs/regimen/README.md, phase 1b): which of the person's other items an FDA label names. The label's
// own words are the source, so a mention is reported as "the label names X in its <section>", never as an interaction
// verdict: the person reads the sentence and decides. Matching is by whole words of the item's name, without the brand
// in brackets and without words too generic to mean anything on their own.

private val GENERIC_WORDS = setOf(
    "complex", "extract", "concentrate", "activated", "triple", "oil", "powder", "capsule", "capsules", "tablet", "tablets",
    "root", "leaf", "liquid", "plus", "forte", "max", "pure", "natural", "organic", "supplement", "formula", "the", "and",
    "with", "for", "acid",
)

/** The words of an item's name a label could use: "Zinc Picolinate (Swanson)" -> ["zinc picolinate", "zinc", "picolinate"]. */
fun mentionTerms(name: String): List<String> {
    val cleaned = name.replace(Regex("""\([^)]*\)"""), " ").lowercase().replace(Regex("""[^a-z0-9\- ]"""), " ")
        .replace(Regex("""\s+"""), " ").trim()
    if (cleaned.isEmpty()) return emptyList()
    val words = cleaned.split(" ").filter { it.length >= 4 && it !in GENERIC_WORDS && !it.all(Char::isDigit) }
    return (listOf(cleaned) + words).distinct()
}

data class LabelMention(val itemId: Long, val itemName: String, val term: String, val section: String, val excerpt: String)

/** Section keys as stored on the card, in reading order, with their headings. */
val LABEL_SECTION_TITLES: List<Pair<String, String>> = listOf(
    "boxed_warning" to "Boxed warning",
    "contraindications" to "Contraindications",
    "drug_interactions" to "Drug interactions",
    "warnings" to "Warnings and precautions",
    "pharmacokinetics" to "How the body handles it",
    "dosage_forms_and_strengths" to "Forms and strengths",
)

/**
 * The other roster items that [sections] name, one mention per item (the first section that names it, in the order of
 * [LABEL_SECTION_TITLES]), each with the sentence around the word so it can be read in place.
 */
fun labelMentions(sections: Map<String, String>, others: List<Pair<Long, String>>): List<LabelMention> {
    val out = mutableListOf<LabelMention>()
    for ((id, name) in others) {
        val terms = mentionTerms(name)
        hit@ for ((key, title) in LABEL_SECTION_TITLES) {
            val text = sections[key] ?: continue
            val lower = text.lowercase()
            for (term in terms) {
                val m = Regex("""(?<![a-z0-9])${Regex.escape(term)}(?![a-z0-9])""").find(lower) ?: continue
                out += LabelMention(id, name, term, title, excerptAround(text, m.range.first, m.range.last + 1))
                break@hit
            }
        }
    }
    return out
}

/** About a sentence of [text] around [from]..[to], with ellipses where it was cut. */
fun excerptAround(text: String, from: Int, to: Int, radius: Int = 110): String {
    val start = (from - radius).coerceAtLeast(0)
    val end = (to + radius).coerceAtMost(text.length)
    return (if (start > 0) "…" else "") + text.substring(start, end).trim() + (if (end < text.length) "…" else "")
}

/** "17.5 hours", "8 days", "1 week". */
fun halfLifeWords(hours: Double): String = when {
    hours >= 168 && hours % 168 == 0.0 -> "${formatAmount(hours / 168)} week${if (hours == 168.0) "" else "s"}"
    hours >= 48 -> "${formatAmount(Math.round(hours / 24 * 10) / 10.0)} days"
    hours < 1 -> "${formatAmount(Math.round(hours * 60).toDouble())} minutes"
    else -> "${formatAmount(hours)} hours"
}
