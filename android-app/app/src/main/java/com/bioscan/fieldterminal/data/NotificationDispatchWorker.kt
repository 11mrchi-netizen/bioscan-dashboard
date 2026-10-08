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
import com.bioscan.fieldterminal.domain.notifications.nextFireTime
import com.bioscan.fieldterminal.domain.notifications.supplementsDueForBucket
import com.bioscan.fieldterminal.domain.notifications.withinLimits
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
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

        val rules = runCatching { repo.loadRules() }.getOrElse { return Result.retry() }
        val prefs = runCatching { repo.loadPrefs() }.getOrElse { return Result.retry() }
            ?: return Result.success()

        val now = LocalDateTime.now()
        val windowStart = now.minusMinutes(15)
        val today = LocalDate.now()
        val dayStart = today.atStartOfDay().toInstant(ZoneOffset.UTC)

        val shownToday = runCatching { repo.loadShownSince(dayStart) }
            .getOrElse { emptyList() }
            .map { ShownEntry(it.ruleKind, LocalDateTime.parse(it.firedAt.take(19))) }

        val nm = NotificationManagerCompat.from(applicationContext)

        for (rule in rules) {
            if (!rule.enabled) continue
            val fireTime = nextFireTime(rule, prefs, windowStart) ?: continue
            if (fireTime.isAfter(now)) continue
            if (!withinLimits(rule, prefs, now, shownToday)) continue

            val (title, body) = when (rule.kind) {
                NotificationKind.SUPPLEMENT_DUE -> {
                    // Find which bucket this fire belongs to by matching the scheduled time
                    val bucket = rule.schedule.buckets.entries
                        .minByOrNull { (_, t) ->
                            val bt = java.time.LocalTime.parse(t)
                            Math.abs(java.time.Duration.between(bt, fireTime.toLocalTime()).toMinutes())
                        }?.key ?: continue

                    val suppRepo = SupplementsRepository(supabase)
                    val overview = runCatching { suppRepo.loadOverview() }.getOrElse { continue }
                    val activeIds = overview.active.mapNotNull { it.id }.toList()
                    val lastTaken = runCatching { suppRepo.loadRecentTakenDates(activeIds) }.getOrElse { emptyMap() }
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
                    )
                    if (due.isEmpty()) continue
                    val names = due.take(3).joinToString(", ") { it.name }
                    val more = if (due.size > 3) " +${due.size - 3} more" else ""
                    "Time for your $bucket supplements" to "$names$more"
                }

                NotificationKind.CHECKIN_MORNING ->
                    "Morning check-in" to "Log your morning readiness"

                NotificationKind.CHECKIN_EVENING ->
                    "Evening check-in" to "Log how your day went"

                else -> continue
            }

            val notification = NotificationCompat.Builder(applicationContext, rule.kind)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()

            nm.notify(rule.kind.hashCode(), notification)
            runCatching { repo.appendLog(rule.kind, NotificationOutcome.SHOWN) }
        }

        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "notification_dispatch"

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<NotificationDispatchWorker>(15, TimeUnit.MINUTES).build(),
            )
        }
    }
}
