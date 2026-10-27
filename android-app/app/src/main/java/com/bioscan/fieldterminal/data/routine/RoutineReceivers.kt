package com.bioscan.fieldterminal.data.routine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

// Routine intelligence R2: the broadcasts that drive LocationCollector's state machine. All three are internal
// (exported = false); Play services delivers through our own PendingIntents.

// Activity transitions: every one is recorded; entering a moving activity starts a trip, entering STILL lets one settle.
class RoutineTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        if (!LocationCollector.canRun(context)) return
        val events = ActivityTransitionResult.extractResult(intent)?.transitionEvents.orEmpty()
        // Event times are elapsed-realtime; turn them into wall-clock time.
        val wallMinusElapsed = System.currentTimeMillis() - SystemClock.elapsedRealtimeNanos() / 1_000_000
        for (e in events) {
            val activity = LocationCollector.TRACKED[e.activityType] ?: continue
            val entered = e.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
            RoutineOutbox.addTransition(
                context,
                OutboxTransition(wallMinusElapsed + e.elapsedRealTimeNanos / 1_000_000, activity, if (entered) "enter" else "exit"),
            )
            if (!entered) continue
            if (e.activityType == DetectedActivity.STILL) {
                TripRecordingService.settle(context)
            } else {
                TripRecordingService.start(context, activity)
            }
        }
    }
}

// Leaving the current stay: a trip has begun even if no transition said so yet (a slow walk is often missed at first).
class RoutineGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError() || event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_EXIT) return
        if (!LocationCollector.canRun(context)) return
        TripRecordingService.start(context, activity = null)
    }
}

// Play services forgets transition and geofence registrations on reboot and on app update.
class RoutineBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        LocationCollector.registerAll(context)
        RoutineCaptureWorker.schedule(context)
    }
}
