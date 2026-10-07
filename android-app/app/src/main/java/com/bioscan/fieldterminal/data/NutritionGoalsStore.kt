package com.bioscan.fieldterminal.data

import android.content.Context
import android.content.SharedPreferences

// Default daily fluid range used when the user hasn't set their own: a
// deliberately wide band around the NASEM adequate intake for adult men
// (3.7 L total water, roughly 3.0 L of it from beverages). It is a generic
// reference, never presented as a personal target -- the UI labels it as one.
const val DEFAULT_HYDRATION_MIN_ML = 2500.0
const val DEFAULT_HYDRATION_MAX_ML = 3500.0

// DAV-286: one daily calorie/macro target profile. Values are stored as
// strings (not Float), same precision-safe convention MapSettingsStore
// already uses for coordinates -- fields are independently settable/clearable
// (e.g. a calories-only goal) rather than an all-or-nothing blob, matching
// how this app's other per-field local settings behave.
//
// Hydration is a min-max range, not a single volume: a fixed daily number
// is false precision for fluid intake.
data class NutritionGoals(
    val caloriesKcal: Double? = null,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    val hydrationMinMl: Double? = null,
    val hydrationMaxMl: Double? = null,
) {
    // Calorie/macro goals only -- the goal-adherence card keys off this.
    val isSet: Boolean get() = caloriesKcal != null || proteinG != null || carbsG != null || fatG != null
    val hasHydrationRange: Boolean get() = hydrationMinMl != null && hydrationMaxMl != null
    val anySet: Boolean get() = isSet || hydrationMinMl != null || hydrationMaxMl != null
}

// Local-only, same pattern as MapSettingsStore -- one person's own daily
// targets, no cross-device sync requirement in the issue, so no Supabase
// table. Adding a future nutrient (fiber, fluid, caffeine) is just another
// key/field pair here, not a storage redesign.
object NutritionGoalsStore {
    private const val PREFS_NAME = "nutrition_goals"
    private const val PREF_CALORIES = "calories_kcal"
    private const val PREF_PROTEIN = "protein_g"
    private const val PREF_CARBS = "carbs_g"
    private const val PREF_FAT = "fat_g"
    private const val PREF_HYDRATION_MIN = "hydration_min_ml"
    private const val PREF_HYDRATION_MAX = "hydration_max_ml"

    fun getGoals(context: Context): NutritionGoals {
        val p = prefs(context)
        return NutritionGoals(
            caloriesKcal = p.getString(PREF_CALORIES, null)?.toDoubleOrNull(),
            proteinG = p.getString(PREF_PROTEIN, null)?.toDoubleOrNull(),
            carbsG = p.getString(PREF_CARBS, null)?.toDoubleOrNull(),
            fatG = p.getString(PREF_FAT, null)?.toDoubleOrNull(),
            hydrationMinMl = p.getString(PREF_HYDRATION_MIN, null)?.toDoubleOrNull(),
            hydrationMaxMl = p.getString(PREF_HYDRATION_MAX, null)?.toDoubleOrNull(),
        )
    }

    fun saveGoals(context: Context, goals: NutritionGoals) {
        prefs(context).edit()
            .putOrRemove(PREF_CALORIES, goals.caloriesKcal)
            .putOrRemove(PREF_PROTEIN, goals.proteinG)
            .putOrRemove(PREF_CARBS, goals.carbsG)
            .putOrRemove(PREF_FAT, goals.fatG)
            .putOrRemove(PREF_HYDRATION_MIN, goals.hydrationMinMl)
            .putOrRemove(PREF_HYDRATION_MAX, goals.hydrationMaxMl)
            .apply()
    }

    fun clearGoals(context: Context) {
        prefs(context).edit()
            .remove(PREF_CALORIES).remove(PREF_PROTEIN).remove(PREF_CARBS).remove(PREF_FAT)
            .remove(PREF_HYDRATION_MIN).remove(PREF_HYDRATION_MAX)
            .apply()
    }

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: Double?) =
        if (value != null) putString(key, value.toString()) else remove(key)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
