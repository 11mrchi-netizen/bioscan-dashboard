package com.bioscan.fieldterminal.domain

import kotlinx.serialization.Serializable
import java.time.LocalDate

// Pulling the whole Zepp history (workouts, daily band data, stress): the stored data from Zepp started on 26 Aug
// 2026, and Health Connect's copy of the older runs is about twice their real distance. The pull walks back from
// today in windows of HISTORY_WINDOW_DAYS, each one call to zepp-extract, and stops where the account's history starts:
// HISTORY_STOP_AFTER_EMPTY windows in a row with no workout and no day of band data. The state is stored after every
// window, so an interrupted pull (app closed, no network) carries on from where it stopped.

// A week per call keeps each zepp-extract run well inside its time limit (about 3 Zepp requests a day plus workouts).
const val HISTORY_WINDOW_DAYS = 7L
// Eight weeks without a workout or a day of band data: the account's history has ended.
const val HISTORY_STOP_AFTER_EMPTY = 8
/** Never look further back than this. */
val HISTORY_FLOOR: LocalDate = LocalDate.of(2015, 1, 1)

@Serializable
data class HistoryPullState(
    /** The newest day of the next window to pull (ISO date). */
    val nextEnd: String,
    val emptyStreak: Int = 0,
    val workouts: Int = 0,
    val bandDays: Int = 0,
    val windowsDone: Int = 0,
    /** Start of the oldest window that held anything (ISO date). */
    val oldestWithData: String? = null,
    val done: Boolean = false,
    val error: String? = null,
) {
    val nextWindow: Pair<LocalDate, LocalDate>
        get() = LocalDate.parse(nextEnd).let { end -> end.minusDays(HISTORY_WINDOW_DAYS - 1) to end }
}

fun startHistoryPull(today: LocalDate): HistoryPullState = HistoryPullState(nextEnd = today.toString())

/** The state after one window came back with [workouts] workouts and [bandDays] days of band data. */
fun HistoryPullState.afterWindow(workouts: Int, bandDays: Int): HistoryPullState {
    val (from, _) = nextWindow
    val empty = workouts == 0 && bandDays == 0
    val streak = if (empty) emptyStreak + 1 else 0
    val nextEndDate = from.minusDays(1)
    return copy(
        nextEnd = nextEndDate.toString(),
        emptyStreak = streak,
        workouts = this.workouts + workouts,
        bandDays = this.bandDays + bandDays,
        windowsDone = windowsDone + 1,
        oldestWithData = if (empty) oldestWithData else from.toString(),
        done = streak >= HISTORY_STOP_AFTER_EMPTY || nextEndDate.isBefore(HISTORY_FLOOR),
        error = null,
    )
}

/** One line for Settings. */
fun HistoryPullState.describe(running: Boolean): String {
    val back = oldestWithData?.let { " back to ${LocalDate.parse(it)}" } ?: ""
    val counts = "$workouts workouts, $bandDays days of band data"
    return when {
        error != null -> "Paused$back ($counts): $error. Tap again to carry on."
        done -> "Done: Zepp history$back pulled ($counts)."
        running -> "Pulling, now at ${nextWindow.second}$back ($counts so far)…"
        else -> "Stopped at ${nextWindow.second}$back ($counts). Tap to carry on."
    }
}
