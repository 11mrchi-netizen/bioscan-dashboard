package com.bioscan.fieldterminal.domain

// Live check: "estimated/looked-up food can't be saved" traced to
// NutritionFoodSearchRepository.search() wrapping the ENTIRE query in one
// `ilike("name", "%$query%")` -- fine for a short manual search ("egg"), but
// the review sheet defaults its search box to the AI estimate's own free-text
// description ("Grilled chicken breast with steamed broccoli"), and no real
// foods.name row contains that whole phrase as a contiguous substring. Search
// always came back empty, so an AI-estimated item's food could never be
// matched, and the review sheet's SAVE button -- gated on every item being
// resolved -- silently stayed disabled forever, no error shown.
//
// Fix: search each significant word instead of the whole phrase (every
// token must appear somewhere in the name, AND-combined, order-independent --
// matches "Chicken, breast, grilled" against a "Grilled chicken breast"
// query, which the old whole-phrase substring never could). Connector words
// are dropped outright rather than by length alone -- "with"/"and" are long
// enough to survive a pure length filter, but AND-requiring either into a
// food name would zero out real matches ("chicken with rice" -> almost no
// real food name contains the literal word "with"). Capped at 3 words so a
// long description doesn't AND-narrow itself into zero results either.
private val STOPWORDS = setOf("with", "and", "the", "for", "a", "an", "of", "in", "on")
private const val MIN_TOKEN_LENGTH = 3
private const val MAX_TOKENS = 3

fun foodSearchTokens(query: String): List<String> =
    query.trim().split(Regex("\\s+"))
        .filter { it.length >= MIN_TOKEN_LENGTH && it.lowercase() !in STOPWORDS }
        .take(MAX_TOKENS)
