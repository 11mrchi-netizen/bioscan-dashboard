package com.bioscan.fieldterminal.data

import android.content.Context
import android.content.SharedPreferences
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Default daily fluid range used when the user hasn't set their own: a
// deliberately wide band around the NASEM adequate intake for adult men
// (3.7 L total water, roughly 3.0 L of it from beverages). It is a generic
// reference, never presented as a personal target -- the UI labels it as one.
const val DEFAULT_HYDRATION_MIN_ML = 2500.0
const val DEFAULT_HYDRATION_MAX_ML = 3500.0

// The day's fluid range: the saved range (or the generic reference above) plus today's inferred exercise sweat loss. One
// definition for the Fuel tile's hydration card and the Status hub's FUEL preview, so the two never disagree.
fun hydrationBoundsMl(goals: NutritionGoals, exerciseDemandMl: Double): ClosedFloatingPointRange<Double> =
    ((goals.hydrationMinMl ?: DEFAULT_HYDRATION_MIN_ML) + exerciseDemandMl)..((goals.hydrationMaxMl ?: DEFAULT_HYDRATION_MAX_ML) + exerciseDemandMl)

// DAV-286: one daily calorie/macro target profile. Values are stored as
// strings (not Float), same precision-safe convention MapSettingsStore
// already uses for coordinates -- fields are independently settable/clearable
// (e.g. a calories-only goal) rather than an all-or-nothing blob, matching
// how this app's other per-field local settings behave.
//
// Hydration is a min-max range, not a single volume: a fixed daily number
// is false precision for fluid intake.
@Serializable
data class NutritionGoals(
    val caloriesKcal: Double? = null,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    val hydrationMinMl: Double? = null,
    val hydrationMaxMl: Double? = null,
    // How the goal was entered (domain/NutritionTargets.kt), so the form reopens the same way: protein per kg of body
    // weight and fat as a share of calories when chosen that way (the grams above are always the resolved goal), and the
    // activity level when chosen by hand (null: taken from logged training).
    val proteinPerKg: Double? = null,
    val fatPct: Double? = null,
    val activityLevel: String? = null,
) {
    // Calorie/macro goals only -- the goal-adherence card keys off this.
    val isSet: Boolean get() = caloriesKcal != null || proteinG != null || carbsG != null || fatG != null
    val hasHydrationRange: Boolean get() = hydrationMinMl != null && hydrationMaxMl != null
    val anySet: Boolean get() = isSet || hydrationMinMl != null || hydrationMaxMl != null
}

// Kept on the phone (SharedPreferences, read synchronously by the score build and the fuel plan) and with the account
// (user_profile.nutrition_goals), so a reinstall or a second phone scores FUEL against the same targets. A local change is
// marked unsynced until the server has it, so a save made offline is pushed on the next open instead of being overwritten
// by the older server copy.

@Serializable
private data class GoalsRow(@SerialName("nutrition_goals") val nutritionGoals: NutritionGoals?)

object NutritionGoalsStore {
    private const val PREFS_NAME = "nutrition_goals"
    private const val PREF_CALORIES = "calories_kcal"
    private const val PREF_PROTEIN = "protein_g"
    private const val PREF_CARBS = "carbs_g"
    private const val PREF_FAT = "fat_g"
    private const val PREF_HYDRATION_MIN = "hydration_min_ml"
    private const val PREF_HYDRATION_MAX = "hydration_max_ml"
    private const val PREF_PROTEIN_PER_KG = "protein_per_kg"
    private const val PREF_FAT_PCT = "fat_pct"
    private const val PREF_ACTIVITY = "activity_level"
    private const val PREF_UNSYNCED = "unsynced"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun getGoals(context: Context): NutritionGoals {
        val p = prefs(context)
        return NutritionGoals(
            caloriesKcal = p.getString(PREF_CALORIES, null)?.toDoubleOrNull(),
            proteinG = p.getString(PREF_PROTEIN, null)?.toDoubleOrNull(),
            carbsG = p.getString(PREF_CARBS, null)?.toDoubleOrNull(),
            fatG = p.getString(PREF_FAT, null)?.toDoubleOrNull(),
            hydrationMinMl = p.getString(PREF_HYDRATION_MIN, null)?.toDoubleOrNull(),
            hydrationMaxMl = p.getString(PREF_HYDRATION_MAX, null)?.toDoubleOrNull(),
            proteinPerKg = p.getString(PREF_PROTEIN_PER_KG, null)?.toDoubleOrNull(),
            fatPct = p.getString(PREF_FAT_PCT, null)?.toDoubleOrNull(),
            activityLevel = p.getString(PREF_ACTIVITY, null),
        )
    }

    fun saveGoals(context: Context, goals: NutritionGoals) {
        writeLocal(context, goals, unsynced = true)
        scope.launch { catching { sync(context, SupabaseClientProvider.client) } }
    }

    fun clearGoals(context: Context) = saveGoals(context, NutritionGoals())

    /**
     * Brings the phone and the account into line: an unsynced local change is pushed; otherwise the account's copy wins,
     * and goals that only this phone has (set before they were kept with the account) are uploaded. Called on every open.
     */
    suspend fun sync(context: Context, supabase: SupabaseClient) {
        val local = getGoals(context)
        val table = supabase.postgrest.from("user_profile")
        if (!prefs(context).getBoolean(PREF_UNSYNCED, false)) {
            val remote = table.select(columns = Columns.list("nutrition_goals")).decodeSingleOrNull<GoalsRow>()?.nutritionGoals
            if (remote != null) return writeLocal(context, remote, unsynced = false)
            // ponytail: goals cleared on one phone come back from another phone that still has them; add a cleared
            // marker on the account if two phones are ever used.
            if (!local.anySet && local.activityLevel == null) return
        }
        table.upsert(GoalsRow(local.takeIf { it.anySet || it.activityLevel != null })) { onConflict = "user_id" }
        prefs(context).edit().putBoolean(PREF_UNSYNCED, false).apply()
    }

    private fun writeLocal(context: Context, goals: NutritionGoals, unsynced: Boolean) {
        prefs(context).edit()
            .putOrRemove(PREF_CALORIES, goals.caloriesKcal)
            .putOrRemove(PREF_PROTEIN, goals.proteinG)
            .putOrRemove(PREF_CARBS, goals.carbsG)
            .putOrRemove(PREF_FAT, goals.fatG)
            .putOrRemove(PREF_HYDRATION_MIN, goals.hydrationMinMl)
            .putOrRemove(PREF_HYDRATION_MAX, goals.hydrationMaxMl)
            .putOrRemove(PREF_PROTEIN_PER_KG, goals.proteinPerKg)
            .putOrRemove(PREF_FAT_PCT, goals.fatPct)
            .putOrRemoveString(PREF_ACTIVITY, goals.activityLevel)
            .putBoolean(PREF_UNSYNCED, unsynced)
            .apply()
    }

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: Double?) =
        if (value != null) putString(key, value.toString()) else remove(key)

    private fun SharedPreferences.Editor.putOrRemoveString(key: String, value: String?) =
        if (value != null) putString(key, value) else remove(key)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
