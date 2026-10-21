package com.bioscan.fieldterminal.data

import android.content.Context
import com.bioscan.fieldterminal.domain.fuel.DEFAULT_TOLERATED_G_PER_H
import com.bioscan.fieldterminal.domain.fuel.MealTimes
import com.bioscan.fieldterminal.domain.fuel.RaceFuelSettings
import java.time.LocalTime

// The person's fuel-planning settings (docs/fuel-planning/README.md): usual meal times, whether to train low before easy
// sessions, the carbohydrate rate their gut tolerates, caffeine, and whether they are a salty sweater. Local, like the
// nutrition goal next to it (NutritionGoalsStore).

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

object FuelSettingsStore {
    private const val PREFS = "fuel_settings"

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

    fun save(context: Context, s: FuelSettings) {
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
            .apply()
    }
}
