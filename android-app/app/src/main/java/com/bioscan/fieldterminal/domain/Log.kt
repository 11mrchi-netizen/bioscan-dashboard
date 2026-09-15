package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.LogArousalRow
import com.bioscan.fieldterminal.data.model.LogEncounterRow
import com.bioscan.fieldterminal.data.model.LogMealRow
import com.bioscan.fieldterminal.data.model.LogRunRow
import com.bioscan.fieldterminal.data.model.LogSleepRow
import com.bioscan.fieldterminal.data.model.LogStoolRow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime

enum class LogEntryKind(val label: String) {
    Run("RUN"), Food("FOOD"), Sleep("SLEEP"), Stool("STOOL"),
    Arousal("AROUSAL"), Encounter("ENC"),
}

data class LogEntry(
    val kind: LogEntryKind,
    val timestamp: LocalDateTime,
    val headline: String,
    val detail: String?,
)

// `runs`/`sleep_daily`/`arousal_daily` only store a `date`, no time-of-day --
// these nominal times exist purely to give same-day entries a stable sort
// position, not a claim about when the real thing happened. Meals and stool
// have real timestamps and use them as-is.
private val RUN_NOMINAL_TIME = LocalTime.of(7, 0)
private val SLEEP_NOMINAL_TIME = LocalTime.of(7, 30)
private val AROUSAL_NOMINAL_TIME = LocalTime.of(7, 15)
private val ENCOUNTER_NOMINAL_TIME = LocalTime.of(21, 0)

fun buildLogEntries(
    meals: List<LogMealRow>,
    runs: List<LogRunRow>,
    sleep: List<LogSleepRow>,
    arousal: List<LogArousalRow>,
    stool: List<LogStoolRow>,
    encounters: List<LogEncounterRow>,
): List<LogEntry> {
    val entries = mutableListOf<LogEntry>()

    meals.forEach { m ->
        val macros = listOfNotNull(
            m.proteinG?.let { "${it.toInt()} P" },
            m.carbsG?.let { "${it.toInt()} C" },
            m.fatG?.let { "${it.toInt()} F" },
        ).joinToString(" / ")
        entries += LogEntry(
            kind = LogEntryKind.Food,
            timestamp = parseTimestamp(m.loggedAt),
            headline = m.description?.takeIf { it.isNotBlank() } ?: "Meal",
            detail = listOfNotNull(m.calories?.let { "${it.toInt()} kcal" }, macros.takeIf { it.isNotEmpty() }).joinToString(" · "),
        )
    }

    runs.forEach { r ->
        val headline = listOfNotNull(
            r.distanceKm?.let { "%.1f km".format(it) },
            r.durationMin?.let { formatDuration(it) },
        ).joinToString(" · ")
        entries += LogEntry(
            kind = LogEntryKind.Run,
            timestamp = LocalDateTime.of(LocalDate.parse(r.date), RUN_NOMINAL_TIME),
            headline = headline.ifEmpty { "Run" },
            detail = r.avgHr?.let { "avg ${it.toInt()} bpm" },
        )
    }

    sleep.forEach { s ->
        val hours = s.hours ?: return@forEach
        entries += LogEntry(
            kind = LogEntryKind.Sleep,
            timestamp = LocalDateTime.of(LocalDate.parse(s.date), SLEEP_NOMINAL_TIME),
            headline = formatDuration(hours * 60),
            detail = s.score?.let { "Score $it" },
        )
    }

    arousal.forEach { a ->
        val parts = listOfNotNull(
            a.morningErectionQuality?.let { "Morning wood $it/10" },
            a.arousalLevel?.let { "Arousal $it/10" },
        )
        if (parts.isNotEmpty()) {
            entries += LogEntry(
                kind = LogEntryKind.Arousal,
                timestamp = LocalDateTime.of(LocalDate.parse(a.date), AROUSAL_NOMINAL_TIME),
                headline = parts.joinToString(" · "),
                detail = null,
            )
        }
    }

    stool.forEach { s ->
        entries += LogEntry(
            kind = LogEntryKind.Stool,
            timestamp = parseTimestamp(s.occurredAt),
            headline = "Bristol ${s.bristolType}",
            detail = s.discomfort?.let { "Discomfort $it/10" },
        )
    }

    encounters.forEach { e ->
        entries += LogEntry(
            kind = LogEntryKind.Encounter,
            timestamp = LocalDateTime.of(LocalDate.parse(e.date), ENCOUNTER_NOMINAL_TIME),
            headline = e.calendarEventTitle?.takeIf { it.isNotBlank() } ?: e.status.replaceFirstChar { it.uppercase() },
            detail = null,
        )
    }

    return entries.sortedByDescending { it.timestamp }
}

private fun parseTimestamp(iso: String): LocalDateTime =
    try {
        OffsetDateTime.parse(iso).toLocalDateTime()
    } catch (e: Exception) {
        LocalDateTime.parse(iso)
    }

private fun formatDuration(totalMinutes: Double): String {
    val h = (totalMinutes / 60).toInt()
    val m = (totalMinutes % 60).toInt()
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
