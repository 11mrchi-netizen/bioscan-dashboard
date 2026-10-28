package com.bioscan.fieldterminal.data.routine

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId

// Routine intelligence (docs/routine-intelligence/01-research-and-plan.md, section 8.3): one switch per stream, PAUSE FOR
// TODAY, and the collector's own bookkeeping. Both streams start off: nothing is collected until the person turns a stream on
// in Setup › Profile & goals › Data capture.
enum class RoutineStream(val key: String) { PHONE_USAGE("phone_usage"), LOCATION("location") }

object RoutineSettingsStore {
    private const val PREFS = "ft_routine"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context, stream: RoutineStream): Boolean = prefs(context).getBoolean("${stream.key}_enabled", false)

    fun setEnabled(context: Context, stream: RoutineStream, enabled: Boolean) {
        prefs(context).edit().putBoolean("${stream.key}_enabled", enabled).apply()
    }

    fun pausedUntil(context: Context, stream: RoutineStream): Long = prefs(context).getLong("${stream.key}_paused_until", 0L)

    // Paused until the next local midnight.
    fun pauseForToday(context: Context, stream: RoutineStream, zone: ZoneId = ZoneId.systemDefault()) {
        val until = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        prefs(context).edit().putLong("${stream.key}_paused_until", until).apply()
    }

    fun resume(context: Context, stream: RoutineStream) {
        prefs(context).edit().putLong("${stream.key}_paused_until", 0L).apply()
    }

    fun isActive(context: Context, stream: RoutineStream, now: Long = System.currentTimeMillis()): Boolean =
        isEnabled(context, stream) && pausedUntil(context, stream) <= now

    fun getLong(context: Context, key: String): Long = prefs(context).getLong(key, 0L)

    fun putLong(context: Context, key: String, value: Long) {
        prefs(context).edit().putLong(key, value).apply()
    }

    // The centre of the current stay's geofence, so it can be re-armed after a reboot.
    fun stayCentre(context: Context): Pair<Double, Double>? {
        val p = prefs(context)
        if (!p.contains("stay_lat")) return null
        return Double.fromBits(p.getLong("stay_lat", 0L)) to Double.fromBits(p.getLong("stay_lon", 0L))
    }

    fun setStayCentre(context: Context, lat: Double, lon: Double) {
        prefs(context).edit().putLong("stay_lat", lat.toRawBits()).putLong("stay_lon", lon.toRawBits()).apply()
    }

    const val KEY_LAST_HEARTBEAT = "location_last_heartbeat"
    const val KEY_LAST_UPLOAD = "location_last_upload"
    const val KEY_LAST_PHONE_RUN = "phone_usage_last_run"
    const val KEY_LAST_PHONE_ERROR = "phone_usage_last_error_at"
    const val KEY_LAST_MOBILITY_RUN = "mobility_last_run"
}
