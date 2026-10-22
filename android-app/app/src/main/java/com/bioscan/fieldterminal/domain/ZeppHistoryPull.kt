package com.bioscan.fieldterminal.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.OffsetDateTime

// The whole Zepp history is pulled on the server (supabase/functions/zepp-extract/historyPull.ts, migration
// 20261020_zepp_history_pull.sql): a pg_cron job drives zepp-extract back from the start day a week at a time until the
// account's history starts. The app only starts or resumes it and shows where it is, from this row.

@Serializable
data class ZeppHistoryPullRow(
    @SerialName("next_end") val nextEnd: String,
    val workouts: Int = 0,
    @SerialName("band_days") val bandDays: Int = 0,
    @SerialName("weeks_done") val weeksDone: Int = 0,
    @SerialName("oldest_with_data") val oldestWithData: String? = null,
    val done: Boolean = false,
    val error: String? = null,
    @SerialName("running_since") val runningSince: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** One line for Settings: where the pull is, what it found, and whether it waits on something. */
fun ZeppHistoryPullRow.describe(): String {
    val back = oldestWithData?.let { " back to ${LocalDate.parse(it)}" } ?: ""
    val counts = "$workouts workouts, $bandDays days of band data"
    return when {
        error != null -> "Paused$back ($counts): $error"
        done -> "Done: Zepp history$back pulled ($counts)."
        weeksDone == 0 -> "Starting: the server picks it up within a minute."
        else -> "Pulling on the server, now at ${LocalDate.parse(nextEnd)}$back ($counts so far). Runs on with the app closed."
    }
}

/** Seconds since the server last saved progress, or null when unknown. */
fun ZeppHistoryPullRow.secondsSinceUpdate(now: OffsetDateTime): Long? =
    updatedAt?.let { runCatching { java.time.Duration.between(OffsetDateTime.parse(it), now).seconds }.getOrNull() }
