package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.LogArousalRow
import com.bioscan.fieldterminal.data.model.LogEncounterRow
import com.bioscan.fieldterminal.data.model.LogHydrationRow
import com.bioscan.fieldterminal.data.model.LogMealRow
import com.bioscan.fieldterminal.data.model.LogNoteRow
import com.bioscan.fieldterminal.data.model.LogRunRow
import com.bioscan.fieldterminal.data.model.LogSleepRow
import com.bioscan.fieldterminal.data.model.LogStoolRow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime

enum class LogEntryKind(val label: String) {
    Run("RUN"), Food("FOOD"), Sleep("SLEEP"), Stool("STOOL"),
    Arousal("AROUSAL"), Encounter("ENC"), Note("NOTE"), Drink("DRINK"),
}

// Which table (and which AddEntryRepository calls) an entry came from --
// needed for Log tab edit/delete, added alongside that flow. Sleep has no
// corresponding add-entry form (Step 12's picker never offered "Sleep"), so
// it's delete-only; every other source is also editable.
enum class LogSource(val table: String) {
    Meal("meals"), Run("runs"), Sleep("sleep_daily"), Arousal("arousal_daily"),
    Stool("stool_log"), Encounter("encounters"), Note("notes"), Hydration("hydration_daily"),
}

data class LogEntry(
    val id: Long,
    val source: LogSource,
    val kind: LogEntryKind,
    val timestamp: LocalDateTime,
    val headline: String,
    val detail: String?,
)

// `runs`/`sleep_daily`/`arousal_daily`/`hydration_daily` only store a `date`,
// no time-of-day -- these nominal times exist purely to give same-day
// entries a stable sort position, not a claim about when the real thing
// happened. Meals, stool and notes have real timestamps and use them as-is.
private val RUN_NOMINAL_TIME = LocalTime.of(7, 0)
private val SLEEP_NOMINAL_TIME = LocalTime.of(7, 30)
private val AROUSAL_NOMINAL_TIME = LocalTime.of(7, 15)
private val ENCOUNTER_NOMINAL_TIME = LocalTime.of(21, 0)
private val DRINK_NOMINAL_TIME = LocalTime.of(12, 0)

fun buildLogEntries(
    meals: List<LogMealRow>,
    runs: List<LogRunRow>,
    sleep: List<LogSleepRow>,
    arousal: List<LogArousalRow>,
    stool: List<LogStoolRow>,
    encounters: List<LogEncounterRow>,
    notes: List<LogNoteRow> = emptyList(),
    hydration: List<LogHydrationRow> = emptyList(),
): List<LogEntry> {
    val entries = mutableListOf<LogEntry>()

    meals.forEach { m ->
        val macros = listOfNotNull(
            m.proteinG?.let { "${it.toInt()} P" },
            m.carbsG?.let { "${it.toInt()} C" },
            m.fatG?.let { "${it.toInt()} F" },
        ).joinToString(" / ")
        entries += LogEntry(
            id = m.id,
            source = LogSource.Meal,
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
            id = r.id,
            source = LogSource.Run,
            kind = LogEntryKind.Run,
            timestamp = LocalDateTime.of(LocalDate.parse(r.date), RUN_NOMINAL_TIME),
            headline = headline.ifEmpty { "Run" },
            detail = r.avgHr?.let { "avg ${it.toInt()} bpm" },
        )
    }

    sleep.forEach { s ->
        val hours = s.hours ?: return@forEach
        entries += LogEntry(
            id = s.id,
            source = LogSource.Sleep,
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
                id = a.id,
                source = LogSource.Arousal,
                kind = LogEntryKind.Arousal,
                timestamp = LocalDateTime.of(LocalDate.parse(a.date), AROUSAL_NOMINAL_TIME),
                headline = parts.joinToString(" · "),
                detail = null,
            )
        }
    }

    stool.forEach { s ->
        entries += LogEntry(
            id = s.id,
            source = LogSource.Stool,
            kind = LogEntryKind.Stool,
            timestamp = parseTimestamp(s.occurredAt),
            headline = "Bristol ${s.bristolType}",
            detail = s.discomfort?.let { "Discomfort $it/10" },
        )
    }

    encounters.forEach { e ->
        entries += LogEntry(
            id = e.id,
            source = LogSource.Encounter,
            kind = LogEntryKind.Encounter,
            timestamp = LocalDateTime.of(LocalDate.parse(e.date), ENCOUNTER_NOMINAL_TIME),
            headline = e.calendarEventTitle?.takeIf { it.isNotBlank() } ?: e.status.replaceFirstChar { it.uppercase() },
            detail = null,
        )
    }

    notes.forEach { n ->
        entries += LogEntry(
            id = n.id,
            source = LogSource.Note,
            kind = LogEntryKind.Note,
            timestamp = parseTimestamp(n.occurredAt),
            headline = n.text,
            detail = null,
        )
    }

    hydration.forEach { h ->
        val ml = h.ml ?: return@forEach
        entries += LogEntry(
            id = h.id,
            source = LogSource.Hydration,
            kind = LogEntryKind.Drink,
            timestamp = LocalDateTime.of(LocalDate.parse(h.date), DRINK_NOMINAL_TIME),
            headline = "$ml ml (day total)",
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
