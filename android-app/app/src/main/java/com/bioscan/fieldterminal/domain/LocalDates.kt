package com.bioscan.fieldterminal.domain

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

// The local calendar day a stored timestamp falls on. Postgres returns timestamptz in UTC ("2026-10-20T23:30:00+00:00"),
// so the timestamp's own date is the UTC date: in UTC+8 a 07:00 session would land on the day before. Charts and day
// buckets use the phone's local dates, so every timestamp is moved into the zone first. Accepts the space-separated form
// Postgres also uses; a bare date (no time) is taken as already local. Null when unparseable.
fun localDateIn(timestamp: String, zone: ZoneId): LocalDate? =
    runCatching { OffsetDateTime.parse(timestamp.replace(' ', 'T')).atZoneSameInstant(zone).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDate.parse(timestamp.take(10)) }.getOrNull()
