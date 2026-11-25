package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.routine.PlaceRow
import com.bioscan.fieldterminal.data.routine.homeOf
import com.bioscan.fieldterminal.domain.fuel.MealTimes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

// user_profile.fuel_settings: the shape the web reads (console/src/lib/fuelSettings.ts and its test use the same JSON).
class FuelSettingsJsonTest {

    private fun parse(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test
    fun roundTripsEveryField() {
        val s = FuelSettings(MealTimes(LocalTime.of(7, 15), LocalTime.of(13, 0), null, LocalTime.of(20, 0)), true, 75.0, true, 2.5, true)
        val json = fuelSettingsToJson(s)
        assertEquals(
            """{"breakfast":"07:15","lunch":"13:00","snack":null,"dinner":"20:00","trainLow":true,"toleratedGPerHour":75.0,"caffeineOk":true,"caffeineMgPerKg":2.5,"saltySweater":true}""",
            json.toString(),
        )
        assertEquals(s, fuelSettingsFromJson(json))
    }

    @Test
    fun missingOrBadKeysTakeTheDefaults() {
        assertEquals(FuelSettings(), fuelSettingsFromJson(parse("{}")))
        val s = fuelSettingsFromJson(parse("""{"breakfast":"8:00","toleratedGPerHour":"90","caffeineMgPerKg":-1,"trainLow":"yes"}"""))
        assertEquals(FuelSettings(), s)
        // an absent snack is the default snack; a null one is no snack
        assertEquals(LocalTime.of(16, 0), fuelSettingsFromJson(parse("""{"lunch":"12:00"}""")).meals.snack)
        assertNull(fuelSettingsFromJson(parse("""{"snack":null}""")).meals.snack)
    }

    @Test
    fun homeIsTheOldestHomePlaceStillInUse() {
        val places = listOf(
            PlaceRow("a", 1.0, 2.0, category = "work", createdAt = "2026-01-01"),
            PlaceRow("b", 3.0, 4.0, category = "home", createdAt = "2026-03-01"),
            PlaceRow("c", 5.0, 6.0, category = "home", createdAt = "2026-02-01", stopSince = "2026-04-01"),
            PlaceRow("d", 7.0, 8.0, category = "home", createdAt = "2026-05-01"),
        )
        assertEquals(3.0 to 4.0, homeOf(places))
        assertNull(homeOf(places.take(1)))
    }
}
