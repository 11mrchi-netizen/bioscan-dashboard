package com.bioscan.fieldterminal.domain.notifications

import com.bioscan.fieldterminal.data.model.NotificationKind
import com.bioscan.fieldterminal.data.model.NotificationPrefsRow
import com.bioscan.fieldterminal.data.model.NotificationRuleRow
import com.bioscan.fieldterminal.data.model.SupplementRow
import com.bioscan.fieldterminal.domain.isDueOn
import com.bioscan.fieldterminal.domain.doseTextOn
import com.bioscan.fieldterminal.domain.spec
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

// Pure rule engine for the notification system -- no Android, no Supabase.
// All times are LocalDateTime in the user's own zone; the scheduler converts
// to/from epoch millis so DST handling lives in exactly one place.

fun parseHm(hm: String): LocalTime = LocalTime.parse(hm)

// The quiet window is [quietStart, quietEnd) in "HH:mm" and may cross midnight
// (default 23:00..07:00). quietEnd itself is outside the window, so a reminder
// scheduled exactly then fires. Equal start/end means no window.
fun isInQuietWindow(time: LocalTime, prefs: NotificationPrefsRow): Boolean {
    if (!prefs.quietEnabled) return false
    val start = parseHm(prefs.quietStart)
    val end = parseHm(prefs.quietEnd)
    return when {
        start == end -> false
        start < end -> time >= start && time < end
        else -> time >= start || time < end
    }
}

// Quiet hours policy: a reminder due inside the window is held and fires when
// the window ends (it is delayed, never dropped, so a check-in still arrives).
// Returns the actual fire time.
fun resolveQuietHours(candidate: LocalDateTime, prefs: NotificationPrefsRow): LocalDateTime {
    val time = candidate.toLocalTime()
    if (!isInQuietWindow(time, prefs)) return candidate
    val start = parseHm(prefs.quietStart)
    val end = parseHm(prefs.quietEnd)
    // Crossing-midnight window, evening side (>= start): the end is tomorrow.
    val endsTomorrow = start > end && time >= start
    val date = if (endsTomorrow) candidate.toLocalDate().plusDays(1) else candidate.toLocalDate()
    return LocalDateTime.of(date, end)
}

fun scheduledTimes(rule: NotificationRuleRow): List<LocalTime> =
    when (rule.kind) {
        NotificationKind.SUPPLEMENT_DUE -> rule.schedule.buckets.values
        else -> rule.schedule.times
    }.map(::parseHm).sorted()

// Next moment this rule should fire strictly after `after`, honoring master
// switch, enabled flag, day-of-week and quiet hours (delayed to the window end,
// not dropped). Null when it never will (disabled / no times / no allowed day
// within the next 8 days).
fun nextFireTime(rule: NotificationRuleRow, prefs: NotificationPrefsRow, after: LocalDateTime): LocalDateTime? {
    if (!prefs.masterEnabled || !rule.enabled) return null
    val times = scheduledTimes(rule)
    if (times.isEmpty()) return null
    for (offset in 0L..7L) {
        val date = after.toLocalDate().plusDays(offset)
        if (date.dayOfWeek.value !in rule.schedule.days) continue
        for (t in times) {
            val candidate = LocalDateTime.of(date, t)
            if (!candidate.isAfter(after)) continue
            val resolved = resolveQuietHours(candidate, prefs)
            if (resolved.isAfter(after)) return resolved
        }
    }
    return null
}

data class ShownEntry(val ruleKind: String, val firedAt: LocalDateTime)

// Caps checked at fire time against today's already-shown notifications.
// A snooze re-fire skips the min-gap check (the gap is what the snooze set).
fun withinLimits(
    rule: NotificationRuleRow,
    prefs: NotificationPrefsRow,
    now: LocalDateTime,
    shownToday: List<ShownEntry>,
    isSnoozeRefire: Boolean = false,
): Boolean {
    if (shownToday.size >= prefs.dailyCap) return false
    val mine = shownToday.filter { it.ruleKind == rule.kind }
    if (mine.size >= rule.limits.maxPerDay) return false
    if (!isSnoozeRefire) {
        val last = mine.maxOfOrNull { it.firedAt }
        if (last != null && Duration.between(last, now).toMinutes() < rule.limits.minGapMin) return false
    }
    return true
}

data class SupplementDueItem(val id: Long, val name: String, val dose: String)

// Items that belong in the `bucket` reminder: active (NOT the 7-day recently-ended grace isSupplementActive allows --
// never remind for an ended supplement), with `bucket` among its times of day, due today by its schedule
// (domain/Regimen.kt: every N days, weekdays, on-cycle), and (when skipIfLogged) this slot not yet taken: an item taken
// twice a day has its morning slot done after one logged dose and its night slot after two. [takesToday] is the number of
// doses logged today per item; without it, a take dated today counts as one.
fun supplementsDueForBucket(
    supplements: List<SupplementRow>,
    bucket: String,
    lastTaken: Map<Long, LocalDate>,
    takenToday: Set<Long>,
    today: LocalDate,
    skipIfLogged: Boolean,
    takesToday: Map<Long, Int> = emptyMap(),
): List<SupplementDueItem> =
    supplements
        .filter { it.status.equals("active", ignoreCase = true) }
        .filter { it.endDate == null || LocalDate.parse(it.endDate) >= today }
        .mapNotNull { row ->
            val spec = row.spec()
            val slot = spec.slots.indexOfFirst { it.equals(bucket, ignoreCase = true) }
            if (slot < 0 || !spec.isDueOn(today, lastTaken[row.id])) return@mapNotNull null
            val takes = takesToday[row.id] ?: if (row.id in takenToday) 1 else 0
            if (skipIfLogged && takes > slot) return@mapNotNull null
            SupplementDueItem(row.id, row.name, spec.doseTextOn(today))
        }

// True when the check-in still has something to ask. Mirrors the web cron's
// "skip if today's row is already filled" test: morning asks about morning
// wood, evening about arousal.
// Analysis Layer 3 / R2. The wellbeing check-in asks for energy, mood, stress and soreness; any score already logged today
// means the person has done their check-in, so it is skipped (same "skip if logged" rule as the other kinds).
fun wellbeingCheckinNeeded(energy: Int?, mood: Int?, stress: Int?, soreness: Int?): Boolean =
    listOf(energy, mood, stress, soreness).all { it == null }

// The evening log nudges the one-message "say it" entry; a message already saved today means it is done.
fun eveningLogNeeded(savedMessagesToday: Int): Boolean = savedMessagesToday <= 0

fun checkinNeeded(kind: String, morningWood: Int?, arousal: Int?): Boolean =
    when (kind) {
        NotificationKind.CHECKIN_MORNING -> morningWood == null
        NotificationKind.CHECKIN_EVENING -> arousal == null
        else -> false
    }

/** A notification_log.fired_at (a real instant, set by the database) as the local time in [zone]. */
fun firedAtLocal(firedAt: String, zone: java.time.ZoneId): LocalDateTime? {
    val text = firedAt.trim().replace(' ', 'T')
    return runCatching { java.time.OffsetDateTime.parse(text).atZoneSameInstant(zone).toLocalDateTime() }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(text.replace(Regex("([+-]\\d{2})$"), "$1:00")).atZoneSameInstant(zone).toLocalDateTime() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(text.take(19)) }.getOrNull()
}
