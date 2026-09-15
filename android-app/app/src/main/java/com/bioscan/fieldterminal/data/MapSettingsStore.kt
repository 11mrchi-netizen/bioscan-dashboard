package com.bioscan.fieldterminal.data

import android.content.Context

// Local-only settings for the Map tab, neither synced to Supabase:
//
// - The CARTO tile API key (free, rate-limited only -- no billing exposure,
//   unlike a Google Maps API key -- see ROADMAP.md). Stored in plain
//   SharedPreferences, not Keystore-encrypted like GeminiApiKeyStore: that
//   one guards against a leaked key running up someone else's bill, which
//   doesn't apply here.
// - Home latitude/longitude, used purely to build a Google Maps directions
//   deep-link on-device. Real personal location data with no reason to leave
//   this device, so this never touches Supabase or any network call of its
//   own -- stored as strings (not Float) to avoid losing the last decimal
//   digit of precision Float's ~7 significant figures would cost a
//   coordinate like 121.5654321.
object MapSettingsStore {
    private const val PREFS_NAME = "map_settings"
    private const val PREF_CARTO_KEY = "carto_api_key"
    private const val PREF_HOME_LAT = "home_lat"
    private const val PREF_HOME_LON = "home_lon"

    fun getCartoKey(context: Context): String? = prefs(context).getString(PREF_CARTO_KEY, null)

    fun saveCartoKey(context: Context, key: String) {
        prefs(context).edit().putString(PREF_CARTO_KEY, key).apply()
    }

    fun clearCartoKey(context: Context) {
        prefs(context).edit().remove(PREF_CARTO_KEY).apply()
    }

    fun getHome(context: Context): Pair<Double, Double>? {
        val p = prefs(context)
        val lat = p.getString(PREF_HOME_LAT, null)?.toDoubleOrNull()
        val lon = p.getString(PREF_HOME_LON, null)?.toDoubleOrNull()
        return if (lat != null && lon != null) lat to lon else null
    }

    fun saveHome(context: Context, lat: Double, lon: Double) {
        prefs(context).edit()
            .putString(PREF_HOME_LAT, lat.toString())
            .putString(PREF_HOME_LON, lon.toString())
            .apply()
    }

    fun clearHome(context: Context) {
        prefs(context).edit().remove(PREF_HOME_LAT).remove(PREF_HOME_LON).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
