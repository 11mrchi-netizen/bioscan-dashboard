package com.bioscan.fieldterminal.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

// This app's timestamp convention (AddEntrySheet's toIsoWithOffset, HealthConnectExerciseSyncRepository, zepp-extract's
// toLocalLabeledUtcIso): every LOGGED time -- meals.logged_at, stool_log / grip / notes / body_checkins / scale_readings
// occurred_at, exercise_sessions.start_time, athlete_events.start_time -- is the phone's LOCAL WALL-CLOCK reading labelled
// with a zero (UTC) offset. Its digits are the local time; it is not a real instant.
//
// A few columns are real instants and are NOT read this way: calendar_events start/end (from Google), sleep_daily
// bedtime / wake_time (checked against the data: 16:01Z is a 00:01 bedtime in Taipei), Zepp's stress samples (epoch
// milliseconds) and every created_at / synced_at.
//
// Reading a logged time as a real instant moves it by the UTC offset (8 h in Taipei): lunch at 13:10 became 21:10 and was
// matched to dinner, a 08:11 session became 16:11 and never matched the 07:00 plan. These helpers read and write the
// convention in one place.
object WallClock {
    /** The local date-time a logged timestamp stands for: its digits, whatever offset it carries. */
    fun read(iso: String?): LocalDateTime? {
        val text = iso?.trim()?.replace(' ', 'T') ?: return null
        return runCatching { OffsetDateTime.parse(text).toLocalDateTime() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(text) }.getOrNull()
    }

    /** The local date a logged timestamp falls on. */
    fun date(iso: String?): LocalDate? = read(iso)?.toLocalDate()

    /** The logged time placed in [zone], for comparing with plan times. */
    fun zoned(iso: String?, zone: ZoneId): ZonedDateTime? = read(iso)?.atZone(zone)

    /** The real instant a logged time stands for in [zone], for comparing with true instants (stress samples, sleep). */
    fun instant(iso: String?, zone: ZoneId): Instant? = zoned(iso, zone)?.toInstant()

    /** A local date-time written the app's way: its digits with a zero offset. */
    fun write(local: LocalDateTime): String = local.atOffset(ZoneOffset.UTC).toString()

    /** A zoned time written the app's way: its local digits with a zero offset. */
    fun write(time: ZonedDateTime): String = write(time.toLocalDateTime())
}
