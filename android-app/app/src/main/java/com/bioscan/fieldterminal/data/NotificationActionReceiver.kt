package com.bioscan.fieldterminal.data

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bioscan.fieldterminal.MainActivity
import com.bioscan.fieldterminal.data.model.NotificationKind
import com.bioscan.fieldterminal.data.model.NotificationOutcome
import com.bioscan.fieldterminal.domain.notifications.idsStillToLog
import com.bioscan.fieldterminal.domain.toAppIso
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

// Handles the "LOG ALL" action on the supplement reminder. A receiver (not an activity) is used on purpose: Android 12+
// forbids starting an activity from a notification trampoline, and the action should log without opening the app.
class NotificationActionReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission") // only reachable from a notification we already posted, so the permission was granted
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_LOG_ALL) return
        val app = context.applicationContext
        val kind = intent.getStringExtra(EXTRA_KIND) ?: NotificationKind.SUPPLEMENT_DUE
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, kind.hashCode())
        val ids = intent.getLongArrayExtra(EXTRA_SUPPLEMENT_IDS)?.toList().orEmpty()
        val nm = NotificationManagerCompat.from(app)
        // Dismiss first so a second tap on the action can't start a second write.
        nm.cancel(notificationId)

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val supabase = SupabaseClientProvider.client
                val takenToday = SupplementsRepository(supabase).loadRecentTakenDates(ids)
                    .filterValues { it == LocalDate.now() }.keys
                val toLog = idsStillToLog(ids, takenToday)
                val addRepo = AddEntryRepository(supabase)
                val takenAt = LocalDateTime.now().toAppIso()
                for (id in toLog) addRepo.addSupplementTakenById(takenAt, id, 1)
                runCatching { NotificationRulesRepository(supabase).appendLog(kind, NotificationOutcome.ACTIONED, action = "log_all") }
            } catch (e: Exception) {
                val retry = NotificationCompat.Builder(app, kind)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("Couldn't log your supplements")
                    .setContentText("Tap to open the app and log them")
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .setContentIntent(contentIntent(app, kind))
                    .build()
                nm.notify(notificationId, retry)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_LOG_ALL = "com.bioscan.fieldterminal.action.LOG_ALL"
        const val EXTRA_KIND = "notification_kind"
        const val EXTRA_SUPPLEMENT_IDS = "supplement_ids"
        const val EXTRA_NOTIFICATION_ID = "notification_id"

        // Tapping a notification opens MainActivity, which routes by kind (MainActivity.onNewIntent / FieldTerminalNavHost).
        // Request code is per kind so intents for different kinds don't collapse into one PendingIntent.
        fun contentIntent(context: Context, kind: String): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_KIND, kind)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(context, kind.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        fun logAllIntent(context: Context, kind: String, supplementIds: List<Long>): PendingIntent {
            val intent = Intent(context, NotificationActionReceiver::class.java)
                .setAction(ACTION_LOG_ALL)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_SUPPLEMENT_IDS, supplementIds.toLongArray())
                .putExtra(EXTRA_NOTIFICATION_ID, kind.hashCode())
            return PendingIntent.getBroadcast(context, kind.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
