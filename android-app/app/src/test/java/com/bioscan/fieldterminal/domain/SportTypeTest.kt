package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SportTypeTest {

    @Test
    fun classifiesEachKnownType() {
        assertEquals(SportType.RUN, SportType.from("run", routeType = null))
        assertEquals(SportType.TRAIL_RUN, SportType.from("run", routeType = "trail"))
        assertEquals(SportType.RUN, SportType.from("run", routeType = "road"))
        assertEquals(SportType.WALK, SportType.from("walk", routeType = null))
        assertEquals(SportType.HIKE, SportType.from("hike", routeType = null))
        assertEquals(SportType.RIDE, SportType.from("ride", routeType = null))
        assertEquals(SportType.SWIM, SportType.from("swim", routeType = null))
        assertEquals(SportType.STRENGTH, SportType.from("strength", routeType = null))
        assertEquals(SportType.MOBILITY, SportType.from("yoga", routeType = null))
        assertEquals(SportType.CONDITIONING, SportType.from("conditioning", routeType = null))
        assertEquals(SportType.CLIMBING, SportType.from("climbing", routeType = null))
    }

    @Test
    fun unclassifiedTypeFallsBackToOther() {
        assertEquals(SportType.OTHER, SportType.from("kayaking", routeType = null))
    }
}
