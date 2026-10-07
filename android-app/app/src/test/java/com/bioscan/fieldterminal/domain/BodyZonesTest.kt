package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyZonesTest {

    @Test
    fun everyZoneIsReachableFromSomeRegion() {
        val reached = BodyRegion.entries.map { it.zone() }.toSet()
        assertEquals(BodyZone.entries.toSet(), reached)
    }

    @Test
    fun zoneLoadsPreserveTotalAndMergeRegions() {
        val regional = mapOf(BodyRegion.Lats to 300.0, BodyRegion.MiddleBack to 100.0, BodyRegion.Chest to 50.0)
        val zones = zoneLoads(regional)
        assertEquals(400.0, zones.getValue(BodyZone.UpperBack), 1e-9)
        assertEquals(regional.values.sum(), zones.values.sum(), 1e-9)
        assertTrue(BodyZone.Quads !in zones)
    }
}
