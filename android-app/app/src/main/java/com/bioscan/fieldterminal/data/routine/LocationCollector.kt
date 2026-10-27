package com.bioscan.fieldterminal.data.routine

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

// Routine intelligence R2 (docs/routine-intelligence/01-research-and-plan.md, section 3.1): transition-driven location.
// 1. Activity transitions (still, walking, running, on a bicycle, in a vehicle) arrive at RoutineTransitionReceiver.
// 2. Moving, or leaving the stay geofence (RoutineGeofenceReceiver), starts TripRecordingService, which takes fixes.
// 3. When the trip settles, the service arms a 150 m geofence around where it ended and stops.
// 4. RoutineCaptureWorker takes one balanced-power fix an hour (the stay's evidence that the collector is alive).
// Nothing here runs unless the Locations stream is on and every permission below is granted.
object LocationCollector {
    const val STAY_GEOFENCE_ID = "routine_stay"
    const val STAY_RADIUS_M = 150f

    private const val RC_TRANSITIONS = 7101
    private const val RC_GEOFENCE = 7102

    val MOVING = mapOf(
        DetectedActivity.WALKING to "walking",
        DetectedActivity.RUNNING to "running",
        DetectedActivity.ON_BICYCLE to "on_bicycle",
        DetectedActivity.IN_VEHICLE to "in_vehicle",
    )
    val TRACKED = MOVING + (DetectedActivity.STILL to "still")

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasFineLocation(context: Context) = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasBackgroundLocation(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun hasActivityRecognition(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(context, Manifest.permission.ACTIVITY_RECOGNITION)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun hasEverything(context: Context) =
        hasFineLocation(context) && hasBackgroundLocation(context) && hasActivityRecognition(context)

    fun canRun(context: Context) = RoutineSettingsStore.isActive(context, RoutineStream.LOCATION) && hasEverything(context)

    @SuppressLint("BatteryLife") // personal sideloaded app: the exemption is the point (section 8.5)
    fun batteryExemptionIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    // "Allow all the time" can only be chosen on the app's own settings page from Android 11 on.
    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    // Play services fills these intents with its results, so they must be mutable (API 31+).
    private fun broadcast(context: Context, cls: Class<*>, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode, Intent(context, cls),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
        )

    // Idempotent: re-registering with the same PendingIntent replaces the earlier request. Called on app start, after a
    // reboot or update, and by the hourly worker, since Play services drops registrations on those occasions.
    @SuppressLint("MissingPermission")
    fun registerAll(context: Context) {
        if (!canRun(context)) return
        val transitions = TRACKED.keys.flatMap { type ->
            listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map { t ->
                ActivityTransition.Builder().setActivityType(type).setActivityTransition(t).build()
            }
        }
        runCatching {
            ActivityRecognition.getClient(context).requestActivityTransitionUpdates(
                ActivityTransitionRequest(transitions),
                broadcast(context, RoutineTransitionReceiver::class.java, RC_TRANSITIONS),
            )
        }.onFailure { android.util.Log.w("LocationCollector", "transitions: ${it.message}") }
        RoutineSettingsStore.stayCentre(context)?.let { (lat, lon) -> armStayGeofence(context, lat, lon) }
    }

    @SuppressLint("MissingPermission")
    fun unregisterAll(context: Context) {
        runCatching {
            ActivityRecognition.getClient(context)
                .removeActivityTransitionUpdates(broadcast(context, RoutineTransitionReceiver::class.java, RC_TRANSITIONS))
        }
        runCatching { LocationServices.getGeofencingClient(context).removeGeofences(listOf(STAY_GEOFENCE_ID)) }
        context.stopService(Intent(context, TripRecordingService::class.java))
    }

    @SuppressLint("MissingPermission")
    fun armStayGeofence(context: Context, lat: Double, lon: Double) {
        RoutineSettingsStore.setStayCentre(context, lat, lon)
        if (!canRun(context)) return
        val fence = Geofence.Builder()
            .setRequestId(STAY_GEOFENCE_ID)
            .setCircularRegion(lat, lon, STAY_RADIUS_M)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()
        val request = GeofencingRequest.Builder().setInitialTrigger(0).addGeofence(fence).build()
        runCatching {
            LocationServices.getGeofencingClient(context)
                .addGeofences(request, broadcast(context, RoutineGeofenceReceiver::class.java, RC_GEOFENCE))
        }.onFailure { android.util.Log.w("LocationCollector", "geofence: ${it.message}") }
    }

    // One fix now at balanced power (Wi-Fi/cell, GPS only if needed); null if none comes within Play services' own timeout.
    @SuppressLint("MissingPermission")
    suspend fun currentFix(context: Context): Location? {
        if (!hasFineLocation(context)) return null
        val cts = CancellationTokenSource()
        return runCatching {
            LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                .awaitOrNull { cts.cancel() }
        }.getOrNull()
    }
}

// Play services' Task as a suspend call, without the kotlinx-coroutines-play-services dependency.
internal suspend fun <T> Task<T>.awaitOrNull(onCancel: () -> Unit = {}): T? = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resume(null) }
    addOnCanceledListener { cont.resume(null) }
    cont.invokeOnCancellation { onCancel() }
}
