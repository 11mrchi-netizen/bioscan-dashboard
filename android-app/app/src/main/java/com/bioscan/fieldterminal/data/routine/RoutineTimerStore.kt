package com.bioscan.fieldterminal.data.routine

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bioscan.fieldterminal.data.NotificationActionReceiver
import com.bioscan.fieldterminal.data.model.NotificationKind
import com.bioscan.fieldterminal.domain.routine.LogSource
import com.bioscan.fieldterminal.domain.routine.LogStatus
import com.bioscan.fieldterminal.domain.routine.blockMinutes
import com.bioscan.fieldterminal.domain.routine.dayOfBlock
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

// The one running routine timer (TV, reading, a practice started from its reminder). Kept on the phone, not on the server:
// nothing is saved until it stops, so a timer left running is discarded or stopped, never half-written. While it runs an
// ongoing, silent notification shows what is timed and carries STOP (data/routine/RoutineActionReceiver.kt).

data class RunningTimer(val activityId: Long, val name: String, val start: LocalDateTime, val clientKey: String)

object RoutineTimerStore {
    const val CHANNEL = "routine_timer"
    private const val PREFS = "routine_timer"
    private const val NOTIFICATION_ID = 0x7151

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(context: Context): RunningTimer? {
        val p = prefs(context)
        val id = p.getLong("activity_id", -1).takeIf { it >= 0 } ?: return null
        val start = p.getString("start", null)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return null
        return RunningTimer(id, p.getString("name", null) ?: "Timer", start, p.getString("key", null) ?: UUID.randomUUID().toString())
    }

    /** Starts timing [name]; a timer already running is stopped and saved first. */
    suspend fun start(context: Context, activityId: Long, name: String, now: LocalDateTime = LocalDateTime.now()) {
        if (current(context) != null) stop(context, now)
        prefs(context).edit().putLong("activity_id", activityId).putString("name", name).putString("start", now.toString())
            .putString("key", "timer:" + UUID.randomUUID().toString()).apply()
        showOngoing(context)
    }

    /** Stops the timer and saves its block (under a minute is dropped). Returns the minutes saved, or null. */
    suspend fun stop(context: Context, now: LocalDateTime = LocalDateTime.now()): Double? {
        val t = current(context) ?: return null
        val minutes = blockMinutes(t.start, now)?.coerceAtMost(1440.0)
        if (minutes != null && minutes >= 1) {
            PracticesRepository(com.bioscan.fieldterminal.data.SupabaseClientProvider.client).saveLog(
                NewLog(t.activityId, dayOfBlock(t.start), t.start, now, minutes, LogStatus.DONE, LogSource.TIMER, null, t.clientKey),
            )
        }
        clear(context)
        return minutes?.takeIf { it >= 1 }
    }

    /** Throws the running timer away without saving. */
    fun discard(context: Context) = clear(context)

    private fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    @SuppressLint("MissingPermission") // checked just below
    fun showOngoing(context: Context) {
        val t = current(context) ?: return
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val since = t.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(t.name)
            .setContentText("Timing since %02d:%02d".format(t.start.hour, t.start.minute))
            .setWhen(since).setUsesChronometer(true).setShowWhen(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(NotificationActionReceiver.contentIntent(context, NotificationKind.PRACTICE_DUE))
            .addAction(0, "STOP", RoutineActionReceiver.intent(context, RoutineActionReceiver.ACTION_TIMER_STOP, t.activityId))
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
    }
}
