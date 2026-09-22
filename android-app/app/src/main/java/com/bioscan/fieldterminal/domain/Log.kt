package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.LogArousalRow
import com.bioscan.fieldterminal.data.model.LogEncounterRow
import com.bioscan.fieldterminal.data.model.LogExerciseRow
import com.bioscan.fieldterminal.data.model.LogHydrationRow
import com.bioscan.fieldterminal.data.model.LogMasturbationRow
import com.bioscan.fieldterminal.data.model.LogMealRow
import com.bioscan.fieldterminal.data.model.LogNoteRow
import com.bioscan.fieldterminal.data.model.LogOstrcRow
import com.bioscan.fieldterminal.data.model.LogSleepRow
import com.bioscan.fieldterminal.data.model.LogStoolRow
import com.bioscan.fieldterminal.data.model.LogSupplementTakenRow
import com.bioscan.fieldterminal.data.model.LogWellbeingRow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime

enum class LogEntryKind(val label: String) {
    Exercise("EXERCISE"), Food("FOOD"), Sleep("SLEEP"), Stool("STOOL"),
    Arousal("AROUSAL"), Encounter("ENC"), Note("NOTE"), Drink("DRINK"),
    Wellness("WELL"), Supplement("SUPP"), Ostrc("OSTRC"), Masturbation("MASTURBATION"),
}

// Which table (and which AddEntryRepository calls) an entry came from --
// needed for Log tab edit/delete, added alongside that flow. Phase G3:
// Exercise (was Run) points at the generic `exercise_sessions` table and,
// unlike the old runs-only source, IS editable -- not its Health-Connect-
// sourced fields, just the rpe/notes "extra details" (see
// ExerciseDetailsForm in ui/screens/AddEntrySheet.kt). Sleep and Supplement
// still have no corresponding edit form (a taken supplement is
// add-or-remove-as-a-whole, not field-editable), so those two stay
// delete-only; every other source is also editable.
enum class LogSource(val table: String) {
    Meal("meals"), Exercise("exercise_sessions"), Sleep("sleep_daily"), Arousal("arousal_daily"),
    Stool("stool_log"), Encounter("encounters"), Note("notes"), Hydration("hydration_daily"),
    Wellbeing("wellbeing_daily"), Supplement("supplement_log"), Ostrc("ostrc_checkins"),
    Masturbation("masturbation_log"),
}

data class LogEntry(
    val id: Long,
    val source: LogSource,
    val kind: LogEntryKind,
    val timestamp: LocalDateTime,
    val headline: String,
    val detail: String?,
)

// `sleep_daily`/`arousal_daily`/`hydration_daily`/`wellbeing_daily` only
// store a `date`, no time-of-day -- these nominal times exist purely to
// give same-day entries a stable sort position, not a claim about when the
// real thing happened. Meals, stool, notes, supplement_log and (since
// Phase G3) exercise_sessions all have real timestamps and use them as-is.
// Encounter (DAV-158) moved from this fake-time group to the real-timestamp
// group: `occurred_at` is now a real, user-editable column, so this nominal
// constant only remains as a fallback for encounters logged before that
// column existed (occurredAt == null).
private val SLEEP_NOMINAL_TIME = LocalTime.of(7, 30)
private val AROUSAL_NOMINAL_TIME = LocalTime.of(7, 15)
private val ENCOUNTER_NOMINAL_TIME = LocalTime.of(21, 0)
private val DRINK_NOMINAL_TIME = LocalTime.of(12, 0)
private val WELLNESS_NOMINAL_TIME = LocalTime.of(8, 0)
private val OSTRC_NOMINAL_TIME = LocalTime.of(8, 15)

fun buildLogEntries(
    meals: List<LogMealRow>,
    exerciseSessions: List<LogExerciseRow>,
    sleep: List<LogSleepRow>,
    arousal: List<LogArousalRow>,
    stool: List<LogStoolRow>,
    encounters: List<LogEncounterRow>,
    notes: List<LogNoteRow> = emptyList(),
    hydration: List<LogHydrationRow> = emptyList(),
    wellbeing: List<LogWellbeingRow> = emptyList(),
    supplementsTaken: List<LogSupplementTakenRow> = emptyList(),
    ostrc: List<LogOstrcRow> = emptyList(),
    masturbation: List<LogMasturbationRow> = emptyList(),
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

    exerciseSessions.forEach { e ->
        val headline = listOfNotNull(
            e.type.replaceFirstChar { it.uppercase() },
            e.distanceKm?.let { "%.1f km".format(it) },
            e.durationMin?.let { formatDuration(it) },
        ).joinToString(" · ")
        entries += LogEntry(
            id = e.id,
            source = LogSource.Exercise,
            kind = LogEntryKind.Exercise,
            timestamp = parseTimestamp(e.startTime),
            headline = headline,
            detail = e.avgHr?.let { "avg ${it.toInt()} bpm" },
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
            timestamp = e.occurredAt?.let { parseTimestamp(it) } ?: LocalDateTime.of(LocalDate.parse(e.date), ENCOUNTER_NOMINAL_TIME),
            headline = e.calendarEventTitle?.takeIf { it.isNotBlank() } ?: e.status.replaceFirstChar { it.uppercase() },
            detail = e.myRating?.let { "Rating $it/5" },
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

    wellbeing.forEach { w ->
        val parts = listOfNotNull(
            w.energy?.let { "Energy $it" },
            w.mood?.let { "Mood $it" },
            w.stress?.let { "Stress $it" },
            w.soreness?.let { "Soreness $it" },
        )
        if (parts.isNotEmpty()) {
            entries += LogEntry(
                id = w.id,
                source = LogSource.Wellbeing,
                kind = LogEntryKind.Wellness,
                timestamp = LocalDateTime.of(LocalDate.parse(w.date), WELLNESS_NOMINAL_TIME),
                headline = parts.joinToString(" · "),
                detail = null,
            )
        }
    }

    supplementsTaken.forEach { s ->
        entries += LogEntry(
            id = s.id,
            source = LogSource.Supplement,
            kind = LogEntryKind.Supplement,
            timestamp = parseTimestamp(s.takenAt),
            headline = s.supplementName,
            detail = formatDose(s.doseValue, s.doseUnit),
        )
    }

    ostrc.forEach { o ->
        entries += LogEntry(
            id = o.id,
            source = LogSource.Ostrc,
            kind = LogEntryKind.Ostrc,
            timestamp = LocalDateTime.of(LocalDate.parse(o.checkDate), OSTRC_NOMINAL_TIME),
            headline = "${o.bodyArea} · severity ${o.q1 + o.q2 + o.q3 + o.q4}/100",
            detail = null,
        )
    }

    masturbation.forEach { m ->
        val parts = listOfNotNull(
            m.watchedPorn?.let { "Porn: ${if (it) "yes" else "no"}" },
            m.loadSize?.let { "Load $it/5" },
            m.orgasmIntensity?.let { "Intensity $it/10" },
        )
        entries += LogEntry(
            id = m.id,
            source = LogSource.Masturbation,
            kind = LogEntryKind.Masturbation,
            timestamp = parseTimestamp(m.occurredAt),
            headline = parts.joinToString(" · ").ifEmpty { "Masturbation" },
            detail = m.notes,
        )
    }

    return entries.sortedByDescending { it.timestamp }
}

// DAV-156. Whole-number doses print without a trailing ".0" (Double's own
// toString() always shows one); a dose with no parsed value but a real unit
// string (e.g. "as needed") still shows that unit rather than nothing.
private fun formatDose(value: Double?, unit: String?): String? = when {
    value != null && value == Math.floor(value) -> "${value.toInt()}${unit?.let { " $it" } ?: ""}"
    value != null -> "$value${unit?.let { " $it" } ?: ""}"
    else -> unit
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
