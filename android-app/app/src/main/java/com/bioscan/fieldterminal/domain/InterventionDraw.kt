package com.bioscan.fieldterminal.domain

import java.security.SecureRandom
import java.util.Random

// The optional daily coin flip for a repeatable intervention. A selected night (a blocked nose, a heavy day) can differ
// from one you did not pick; a draw made before the day removes that. The draw is stored first and never redone: the
// database allows one per intervention and local day.

enum class DrawResult(val key: String) {
    DO("do"), SKIP("skip");

    companion object {
        fun fromKey(key: String): DrawResult? = entries.firstOrNull { it.key == key }
    }
}

/** A fair coin. [random] is injectable so tests are deterministic; production uses [SecureRandom]. */
fun drawCoin(random: Random = SecureRandom()): DrawResult = if (random.nextBoolean()) DrawResult.DO else DrawResult.SKIP

/** What the person sees for an intervention today: still to draw, a "do" waiting to be logged, a "do" logged, or a "skip". */
enum class DrawState { NOT_DRAWN, DO_PENDING, DO_LOGGED, SKIP }

fun drawState(result: String?, eventId: String?): DrawState = when (result) {
    null -> DrawState.NOT_DRAWN
    "do" -> if (eventId == null) DrawState.DO_PENDING else DrawState.DO_LOGGED
    else -> DrawState.SKIP
}

fun drawText(state: DrawState): String = when (state) {
    DrawState.NOT_DRAWN -> "Not drawn today"
    DrawState.DO_PENDING -> "Drawn: do it today, then log it"
    DrawState.DO_LOGGED -> "Drawn: do it, and it is logged"
    DrawState.SKIP -> "Drawn: skip it today"
}
