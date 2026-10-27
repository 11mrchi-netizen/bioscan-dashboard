package com.bioscan.fieldterminal.data.routine

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.bioscan.fieldterminal.domain.routine.Fix
import com.bioscan.fieldterminal.domain.routine.isSettled
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

// Routine intelligence R2 (docs/routine-intelligence/01-research-and-plan.md, section 3.1): records one trip. A foreground
// service of type location, because a background app gets location only a few times an hour, which cannot follow a commute.
// Android requires its ongoing notification; it is on a silent, minimum-importance channel and is not a nudge.
//
// Fixes: every 30 s at high accuracy on foot or bicycle (the route matters for active minutes), every 60 s at balanced
// power in a vehicle or before the activity is known. The trip ends 5 minutes after a STILL transition, when the last 10
// minutes of fixes stay within 100 m (domain/routine/StayPoints.kt isSettled), or after 4 hours; then a geofence is armed
// around the last fix and the service stops.
class TripRecordingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val recent = ArrayDeque<Fix>()
    private var activity: String? = null
    private var startedAt = 0L
    private var lastLocation: Location? = null
    private var updatesOn = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (loc in result.locations) onFix(loc)
        }
    }

    private val settleNow = Runnable { finish() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground first, always: a service started with startForegroundService that stops before calling startForeground
        // crashes the app on Android 12+, even when it stops on purpose.
        if (!goForeground()) return START_NOT_STICKY
        if (!LocationCollector.canRun(this)) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_SETTLE -> handler.postDelayed(settleNow, SETTLE_DELAY_MS)
            else -> {
                handler.removeCallbacks(settleNow)
                val newActivity = intent?.getStringExtra(EXTRA_ACTIVITY)
                if (!updatesOn || (newActivity != null && newActivity != activity)) {
                    if (newActivity != null) activity = newActivity
                    requestUpdates()
                }
                if (startedAt == 0L) startedAt = System.currentTimeMillis()
            }
        }
        running = true
        return START_NOT_STICKY
    }

    private fun goForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Trip recording", NotificationManager.IMPORTANCE_MIN))
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Recording a trip")
            .setContentText("Locations · stops when you settle")
            .setOngoing(true)
            .setSilent(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        return runCatching {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        }.onFailure {
            android.util.Log.w("TripRecordingService", "could not start in the foreground: ${it.message}")
            stopSelf()
        }.isSuccess
    }

    @SuppressLint("MissingPermission") // canRun() checked the permissions in onStartCommand
    private fun requestUpdates() {
        val onFoot = activity in setOf("walking", "running", "on_bicycle")
        val request = if (onFoot) {
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 30_000L).setMinUpdateIntervalMillis(15_000L).build()
        } else {
            LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000L).setMinUpdateIntervalMillis(30_000L).build()
        }
        val client = LocationServices.getFusedLocationProviderClient(this)
        if (updatesOn) client.removeLocationUpdates(callback)
        runCatching { client.requestLocationUpdates(request, callback, Looper.getMainLooper()) }
            .onSuccess { updatesOn = true }
            .onFailure { android.util.Log.w("TripRecordingService", "updates: ${it.message}") }
    }

    private fun onFix(loc: Location) {
        lastLocation = loc
        RoutineOutbox.addFix(
            this,
            OutboxFix(loc.time, loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else null, "trip", activity),
        )
        recent.addLast(Fix(loc.time, loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy.toDouble() else null))
        while (recent.size > 60) recent.removeFirst()
        val now = System.currentTimeMillis()
        if (isSettled(recent.toList(), now) || now - startedAt > MAX_TRIP_MS) finish()
    }

    private fun finish() {
        handler.removeCallbacks(settleNow)
        if (updatesOn) LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(callback)
        updatesOn = false
        lastLocation?.let { LocationCollector.armStayGeofence(this, it.latitude, it.longitude) }
        RoutineCaptureWorker.runSoon(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(settleNow)
        if (updatesOn) LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(callback)
        running = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "routine_trip"
        private const val NOTIFICATION_ID = 7201
        private const val ACTION_START = "start"
        private const val ACTION_SETTLE = "settle"
        private const val EXTRA_ACTIVITY = "activity"
        private const val SETTLE_DELAY_MS = 5 * 60_000L
        private const val MAX_TRIP_MS = 4 * 60 * 60_000L

        @Volatile
        var running = false
            private set

        // Called from the transition and geofence receivers: Android lets an app start a foreground service from the
        // background in response to those two events.
        fun start(context: Context, activity: String?) {
            val intent = Intent(context, TripRecordingService::class.java).setAction(ACTION_START)
            if (activity != null) intent.putExtra(EXTRA_ACTIVITY, activity)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { android.util.Log.w("TripRecordingService", "start refused: ${it.message}") }
        }

        fun settle(context: Context) {
            if (!running) return
            runCatching { context.startService(Intent(context, TripRecordingService::class.java).setAction(ACTION_SETTLE)) }
        }
    }
}
