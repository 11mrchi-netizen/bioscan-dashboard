package com.bioscan.fieldterminal.domain.routine

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

// Routine intelligence R1 (docs/routine-intelligence/01-research-and-plan.md, section 3.2): one local day of phone use from
// Android's usage events. Pure; data/PhoneUsageCollector.kt maps UsageEvents.Event onto these.

enum class UsageEventType { SCREEN_ON, SCREEN_OFF, UNLOCK, APP_RESUMED, APP_PAUSED }

data class UsageEvent(val ts: Long, val type: UsageEventType, val pkg: String? = null)

// The night that follows the day: sleep_daily's row for date + 1 (keyed to the morning the night ends on).
data class SleepWindow(val onset: Instant, val wake: Instant)

data class AppDayUsage(
    val pkg: String,
    val foregroundMin: Double,
    val launches: Int,
    val bedWindowMin: Double?,
    val after21Min: Double,
)

data class PhoneUsageDay(
    val date: LocalDate,
    val screenMin: Double,
    val unlocks: Int,
    val firstUnlockAt: Instant?,
    val lastUseBeforeSleepAt: Instant?,
    val bedWindowMin: Double?,
    val nightScreenMin: Double?,
    val nightUnlocks: Int?,
    val longestFreeMin: Double?,
    val apps: List<AppDayUsage>,
)

// A local day runs 04:00 to 04:00, like the evening check-in (domain/EveningCheckin.kt checkinDateFor).
const val PHONE_DAY_START_HOUR = 4
private const val BED_WINDOW_MIN = 60L
private const val MS_PER_MIN = 60_000.0

fun phoneDayStart(date: LocalDate, zone: ZoneId): Instant =
    date.atTime(LocalTime.of(PHONE_DAY_START_HOUR, 0)).atZone(zone).toInstant()

// The span to read events for: the day itself, plus the rest of the night until a late wake.
fun phoneQueryRange(date: LocalDate, zone: ZoneId, night: SleepWindow?): LongRange {
    val start = phoneDayStart(date, zone).toEpochMilli()
    val dayEnd = phoneDayStart(date.plusDays(1), zone).toEpochMilli()
    val end = maxOf(dayEnd, night?.wake?.toEpochMilli() ?: dayEnd)
    return start until end
}

internal data class Span(val start: Long, val end: Long) {
    val length get() = (end - start).coerceAtLeast(0)
    fun overlap(from: Long, to: Long): Long = (minOf(end, to) - maxOf(start, from)).coerceAtLeast(0)
}

internal fun List<Span>.overlapMin(from: Long, to: Long): Double = sumOf { it.overlap(from, to) } / MS_PER_MIN

// Screen-on spans from screen on/off events. A first SCREEN_OFF with no SCREEN_ON before it means the screen was already on
// when the range began; an unclosed SCREEN_ON is closed at the range's end (or at the last event, if earlier is not known).
internal fun screenSpans(events: List<UsageEvent>, rangeStart: Long, rangeEnd: Long): List<Span> {
    val spans = mutableListOf<Span>()
    var onSince: Long? = null
    var seenAny = false
    for (e in events) {
        when (e.type) {
            UsageEventType.SCREEN_ON -> {
                if (onSince == null) onSince = e.ts
                seenAny = true
            }
            UsageEventType.SCREEN_OFF -> {
                val from = onSince ?: if (!seenAny) rangeStart else null
                if (from != null && e.ts > from) spans += Span(from, e.ts)
                onSince = null
                seenAny = true
            }
            else -> Unit
        }
    }
    onSince?.let { if (rangeEnd > it) spans += Span(it, rangeEnd) }
    return spans
}

// Foreground spans per app. An app is in front from its resume to its pause, or until the screen goes off or another
// app resumes. A launch is a resume that does not follow the same app (moving between one app's own screens is not one).
internal data class AppSpans(val spans: List<Span>, val launches: List<Long>)

internal fun appSpans(events: List<UsageEvent>, rangeEnd: Long, excluded: Set<String>): Map<String, AppSpans> {
    val spans = mutableMapOf<String, MutableList<Span>>()
    val launches = mutableMapOf<String, MutableList<Long>>()
    var current: String? = null
    var since = 0L
    var lastInFront: String? = null
    fun close(at: Long) {
        val pkg = current ?: return
        if (at > since) spans.getOrPut(pkg) { mutableListOf() } += Span(since, at)
        current = null
    }
    for (e in events) {
        when (e.type) {
            UsageEventType.APP_RESUMED -> {
                val pkg = e.pkg ?: continue
                if (pkg == current) continue
                close(e.ts)
                if (pkg != lastInFront) launches.getOrPut(pkg) { mutableListOf() } += e.ts
                current = pkg
                since = e.ts
                lastInFront = pkg
            }
            UsageEventType.APP_PAUSED -> if (e.pkg == current) close(e.ts)
            UsageEventType.SCREEN_OFF -> {
                close(e.ts)
                lastInFront = null
            }
            else -> Unit
        }
    }
    close(rangeEnd)
    return (spans.keys + launches.keys)
        .filter { it !in excluded }
        .associateWith { AppSpans(spans[it].orEmpty(), launches[it].orEmpty()) }
}

private fun longestGapMin(spans: List<Span>, from: Long, to: Long): Double {
    var cursor = from
    var longest = 0L
    for (s in spans.sortedBy { it.start }) {
        if (s.end <= from) continue
        if (s.start >= to) break
        longest = maxOf(longest, s.start - cursor)
        cursor = maxOf(cursor, s.end)
    }
    longest = maxOf(longest, to - cursor)
    return longest.coerceAtLeast(0) / MS_PER_MIN
}

// wakeOfDay: the wake time that starts this day (sleep_daily for `date`), so the first unlock is the first after waking.
fun summarisePhoneDay(
    date: LocalDate,
    zone: ZoneId,
    events: List<UsageEvent>,
    night: SleepWindow?,
    wakeOfDay: Instant?,
    excludedPackages: Set<String> = emptySet(),
): PhoneUsageDay {
    val sorted = events.sortedBy { it.ts }
    val range = phoneQueryRange(date, zone, night)
    val dayStart = range.first
    val dayEnd = phoneDayStart(date.plusDays(1), zone).toEpochMilli()
    val screens = screenSpans(sorted, range.first, range.last + 1)
    val unlocks = sorted.filter { it.type == UsageEventType.UNLOCK }.map { it.ts }

    val firstUnlockFloor = maxOf(dayStart, wakeOfDay?.toEpochMilli()?.takeIf { it < dayEnd } ?: dayStart)
    val firstUnlock = unlocks.firstOrNull { it in firstUnlockFloor until dayEnd }

    val onset = night?.onset?.toEpochMilli()
    val wake = night?.wake?.toEpochMilli()
    val bedFrom = onset?.minus(BED_WINDOW_MIN * 60_000)
    val lastUse = onset?.let { o -> screens.filter { it.start < o }.maxOfOrNull { minOf(it.end, o) } }

    val at = { h: Int -> date.atTime(h, 0).atZone(zone).toInstant().toEpochMilli() }
    val apps = appSpans(sorted, range.last + 1, excludedPackages).map { (pkg, a) ->
        AppDayUsage(
            pkg = pkg,
            foregroundMin = a.spans.overlapMin(dayStart, dayEnd),
            launches = a.launches.count { it in dayStart until dayEnd },
            bedWindowMin = if (bedFrom != null && wake != null) a.spans.overlapMin(bedFrom, wake) else null,
            after21Min = a.spans.overlapMin(at(21), dayEnd),
        )
    }.filter { it.foregroundMin > 0 || it.launches > 0 || (it.bedWindowMin ?: 0.0) > 0 }
        .sortedByDescending { it.foregroundMin }

    return PhoneUsageDay(
        date = date,
        screenMin = screens.overlapMin(dayStart, dayEnd),
        unlocks = unlocks.count { it in dayStart until dayEnd },
        firstUnlockAt = firstUnlock?.let(Instant::ofEpochMilli),
        lastUseBeforeSleepAt = lastUse?.let(Instant::ofEpochMilli),
        bedWindowMin = if (onset != null && bedFrom != null) screens.overlapMin(bedFrom, onset) else null,
        nightScreenMin = if (onset != null && wake != null) screens.overlapMin(onset, wake) else null,
        nightUnlocks = if (onset != null && wake != null) unlocks.count { it in onset until wake } else null,
        longestFreeMin = longestGapMin(screens, at(8), at(22)),
        apps = apps,
    )
}

// Minutes per category for the day; an app without a category counts as "other".
fun minutesByCategory(apps: List<AppDayUsage>, categoryOf: (String) -> String): Map<String, Double> =
    apps.groupBy { categoryOf(it.pkg) }.mapValues { (_, a) -> a.sumOf { it.foregroundMin } }.filterValues { it > 0 }
