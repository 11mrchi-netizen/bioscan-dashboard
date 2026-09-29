package com.bioscan.fieldterminal.data

import android.content.Context
import android.content.SharedPreferences

// DAV-286: one daily calorie/macro target profile. Values are stored as
// strings (not Float), same precision-safe convention MapSettingsStore
// already uses for coordinates -- fields are independently settable/clearable
// (e.g. a calories-only goal) rather than an all-or-nothing blob, matching
// how this app's other per-field local settings behave.
data class NutritionGoals(
    val caloriesKcal: Double? = null,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
) {
    val isSet: Boolean get() = caloriesKcal != null || proteinG != null || carbsG != null || fatG != null
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

    fun getGoals(context: Context): NutritionGoals {
        val p = prefs(context)
        return NutritionGoals(
            caloriesKcal = p.getString(PREF_CALORIES, null)?.toDoubleOrNull(),
            proteinG = p.getString(PREF_PROTEIN, null)?.toDoubleOrNull(),
            carbsG = p.getString(PREF_CARBS, null)?.toDoubleOrNull(),
            fatG = p.getString(PREF_FAT, null)?.toDoubleOrNull(),
        )
    }

    fun saveGoals(context: Context, goals: NutritionGoals) {
        prefs(context).edit()
            .putOrRemove(PREF_CALORIES, goals.caloriesKcal)
            .putOrRemove(PREF_PROTEIN, goals.proteinG)
            .putOrRemove(PREF_CARBS, goals.carbsG)
            .putOrRemove(PREF_FAT, goals.fatG)
            .apply()
    }

    fun clearGoals(context: Context) {
        prefs(context).edit()
            .remove(PREF_CALORIES).remove(PREF_PROTEIN).remove(PREF_CARBS).remove(PREF_FAT)
            .apply()
    }

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: Double?) =
        if (value != null) putString(key, value.toString()) else remove(key)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
