package com.bioscan.fieldterminal.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bioscan.fieldterminal.data.model.NotificationKind
import com.bioscan.fieldterminal.data.model.NotificationOutcome
import com.bioscan.fieldterminal.domain.notifications.ShownEntry
import com.bioscan.fieldterminal.domain.bodyCheckinNeeded
import com.bioscan.fieldterminal.domain.checkinDateFor
import com.bioscan.fieldterminal.domain.eveningCheckinNeeded
import com.bioscan.fieldterminal.domain.notifications.eveningLogNeeded
import com.bioscan.fieldterminal.domain.notifications.wellbeingCheckinNeeded
import com.bioscan.fieldterminal.domain.notifications.nextFireTime
import com.bioscan.fieldterminal.domain.notifications.supplementsDueForBucket
import com.bioscan.fieldterminal.domain.notifications.withinLimits
import com.bioscan.fieldterminal.domain.notifications.firedAtLocal
import com.bioscan.fieldterminal.domain.notifications.FUEL_PROMPT_WINDOW_MIN
import com.bioscan.fieldterminal.domain.notifications.dueFuelPrompts
import com.bioscan.fieldterminal.domain.notifications.fuelPrompts
import com.bioscan.fieldterminal.data.model.EventRow
import com.bioscan.fieldterminal.domain.notifications.PromptEvent
import com.bioscan.fieldterminal.domain.notifications.PromptWindow
import com.bioscan.fieldterminal.domain.notifications.eventDrawNeeded
import com.bioscan.fieldterminal.domain.notifications.followUpsDue
import com.bioscan.fieldterminal.domain.notifications.interventionsToDraw
import com.bioscan.fieldterminal.domain.notifications.unratedEvents
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

// Runs every 15 minutes (WorkManager minimum). For each enabled rule, checks
// whether nextFireTime() falls in the current 15-min window and posts a
// NotificationCompat notification if withinLimits() passes. Pattern mirrors
// ZeppSyncWorker / HealthConnectSyncWorker.
class NotificationDispatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return Result.success()

        val supabase = SupabaseClientProvider.client
        val repo = NotificationRulesRepository(supabase)

        val rules = catching { repo.loadRules() }.getOrElse { return Result.retry() }
        val prefs = catching { repo.loadPrefs() }.getOrElse { return Result.retry() }
            ?: return Result.success()

        val now = LocalDateTime.now()
        val windowStart = now.minusMinutes(15)
        val today = LocalDate.now()
        // notification_log.fired_at is a real instant (now() on insert), not a logged wall-clock time: today starts at local
        // midnight, and each firing is read in this zone (its digits were 8 h early in UTC+8, so the min gap never held).
        val zone = java.time.ZoneId.systemDefault()
        val dayStart = today.atStartOfDay(zone).toInstant()

        val shownToday = catching { repo.loadShownSince(dayStart) }
            .getOrElse { emptyList() }
            .mapNotNull { e -> firedAtLocal(e.firedAt, zone)?.let { ShownEntry(e.ruleKind, it) } }

        val nm = NotificationManagerCompat.from(applicationContext)

        for (rule in rules) {
            if (!rule.enabled) continue
            val fireTime = nextFireTime(rule, prefs, windowStart) ?: continue
            if (fireTime.isAfter(now)) continue
            if (!withinLimits(rule, prefs, now, shownToday)) continue

            var logAllIds: List<Long> = emptyList()
            var supplementBucket: String? = null
            val (title, body) = when (rule.kind) {
                NotificationKind.SUPPLEMENT_DUE -> {
                    // Find which bucket this fire belongs to by matching the scheduled time
                    val bucket = rule.schedule.buckets.entries
                        .minByOrNull { (_, t) ->
                            val bt = java.time.LocalTime.parse(t)
                            Math.abs(java.time.Duration.between(bt, fireTime.toLocalTime()).toMinutes())
                        }?.key ?: continue

                    val suppRepo = SupplementsRepository(supabase)
                    val overview = catching { suppRepo.loadOverview() }.getOrElse { continue }
                    val activeIds = overview.active.mapNotNull { it.id }.toList()
                    val takes = catching { suppRepo.loadRecentTakes(activeIds, today) }.getOrElse { com.bioscan.fieldterminal.data.RecentTakes(emptyMap(), emptyMap()) }
                    val lastTaken = takes.lastTaken
                    val takenToday = lastTaken.entries
                        .filter { (_, d) -> d == today }
                        .map { it.key }
                        .toSet()

                    val due = supplementsDueForBucket(
                        overview.active,
                        bucket,
                        lastTaken,
                        takenToday,
                        today,
                        rule.conditions.skipIfLogged,
                        takes.takesToday,
                    )
                    if (due.isEmpty()) continue
                    logAllIds = due.map { it.id }
                    supplementBucket = bucket
                    val names = due.take(3).joinToString(", ") { it.name }
                    val more = if (due.size > 3) " +${due.size - 3} more" else ""
                    "Time for your $bucket supplements" to "$names$more"
                }

                NotificationKind.CHECKIN_MORNING ->
                    "Morning check-in" to "Log your morning readiness"

                NotificationKind.CHECKIN_EVENING ->
                    "Evening note" to "Log how your day went"

                NotificationKind.WELLBEING_CHECKIN -> {
                    // A failed lookup must not suppress or invent a prompt: skip this run and try again at the next window.
                    val row = catching { repo.loadWellbeingForDate(today.toString()) }.getOrElse { continue }
                    if (rule.conditions.skipIfLogged && !wellbeingCheckinNeeded(row?.energy, row?.mood, row?.stress, row?.soreness)) continue
                    "Wellbeing check-in" to "Energy, mood, stress, soreness: 0-10"
                }

                NotificationKind.EVENING_LOG -> {
                    val startOfLocalDay = today.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()
                    val saved = catching { repo.countSavedAgentMessagesSince(startOfLocalDay) }.getOrElse { continue }
                    if (rule.conditions.skipIfLogged && !eveningLogNeeded(saved)) continue
                    "Anything to log today?" to "Drinks, supplements, recovery: say it in one message"
                }

                // Analysis review phase B: the structured check-in. A failed lookup skips this window rather than nagging.
                NotificationKind.EVENING_CHECKIN -> {
                    val saved = catching { EveningCheckinRepository(supabase).load(checkinDateFor(now)) }.getOrElse { continue }
                    if (rule.conditions.skipIfLogged && !eveningCheckinNeeded(saved)) continue
                    "Evening check-in" to "Drinks, caffeine after 14:00, a late meal: one tap each"
                }

                // The monthly body check-in: a failed lookup skips this window rather than nagging.
                NotificationKind.BODY_CHECKIN -> {
                    val last = catching { BodyCheckinRepository(supabase).lastCheckinDate() }.getOrElse { continue }
                    if (rule.conditions.skipIfLogged && !bodyCheckinNeeded(last, today)) continue
                    "Monthly body check-in" to "Grip, tape and skinfolds: before breakfast, about 15 minutes"
                }

                // Health concerns: a failed lookup skips this window rather than nagging.
                NotificationKind.HEALTH_CHECKIN -> {
                    val last = catching { HealthConcernsRepository(supabase).lastCheckinDate() }.getOrElse { continue }
                    if (rule.conditions.skipIfLogged && !com.bioscan.fieldterminal.domain.health.healthCheckinNeeded(last, today)) continue
                    "Health check-in" to "Anything bothering you today? NOTHING is one tap, and it counts."
                }

                // Repeatable interventions: each branch fetches, the decision is in domain/notifications/EventPrompts.kt. A read that
                // returns nothing (offline, failed) means no prompt this window rather than a wrong one.
                NotificationKind.EVENT_RATING -> {
                    val events = promptEvents(InterventionEventsRepository(supabase).loadRecentEvents())
                    val due = unratedEvents(events, java.time.Instant.now())
                    if (due.isEmpty()) continue
                    "How did it feel?" to "${due.size} ${if (due.size == 1) "event" else "events"} to rate, before you look at any numbers"
                }

                NotificationKind.EVENT_DRAW -> {
                    val ev = InterventionEventsRepository(supabase)
                    val enabled = ev.loadInterventions().filter { it.drawEnabled }.map { it.id }
                    if (enabled.isEmpty()) continue
                    val drawnToday = ev.loadDraws().filter { it.localDate == today.toString() }.map { it.interventionId }.toSet()
                    if (!eventDrawNeeded(enabled, drawnToday)) continue
                    "Today's draw" to "Flip the coin for ${interventionsToDraw(enabled, drawnToday).size} intervention(s): it decides before the day starts"
                }

                NotificationKind.EVENT_FOLLOWUP -> {
                    val ev = InterventionEventsRepository(supabase)
                    val windows = ev.loadExpectations().filter { it.outcomeKind == "tracked" && it.role == "primary" }
                        .map { PromptWindow(it.interventionId, it.outcomeKey, it.windowStartH, it.windowEndH) }
                    if (windows.isEmpty()) continue
                    val values = ev.loadRecentTrackedValueTimes().mapNotNull { v -> parseInstant(v.observedAt)?.let { v.outcomeId to it } }
                    val due = followUpsDue(promptEvents(ev.loadRecentEvents()), windows, values, java.time.Instant.now())
                    if (due.isEmpty()) continue
                    "Log the outcome" to "${due.size} ${if (due.size == 1) "event has" else "events have"} a window that closed with nothing logged"
                }

                else -> continue
            }

            val builder = NotificationCompat.Builder(applicationContext, rule.kind)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(NotificationActionReceiver.contentIntent(applicationContext, rule.kind, supplementBucket))
            if (rule.kind == NotificationKind.SUPPLEMENT_DUE && logAllIds.isNotEmpty()) {
                builder.addAction(
                    0,
                    "LOG ALL",
                    NotificationActionReceiver.logAllIntent(applicationContext, rule.kind, logAllIds),
                )
            }
            val notification = builder.build()

            nm.notify(rule.kind.hashCode(), notification)
            catching { repo.appendLog(rule.kind, NotificationOutcome.SHOWN) }
        }

        // Fuel planning: reminders relative to planned sessions and races, not to clock times.
        val fuelRules = rules.filter { it.enabled && it.kind in NotificationKind.FUEL }.associateBy { it.kind }
        if (fuelRules.isNotEmpty() && prefs.masterEnabled) {
            val days = catching { PlannedDayRepository(supabase).load(today, days = 3) }.getOrNull()
            if (days != null) {
                val shownKeys = catching { repo.loadShownSince(dayStart.minusSeconds(3 * 86_400L)) }.getOrElse { emptyList() }
                    .mapNotNull { it.dedupeKey }.toSet()
                val due = dueFuelPrompts(
                    fuelPrompts(days, NotificationKind.FUEL_SESSION, NotificationKind.FUEL_EVENT),
                    prefs, now.minusMinutes(FUEL_PROMPT_WINDOW_MIN), now, shownKeys,
                )
                val shown = shownToday.toMutableList()
                for (p in due) {
                    val rule = fuelRules[p.kind] ?: continue
                    if (!withinLimits(rule, prefs, now, shown)) continue
                    val n = NotificationCompat.Builder(applicationContext, p.kind)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(p.title)
                        .setContentText(p.body)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(p.body))
                        .setPriority(if (p.kind == NotificationKind.FUEL_EVENT) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                        .setAutoCancel(true)
                        .setContentIntent(NotificationActionReceiver.contentIntent(applicationContext, p.kind))
                        .build()
                    nm.notify(p.key.hashCode(), n)
                    catching { repo.appendLog(p.kind, NotificationOutcome.SHOWN, dedupeKey = p.key) }
                    shown += ShownEntry(p.kind, now)
                }
            }
        }

        // Practices (domain/routine/Practices.kt): each with its reminder on, at its own time, once a day, until it is logged.
        // The window is 30 min, wider than the run's 15, because a late run must not miss one; the key keeps it to one.
        val practiceRule = rules.firstOrNull { it.enabled && it.kind == NotificationKind.PRACTICE_DUE }
        if (practiceRule != null && prefs.masterEnabled) {
            catching {
                val practices = com.bioscan.fieldterminal.data.routine.PracticesRepository(supabase)
                val due = com.bioscan.fieldterminal.domain.routine.practicesDue(practices.activities(), practices.logsSince(today), now.minusMinutes(30), now)
                if (due.isNotEmpty()) {
                    val shownKeys = catching { repo.loadShownSince(dayStart) }.getOrElse { emptyList() }.mapNotNull { it.dedupeKey }.toSet()
                    val shown = shownToday.toMutableList()
                    for (a in due) {
                        val key = com.bioscan.fieldterminal.domain.routine.practiceReminderKey(a.id, today)
                        if (key in shownKeys || !withinLimits(practiceRule, prefs, now, shown)) continue
                        val R = com.bioscan.fieldterminal.data.routine.RoutineActionReceiver
                        val n = NotificationCompat.Builder(applicationContext, NotificationKind.PRACTICE_DUE)
                            .setSmallIcon(android.R.drawable.ic_dialog_info)
                            .setContentTitle(a.name)
                            .setContentText(a.targetMin?.let { "$it min, now" } ?: "Now")
                            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                            .setAutoCancel(true)
                            .setContentIntent(NotificationActionReceiver.contentIntent(applicationContext, NotificationKind.PRACTICE_DUE))
                            .addAction(0, "DONE", R.intent(applicationContext, R.ACTION_DONE, a.id, a.name, a.targetMin))
                            .addAction(0, "START", R.intent(applicationContext, R.ACTION_START, a.id, a.name, a.targetMin))
                            .addAction(0, "SKIP", R.intent(applicationContext, R.ACTION_SKIP, a.id, a.name, a.targetMin))
                            .build()
                        nm.notify(R.reminderNotificationId(a.id), n)
                        catching { repo.appendLog(NotificationKind.PRACTICE_DUE, NotificationOutcome.SHOWN, dedupeKey = key) }
                        shown += ShownEntry(NotificationKind.PRACTICE_DUE, now)
                    }
                }
            }
        }

        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "notification_dispatch"

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                // UPDATE (not KEEP) so the network constraint reaches the already-scheduled job: without a connection the rules
                // cannot be read anyway, and a failed run only burned battery on retry backoff.
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<NotificationDispatchWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }
}

// PostgREST returns timestamptz as "2026-10-28T22:00:00+00:00", which Instant.parse rejects; OffsetDateTime reads both forms.
private fun parseInstant(text: String): java.time.Instant? = runCatching { java.time.OffsetDateTime.parse(text).toInstant() }.getOrNull()

private fun promptEvents(rows: List<EventRow>): List<PromptEvent> =
    rows.mapNotNull { r -> parseInstant(r.occurredAt)?.let { PromptEvent(r.id, r.interventionId, it, r.feeling) } }
