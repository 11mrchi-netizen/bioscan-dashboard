package com.bioscan.fieldterminal.domain.routine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Places added by hand: coordinates from what a person pastes, and what the new place takes over.
class ManualPlacesTest {
    private data class Row(
        override val id: String,
        override val lat: Double,
        override val lon: Double,
        override val label: String? = null,
        override val source: String = "learned",
    ) : PlaceRowLike

    private val gym = 25.0330 to 121.5654
    private fun north(p: Pair<Double, Double>, m: Double) = p.first + m / 111_000.0

    @Test
    fun coordinatesFromWhatPeoplePaste() {
        assertEquals(25.033 to 121.5654, parseCoordinates("25.0330, 121.5654"))
        assertEquals(25.033 to 121.5654, parseCoordinates(" 25.0330 121.5654 "))
        assertEquals(-33.8688 to 151.2093, parseCoordinates("-33.8688;151.2093"))
        assertEquals(25.0478 to 121.517, parseCoordinates("https://www.google.com/maps/place/Taipei+Main+Station/@25.0478,121.5170,17z/data=!3m1"))
        assertEquals(25.0478 to 121.517, parseCoordinates("https://maps.google.com/?q=25.0478,121.5170"))
        assertEquals(25.0478 to 121.517, parseCoordinates("https://www.google.com/maps/search/?api=1&query=25.0478%2C121.5170"))
        assertNull(parseCoordinates("Taipei 101"))
        assertNull(parseCoordinates("95.0, 121.0")) // latitude out of range
        assertNull(parseCoordinates("25.0, 190.0")) // longitude out of range
        assertNull(parseCoordinates(""))
    }

    @Test
    fun aManualPlaceAbsorbsTheUnnamedLearnedPlaceThereAndItsVisits() {
        val known = listOf(
            Row("learned-gym", north(gym, 40.0), gym.second),
            Row("learned-far", north(gym, 900.0), gym.second),
            Row("home", north(gym, 120.0), gym.second, label = "Home", source = "home_setting"),
        )
        val visits = listOf(
            VisitAtPlace("v1", north(gym, 40.0), gym.second, "learned-gym"),
            VisitAtPlace("v2", north(gym, 200.0), gym.second, "learned-gym"), // outside the circle, but its place goes
            VisitAtPlace("v3", north(gym, 30.0), gym.second, null), // no place yet
            VisitAtPlace("v4", north(gym, 110.0), gym.second, "home"), // inside, but nearer the named Home
            VisitAtPlace("v5", north(gym, 50.0), gym.second, "home"), // inside and nearer the new place
            VisitAtPlace("v6", north(gym, 900.0), gym.second, "learned-far"),
        )
        val claim = claimForManualPlace(Place("gym", gym.first, gym.second, 100.0), visits, known)
        assertEquals(setOf("learned-gym"), claim.absorbedPlaceIds)
        assertEquals(listOf("v1", "v2", "v3", "v5"), claim.visitKeys)
    }

    @Test
    fun namedPlacesAreNeverAbsorbed() {
        val known = listOf(Row("named", north(gym, 20.0), gym.second, label = "Climbing"))
        val claim = claimForManualPlace(Place("gym", gym.first, gym.second, 100.0), emptyList(), known)
        assertEquals(emptySet<String>(), claim.absorbedPlaceIds)
    }
}
