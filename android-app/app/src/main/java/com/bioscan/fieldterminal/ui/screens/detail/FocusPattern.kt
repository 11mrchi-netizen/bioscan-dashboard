package com.bioscan.fieldterminal.ui.screens.detail

import com.bioscan.fieldterminal.domain.score.InputDirection

// Contract A10 (Lead, Support, Depth), the parts that are plain logic and so are unit-tested: the support budget, which
// measure leads, and the one-line verdict under the lead. The composables are in FocusKit.kt.

/** A page shows at most this many support items; the rest belong in Depth. */
const val FOCUS_SUPPORT_MAX = 5

/** The verdict line under the lead stays this short (characters). */
const val FOCUS_VERDICT_MAX = 90

/**
 * Which support item leads: the one the person picked last on this page if it is still offered, else the first.
 * Null only when there is nothing to lead with.
 */
fun focusKey(saved: String?, keys: List<String>): String? = saved?.takeIf { it in keys } ?: keys.firstOrNull()

/** The first [FOCUS_SUPPORT_MAX] items; a page that offers more has put something in the wrong layer. */
fun <T> focusSupport(items: List<T>): List<T> = items.take(FOCUS_SUPPORT_MAX)

/**
 * The verdict line: the verdict word first, then what it is measured against and how it moved, in no more than
 * [FOCUS_VERDICT_MAX] characters. [band] is the normal range already formatted ("7 h 10 to 8 h 05"), [change] the move
 * against the week before ("+12 min vs the week before"). Colour never carries it alone: the words say it.
 */
fun focusVerdictLine(position: InputDirection?, band: String?, change: String?): String {
    val word = when (position) {
        InputDirection.ABOVE -> "ABOVE NORMAL"
        InputDirection.BELOW -> "BELOW NORMAL"
        InputDirection.NORMAL -> "IN YOUR NORMAL"
        else -> "NO NORMAL YET"
    }
    val tail = listOfNotNull(band?.let { "normal $it" }, change).joinToString(" · ")
    val line = if (tail.isEmpty()) word else "$word · $tail"
    return if (line.length <= FOCUS_VERDICT_MAX) line else line.take(FOCUS_VERDICT_MAX - 1).trimEnd() + "…"
}
