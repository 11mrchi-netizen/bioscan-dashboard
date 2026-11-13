package com.bioscan.fieldterminal.data.routine

import com.bioscan.fieldterminal.data.catching
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.bioscan.fieldterminal.data.NotificationRulesRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.NotificationKind
import com.bioscan.fieldterminal.data.model.NotificationOutcome
import com.bioscan.fieldterminal.domain.routine.LogSource
import com.bioscan.fieldterminal.domain.routine.LogStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

// The practice reminder's actions and the routine timer's STOP, answered without opening the app (a receiver, as the
// supplement LOG ALL: Android 12+ forbids an activity trampoline from a notification).
//   DONE   logs the practice for today at its planned length (one per practice and day: the key is the reminder's);
//   START  starts the timer for it (data/routine/RoutineTimerStore.kt);
//   SKIP   logs it as skipped today, so adherence knows it was a choice;
//   STOP   stops the running timer and saves the block.
class RoutineActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val action = intent.action ?: return
        val activityId = intent.getLongExtra(EXTRA_ACTIVITY, -1)
        val name = intent.getStringExtra(EXTRA_NAME) ?: "Practice"
        val minutes = intent.getIntExtra(EXTRA_MINUTES, 0).takeIf { it > 0 }
        if (action != ACTION_TIMER_STOP) NotificationManagerCompat.from(app).cancel(reminderNotificationId(activityId))
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = PracticesRepository(SupabaseClientProvider.client)
                val today = LocalDate.now()
                val key = "reminder:$activityId:$today"
                when (action) {
                    ACTION_DONE -> repo.saveLog(NewLog(activityId, today, null, null, minutes?.toDouble(), LogStatus.DONE, LogSource.REMINDER, null, key))
                    ACTION_SKIP -> repo.saveLog(NewLog(activityId, today, null, null, null, LogStatus.SKIPPED, LogSource.REMINDER, null, key))
                    ACTION_START -> RoutineTimerStore.start(app, activityId, name, LocalDateTime.now())
                    ACTION_TIMER_STOP -> RoutineTimerStore.stop(app)
                }
                if (action != ACTION_TIMER_STOP) {
                    catching { NotificationRulesRepository(SupabaseClientProvider.client).appendLog(NotificationKind.PRACTICE_DUE, NotificationOutcome.ACTIONED, action = action.substringAfterLast('.').lowercase()) }
                }
            } catch (e: Exception) {
                // Nothing to show: the practice stays due on Lab › ROUTINE, and a timer stays running there.
                if (action == ACTION_TIMER_STOP) RoutineTimerStore.showOngoing(app)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DONE = "com.bioscan.fieldterminal.action.PRACTICE_DONE"
        const val ACTION_SKIP = "com.bioscan.fieldterminal.action.PRACTICE_SKIP"
        const val ACTION_START = "com.bioscan.fieldterminal.action.PRACTICE_START"
        const val ACTION_TIMER_STOP = "com.bioscan.fieldterminal.action.ROUTINE_TIMER_STOP"
        private const val EXTRA_ACTIVITY = "activity_id"
        private const val EXTRA_NAME = "activity_name"
        private const val EXTRA_MINUTES = "target_min"

        fun reminderNotificationId(activityId: Long): Int = ("practice:$activityId").hashCode()

        fun intent(context: Context, action: String, activityId: Long, name: String? = null, targetMin: Int? = null): PendingIntent {
            val i = Intent(context, RoutineActionReceiver::class.java).setAction(action)
                .putExtra(EXTRA_ACTIVITY, activityId)
                .apply { if (name != null) putExtra(EXTRA_NAME, name); if (targetMin != null) putExtra(EXTRA_MINUTES, targetMin) }
            return PendingIntent.getBroadcast(context, (action + activityId).hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
