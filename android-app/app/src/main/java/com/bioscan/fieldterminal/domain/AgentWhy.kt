package com.bioscan.fieldterminal.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// Personal health agent, phase 1 (docs/personal-health-agent): what a card sends when WHY? is tapped. The card passes
// exactly what it shows (its title, its words and the sentences behind its pills), so the reply can only quote what the
// person can already see plus what the tools return; the server's guards check that (agent-core/guards.ts).
data class WhyContext(
    /** The card title as shown, e.g. "THIS MORNING". */
    val card: String,
    /** The card's words in display order: label -> word ("HRV" -> "BELOW EXPECTED"). */
    val words: List<Pair<String, String>> = emptyList(),
    /** Sentences the card or its pop-ups show, by label. */
    val shown: Map<String, String> = emptyMap(),
    /** A registered series the card is about (metric-series.json id), when there is one. */
    val series: String? = null,
    /** An evidence item id ("q:...", "exp:12"), when the card is about one. */
    val itemId: String? = null,
)

/** The question the agent answers, built from the card so it names what the person is looking at. */
fun WhyContext.question(): String {
    val said = words.joinToString(", ") { (label, word) -> if (label.isBlank() || label == card) word else "$label $word" }
    return if (said.isBlank()) "What does $card show, and what does it rest on?" else "Why does $card read $said?"
}

/** The JSON the app sends as `context`: only what the screen shows, nothing it does not. */
fun WhyContext.toJson(date: String): JsonObject = buildJsonObject {
    put("card", card)
    put("date", date)
    series?.let { put("series", it) }
    itemId?.let { put("item_id", it) }
    if (words.isNotEmpty()) putJsonObject("words") { words.forEach { (label, word) -> put(label.ifBlank { card }, word) } }
    if (shown.isNotEmpty()) putJsonObject("shown") { shown.forEach { (k, v) -> put(k, v) } }
}

/** Server error codes as sentences for the sheet. */
fun agentErrorMessage(code: String?, message: String?): String = when (code) {
    "assistant_off" -> "The assistant is off. Turn it on in Setup › Profile & goals › Data capture › ASSISTANT."
    "daily_cap" -> message ?: "The assistant has answered enough questions for today."
    "not_configured" -> "The assistant is not set up on the server yet."
    else -> message ?: "The assistant could not answer right now. Try again in a minute."
}
