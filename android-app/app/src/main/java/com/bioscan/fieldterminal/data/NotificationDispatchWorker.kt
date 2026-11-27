package com.bioscan.fieldterminal.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import com.bioscan.fieldterminal.R
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bioscan.fieldterminal.data.model.EventRow
import com.bioscan.fieldterminal.data.model.NotificationKind
import com.bioscan.fieldterminal.data.model.NotificationOutcome
import com.bioscan.fieldterminal.data.model.NotificationRuleRow
import io.github.jan.supabase.SupabaseClient
import com.bioscan.fieldterminal.data.routine.PracticesRepository
import com.bioscan.fieldterminal.data.routine.RoutineActionReceiver
import com.bioscan.fieldterminal.domain.bodyCheckinNeeded
import com.bioscan.fieldterminal.domain.risk.dueToday
import com.bioscan.fieldterminal.domain.checkinDateFor
import com.bioscan.fieldterminal.domain.eveningCheckinNeeded
import com.bioscan.fieldterminal.domain.health.healthCheckinNeeded
import com.bioscan.fieldterminal.domain.notifications.FUEL_PROMPT_WINDOW_MIN
import com.bioscan.fieldterminal.domain.notifications.PromptEvent
import com.bioscan.fieldterminal.domain.notifications.PromptWindow
import com.bioscan.fieldterminal.domain.notifications.ShownEntry
import com.bioscan.fieldterminal.domain.notifications.dueFuelPrompts
import com.bioscan.fieldterminal.domain.notifications.eveningLogNeeded
import com.bioscan.fieldterminal.domain.notifications.checkinNeeded
import com.bioscan.fieldterminal.domain.notifications.eventDrawNeeded
import com.bioscan.fieldterminal.domain.notifications.staleFuelKeys
import com.bioscan.fieldterminal.domain.routine.practicesLoggedOn
import com.bioscan.fieldterminal.domain.notifications.firedAtLocal
import com.bioscan.fieldterminal.domain.notifications.followUpsDue
import com.bioscan.fieldterminal.domain.notifications.fuelPrompts
import com.bioscan.fieldterminal.domain.notifications.interventionsToDraw
import com.bioscan.fieldterminal.domain.notifications.nextFireTime
import com.bioscan.fieldterminal.domain.notifications.reportBody
import com.bioscan.fieldterminal.domain.notifications.reportDedupeKey
import com.bioscan.fieldterminal.domain.notifications.reportTitle
import com.bioscan.fieldterminal.domain.notifications.reportToAnnounce
import com.bioscan.fieldterminal.domain.notifications.supplementsDueForBucket
import com.bioscan.fieldterminal.domain.notifications.unratedEvents
import com.bioscan.fieldterminal.domain.notifications.wellbeingCheckinNeeded
import com.bioscan.fieldterminal.domain.notifications.withinLimits
import com.bioscan.fieldterminal.domain.routine.practiceReminderKey
import com.bioscan.fieldterminal.domain.routine.practicesDue
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
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
        val zone = ZoneId.systemDefault()
        val dayStart = today.atStartOfDay(zone).toInstant()

        val shownToday = catching { repo.loadShownSince(dayStart) }
            .getOrElse { emptyList() }
            .mapNotNull { e -> firedAtLocal(e.firedAt, zone)?.let { ShownEntry(e.ruleKind, it) } }

        val nm = NotificationManagerCompat.from(applicationContext)
        catching { withdrawDone(nm, rules, supabase, repo, today, now) }

        for (rule in rules) {
            if (!rule.enabled) continue
            val fireTime = nextFireTime(rule, prefs, windowStart) ?: continue
            if (fireTime.isAfter(now)) continue
            if (!withinLimits(rule, prefs, now, shownToday)) continue

            val prompt = when (val c = check(rule, supabase, repo, today, now, fireTime)) {
                is Check.Needed -> c.prompt
                else -> continue // done already, or the lookup failed: nothing this window
            }
            val title = prompt.title
            val body = prompt.body
            val logAllIds = prompt.logAllIds
            val supplementBucket = prompt.bucket

            val builder = NotificationCompat.Builder(applicationContext, rule.kind)
                .setSmallIcon(R.drawable.ic_stat_bioscan)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(NotificationActionReceiver.contentIntent(applicationContext, rule.kind, supplementBucket))
            if (rule.kind == NotificationKind.SUPPLEMENT_DUE && logAllIds.isNotEmpty()) {
                builder.addAction(
                    0,
                    "LOG ALL",
                    NotificationActionReceiver.logAllIntent(applicationContext, rule.kind, logAllIds, supplementBucket),
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
                val allFuel = fuelPrompts(days, NotificationKind.FUEL_SESSION, NotificationKind.FUEL_EVENT)
                // A session recorded, moved or deleted since its reminder was shown: the reminder goes with it.
                for (key in staleFuelKeys(shownKeys, allFuel.map { it.first.key }.toSet())) nm.cancel(key.hashCode())
                val due = dueFuelPrompts(
                    allFuel,
                    prefs, now.minusMinutes(FUEL_PROMPT_WINDOW_MIN), now, shownKeys,
                )
                val shown = shownToday.toMutableList()
                for (p in due) {
                    val rule = fuelRules[p.kind] ?: continue
                    if (!withinLimits(rule, prefs, now, shown)) continue
                    val n = NotificationCompat.Builder(applicationContext, p.kind)
                        .setSmallIcon(R.drawable.ic_stat_bioscan)
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

        // Weekly and monthly reports (docs/reports): each new one once, in the day's window, opening its page in the app. It follows the
        // master switch (and a rule row, if one is ever saved off); a failed read just waits for the next run.
        val reportRule = rules.firstOrNull { it.kind == NotificationKind.PERIOD_REPORT }
        if (prefs.masterEnabled && reportRule?.enabled != false) {
            catching {
                val reports = ReportsRepository(supabase).latest()
                val shownKeys = repo.loadShownSince(dayStart.minusSeconds(14 * 86_400L)).mapNotNull { it.dedupeKey }.toSet()
                val r = reportToAnnounce(reports, shownKeys, today, now.toLocalTime())
                if (r != null) {
                    val open = NotificationActionReceiver.contentIntent(applicationContext, NotificationKind.PERIOD_REPORT, bucket = r.key)
                    val body = reportBody(r)
                    val n = NotificationCompat.Builder(applicationContext, NotificationKind.PERIOD_REPORT)
                        .setSmallIcon(R.drawable.ic_stat_bioscan)
                        .setContentTitle(reportTitle(r))
                        .setContentText(body)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setAutoCancel(true)
                        .setContentIntent(open)
                        .build()
                    nm.notify(reportDedupeKey(r.key).hashCode(), n)
                    repo.appendLog(NotificationKind.PERIOD_REPORT, NotificationOutcome.SHOWN, dedupeKey = reportDedupeKey(r.key))
                }
            }
        }

        // Practices (domain/routine/Practices.kt): each with its reminder on, at its own time, once a day, until it is logged.
        // The window is 30 min, wider than the run's 15, because a late run must not miss one; the key keeps it to one.
        val practiceRule = rules.firstOrNull { it.enabled && it.kind == NotificationKind.PRACTICE_DUE }
        if (practiceRule != null && prefs.masterEnabled) {
            catching {
                val practices = PracticesRepository(supabase)
                val activities = practices.activities()
                val logs = practices.logsSince(today)
                // Done or skipped from the app (or the web) after the reminder showed: withdraw it.
                for (id in practicesLoggedOn(logs, today)) nm.cancel(RoutineActionReceiver.reminderNotificationId(id))
                val due = practicesDue(activities, logs, now.minusMinutes(30), now)
                if (due.isNotEmpty()) {
                    val shownKeys = catching { repo.loadShownSince(dayStart) }.getOrElse { emptyList() }.mapNotNull { it.dedupeKey }.toSet()
                    val shown = shownToday.toMutableList()
                    for (a in due) {
                        val key = practiceReminderKey(a.id, today)
                        if (key in shownKeys || !withinLimits(practiceRule, prefs, now, shown)) continue
                        val R = RoutineActionReceiver
                        val n = NotificationCompat.Builder(applicationContext, NotificationKind.PRACTICE_DUE)
                            .setSmallIcon(com.bioscan.fieldterminal.R.drawable.ic_stat_bioscan)
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


    // What the person still has to do for [rule], read from the data at the moment of asking (never from the schedule alone).
    // [fireTime] is null when sweeping already-posted notifications: a kind that needs the slot (supplements) is then Unknown.
    // A failed or empty read is Unknown, which neither posts nor cancels anything.
    private suspend fun check(
        rule: NotificationRuleRow, supabase: SupabaseClient, repo: NotificationRulesRepository,
        today: LocalDate, now: LocalDateTime, fireTime: LocalDateTime?,
    ): Check {
        val skip = rule.conditions.skipIfLogged
        return when (rule.kind) {
            NotificationKind.SUPPLEMENT_DUE -> {
                if (fireTime == null) return Check.Unknown
                val bucket = rule.schedule.buckets.entries
                    .minByOrNull { (_, t) -> Math.abs(Duration.between(LocalTime.parse(t), fireTime.toLocalTime()).toMinutes()) }?.key
                    ?: return Check.Unknown
                val suppRepo = SupplementsRepository(supabase)
                val overview = catching { suppRepo.loadOverview() }.getOrElse { return Check.Unknown }
                val activeIds = overview.active.mapNotNull { it.id }.toList()
                val takes = catching { suppRepo.loadRecentTakes(activeIds, today) }.getOrElse { return Check.Unknown }
                val takenToday = takes.lastTaken.entries.filter { (_, d) -> d == today }.map { it.key }.toSet()
                val due = supplementsDueForBucket(overview.active, bucket, takes.lastTaken, takenToday, today, skip, takes.takesToday)
                if (due.isEmpty()) return Check.Done
                val names = due.take(3).joinToString(", ") { it.name }
                val more = if (due.size > 3) " +${due.size - 3} more" else ""
                Check.Needed(Prompt("Time for your $bucket supplements", "$names$more", due.map { it.id }, bucket))
            }

            // The morning and evening check-ins ask about the morning erection and the arousal level, both stored on today's
            // wellbeing_daily row: once that value is there the check-in is done (checkinNeeded, same rule as the web cron).
            NotificationKind.CHECKIN_MORNING, NotificationKind.CHECKIN_EVENING -> {
                val row = catching { repo.loadWellbeingForDate(today.toString()) }.getOrElse { return Check.Unknown }
                if (skip && !checkinNeeded(rule.kind, row?.morningErectionQuality, row?.arousalLevel)) return Check.Done
                if (rule.kind == NotificationKind.CHECKIN_MORNING) Check.Needed(Prompt("Morning check-in", "Log your morning readiness"))
                else Check.Needed(Prompt("Evening note", "Log how your day went"))
            }

            NotificationKind.WELLBEING_CHECKIN -> {
                val row = catching { repo.loadWellbeingForDate(today.toString()) }.getOrElse { return Check.Unknown }
                if (skip && !wellbeingCheckinNeeded(row?.energy, row?.mood, row?.stress, row?.soreness)) return Check.Done
                Check.Needed(Prompt("Wellbeing check-in", "Energy, mood, stress, soreness: 0-10"))
            }

            NotificationKind.EVENING_LOG -> {
                val startOfLocalDay = today.atStartOfDay(ZoneId.systemDefault()).toInstant()
                val saved = catching { repo.countSavedAgentMessagesSince(startOfLocalDay) }.getOrElse { return Check.Unknown }
                if (skip && !eveningLogNeeded(saved)) return Check.Done
                Check.Needed(Prompt("Anything to log today?", "Drinks, supplements, recovery: say it in one message"))
            }

            NotificationKind.EVENING_CHECKIN -> {
                val saved = catching { EveningCheckinRepository(supabase).load(checkinDateFor(now)) }.getOrElse { return Check.Unknown }
                if (skip && !eveningCheckinNeeded(saved)) return Check.Done
                Check.Needed(Prompt("Evening check-in", "Drinks, caffeine after 14:00, a late meal: one tap each"))
            }

            NotificationKind.BODY_CHECKIN -> {
                val last = catching { BodyCheckinRepository(supabase).lastCheckinDate() }.getOrElse { return Check.Unknown }
                if (skip && !bodyCheckinNeeded(last, today)) return Check.Done
                Check.Needed(Prompt("Monthly body check-in", "Grip, tape and skinfolds: before breakfast, about 15 minutes"))
            }

            NotificationKind.QUESTIONNAIRE_DUE -> {
                val last = catching { InstrumentsRepository(supabase).lastAnswered() }.getOrElse { return Check.Unknown }
                val due = InstrumentsRepository.definitions(applicationContext).dueToday(last, today)
                if (due.isEmpty()) return Check.Done
                Check.Needed(Prompt("Questionnaires due", due.joinToString(", ") { it.name.lowercase() }.replaceFirstChar { it.uppercase() } + ": a few minutes, on Me › EXPOSURES"))
            }

            NotificationKind.HEALTH_CHECKIN -> {
                val last = catching { HealthConcernsRepository(supabase).lastCheckinDate() }.getOrElse { return Check.Unknown }
                if (skip && !healthCheckinNeeded(last, today)) return Check.Done
                Check.Needed(Prompt("Health check-in", "Anything bothering you today? NOTHING is one tap, and it counts."))
            }

            // Repeatable interventions: the decision is in domain/notifications/EventPrompts.kt.
            NotificationKind.EVENT_RATING -> {
                val events = catching { promptEvents(InterventionEventsRepository(supabase).loadRecentEvents()) }.getOrElse { return Check.Unknown }
                val due = unratedEvents(events, Instant.now())
                if (due.isEmpty()) return Check.Done
                Check.Needed(Prompt("How did it feel?", "${due.size} ${if (due.size == 1) "event" else "events"} to rate, before you look at any numbers"))
            }

            NotificationKind.EVENT_DRAW -> {
                val ev = InterventionEventsRepository(supabase)
                val enabled = catching { ev.loadInterventions().filter { it.drawEnabled }.map { it.id } }.getOrElse { return Check.Unknown }
                if (enabled.isEmpty()) return Check.Done
                val drawnToday = catching { ev.loadDraws().filter { it.localDate == today.toString() }.map { it.interventionId }.toSet() }.getOrElse { return Check.Unknown }
                if (!eventDrawNeeded(enabled, drawnToday)) return Check.Done
                Check.Needed(Prompt("Today's draw", "Flip the coin for ${interventionsToDraw(enabled, drawnToday).size} intervention(s): it decides before the day starts"))
            }

            NotificationKind.EVENT_FOLLOWUP -> {
                val ev = InterventionEventsRepository(supabase)
                val due = catching {
                    val windows = ev.loadExpectations().filter { it.outcomeKind == "tracked" && it.role == "primary" }
                        .map { PromptWindow(it.interventionId, it.outcomeKey, it.windowStartH, it.windowEndH) }
                    val values = ev.loadRecentTrackedValueTimes().mapNotNull { v -> parseInstant(v.observedAt)?.let { v.outcomeId to it } }
                    followUpsDue(promptEvents(ev.loadRecentEvents()), windows, values, Instant.now())
                }.getOrElse { return Check.Unknown }
                if (due.isEmpty()) return Check.Done
                Check.Needed(Prompt("Log the outcome", "${due.size} ${if (due.size == 1) "event has" else "events have"} a window that closed with nothing logged"))
            }

            else -> Check.Unknown
        }
    }

    // A posted reminder outlives its reason: logged from the app, from another phone or the web, it would still sit in the
    // shade. Each run withdraws the ones whose check now says done. Only notifications still showing are looked up, so a
    // quiet shade costs no requests; a failed lookup withdraws nothing.
    private suspend fun withdrawDone(
        nm: NotificationManagerCompat, rules: List<NotificationRuleRow>, supabase: SupabaseClient,
        repo: NotificationRulesRepository, today: LocalDate, now: LocalDateTime,
    ) {
        val showing = nm.activeNotifications.map { it.id }.toSet()
        if (showing.isEmpty()) return
        for (rule in rules) {
            if (!rule.conditions.skipIfLogged && rule.kind != NotificationKind.EVENT_RATING && rule.kind != NotificationKind.EVENT_FOLLOWUP) continue
            if (rule.kind.hashCode() !in showing) continue
            if (check(rule, supabase, repo, today, now, null) is Check.Done) nm.cancel(rule.kind.hashCode())
        }
    }

    private class Prompt(val title: String, val body: String, val logAllIds: List<Long> = emptyList(), val bucket: String? = null)

    private sealed interface Check {
        class Needed(val prompt: Prompt) : Check
        data object Done : Check // read fine, nothing left to do
        data object Unknown : Check // read failed, or not decidable here: do nothing
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
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }
}

// PostgREST returns timestamptz as "2026-10-28T22:00:00+00:00", which Instant.parse rejects; OffsetDateTime reads both forms.
private fun parseInstant(text: String): Instant? = runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()

private fun promptEvents(rows: List<EventRow>): List<PromptEvent> =
    rows.mapNotNull { r -> parseInstant(r.occurredAt)?.let { PromptEvent(r.id, r.interventionId, it, r.feeling) } }
