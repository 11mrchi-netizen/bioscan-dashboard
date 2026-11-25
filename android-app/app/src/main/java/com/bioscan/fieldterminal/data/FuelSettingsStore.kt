package com.bioscan.fieldterminal.data

import android.content.Context
import com.bioscan.fieldterminal.domain.fuel.DEFAULT_TOLERATED_G_PER_H
import com.bioscan.fieldterminal.domain.fuel.MealTimes
import com.bioscan.fieldterminal.domain.fuel.RaceFuelSettings
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalTime

// The person's fuel-planning settings (docs/fuel-planning/README.md): usual meal times, whether to train low before easy
// sessions, the carbohydrate rate their gut tolerates, caffeine, and whether they are a salty sweater. Kept on the phone
// (read synchronously by the fuel plan) and with the account (user_profile.fuel_settings), so the web console plans with
// the same settings, the way NutritionGoalsStore keeps the nutrition goal next to it.

data class FuelSettings(
    val meals: MealTimes = MealTimes(),
    val trainLow: Boolean = false,
    val toleratedGPerHour: Double = DEFAULT_TOLERATED_G_PER_H,
    val caffeineOk: Boolean = false,
    val caffeineMgPerKg: Double = 3.0,
    val saltySweater: Boolean = false,
) {
    val race: RaceFuelSettings get() = RaceFuelSettings(toleratedGPerHour, caffeineOk, caffeineMgPerKg, saltySweater)
}

/**
 * The account's copy (user_profile.fuel_settings): every field written, `snack` null when there is no afternoon snack.
 * The web reads the same keys (console/src/lib/fuelSettings.ts); a key it does not find takes the default.
 */
fun fuelSettingsToJson(s: FuelSettings): JsonObject = buildJsonObject {
    put("breakfast", JsonPrimitive(s.meals.breakfast.toString()))
    put("lunch", JsonPrimitive(s.meals.lunch.toString()))
    put("snack", s.meals.snack?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
    put("dinner", JsonPrimitive(s.meals.dinner.toString()))
    put("trainLow", JsonPrimitive(s.trainLow))
    put("toleratedGPerHour", JsonPrimitive(s.toleratedGPerHour))
    put("caffeineOk", JsonPrimitive(s.caffeineOk))
    put("caffeineMgPerKg", JsonPrimitive(s.caffeineMgPerKg))
    put("saltySweater", JsonPrimitive(s.saltySweater))
}

fun fuelSettingsFromJson(o: JsonObject): FuelSettings {
    val d = FuelSettings()
    fun time(key: String): LocalTime? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
    fun bool(key: String, default: Boolean) = (o[key] as? JsonPrimitive)?.booleanOrNull ?: default
    fun num(key: String, default: Double) = (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() && it > 0 } ?: default
    return FuelSettings(
        meals = MealTimes(
            breakfast = time("breakfast") ?: d.meals.breakfast,
            lunch = time("lunch") ?: d.meals.lunch,
            snack = if (o.containsKey("snack") && o["snack"] is JsonNull) null else time("snack") ?: d.meals.snack,
            dinner = time("dinner") ?: d.meals.dinner,
        ),
        trainLow = bool("trainLow", d.trainLow),
        toleratedGPerHour = num("toleratedGPerHour", d.toleratedGPerHour),
        caffeineOk = bool("caffeineOk", d.caffeineOk),
        caffeineMgPerKg = num("caffeineMgPerKg", d.caffeineMgPerKg),
        saltySweater = bool("saltySweater", d.saltySweater),
    )
}

@Serializable
private data class FuelSettingsRow(@SerialName("fuel_settings") val fuelSettings: JsonObject?)

object FuelSettingsStore {
    private const val PREFS = "fuel_settings"
    private const val PREF_UNSYNCED = "unsynced"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun get(context: Context): FuelSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun time(key: String, default: LocalTime?): LocalTime? =
            if (p.contains(key)) p.getString(key, null)?.let { runCatching { LocalTime.parse(it) }.getOrNull() } else default
        val d = MealTimes()
        return FuelSettings(
            meals = MealTimes(
                breakfast = time("breakfast", d.breakfast) ?: d.breakfast,
                lunch = time("lunch", d.lunch) ?: d.lunch,
                snack = time("snack", d.snack),
                dinner = time("dinner", d.dinner) ?: d.dinner,
            ),
            trainLow = p.getBoolean("train_low", false),
            toleratedGPerHour = p.getString("tolerated_g_per_h", null)?.toDoubleOrNull() ?: DEFAULT_TOLERATED_G_PER_H,
            caffeineOk = p.getBoolean("caffeine_ok", false),
            caffeineMgPerKg = p.getString("caffeine_mg_per_kg", null)?.toDoubleOrNull() ?: 3.0,
            saltySweater = p.getBoolean("salty_sweater", false),
        )
    }

    /** Saves on the phone and, in the background, with the account; a save made offline is pushed on the next open. */
    fun save(context: Context, s: FuelSettings) {
        writeLocal(context, s, unsynced = true)
        scope.launch { catching { sync(context, SupabaseClientProvider.client) } }
    }

    /**
     * Brings the phone and the account into line, as NutritionGoalsStore.sync: an unsynced local change is pushed;
     * otherwise the account's copy wins, and settings only this phone has (saved before they were kept with the account)
     * are uploaded. Called on every open.
     */
    suspend fun sync(context: Context, supabase: SupabaseClient) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val table = supabase.postgrest.from("user_profile")
        if (!p.getBoolean(PREF_UNSYNCED, false)) {
            val remote = table.select(columns = Columns.list("fuel_settings")).decodeSingleOrNull<FuelSettingsRow>()?.fuelSettings
            if (remote != null) return writeLocal(context, fuelSettingsFromJson(remote), unsynced = false)
            // never saved on this phone either: nothing to upload, the defaults stand on both sides
            if (!p.contains("breakfast")) return
        }
        table.upsert(FuelSettingsRow(fuelSettingsToJson(get(context)))) { onConflict = "user_id" }
        p.edit().putBoolean(PREF_UNSYNCED, false).apply()
    }

    private fun writeLocal(context: Context, s: FuelSettings, unsynced: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("breakfast", s.meals.breakfast.toString())
            .putString("lunch", s.meals.lunch.toString())
            .putString("snack", s.meals.snack?.toString())
            .putString("dinner", s.meals.dinner.toString())
            .putBoolean("train_low", s.trainLow)
            .putString("tolerated_g_per_h", s.toleratedGPerHour.toString())
            .putBoolean("caffeine_ok", s.caffeineOk)
            .putString("caffeine_mg_per_kg", s.caffeineMgPerKg.toString())
            .putBoolean("salty_sweater", s.saltySweater)
            .putBoolean(PREF_UNSYNCED, unsynced)
            .apply()
    }
}
