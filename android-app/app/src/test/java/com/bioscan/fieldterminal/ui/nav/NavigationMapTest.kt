package com.bioscan.fieldterminal.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Test

// App restructure: the tiles were renamed (Fuel -> Nutrition, Heart -> Health, Labs -> Lab) but kept their route strings,
// so a back stack saved before the update still resolves; and every tab set matches the owner's structure document.
class NavigationMapTest {
    @Test
    fun tileRoutesKeepTheirStrings() {
        assertEquals(listOf("tile_training", "tile_fuel", "tile_heart", "tile_labs"), TileRoute.entries.map { it.route })
    }

    @Test
    fun tabsFollowTheStructureDocument() {
        assertEquals(listOf("LOAD", "RUNNING", "STRENGTH", "SESSIONS", "INJURY"), TrainingTab.entries.map { it.label })
        assertEquals(listOf("NUTRITION", "HYDRATION", "PLAN"), NutritionTab.entries.map { it.label })
        assertEquals(listOf("CARDIO", "SLEEP", "BODY", "WELLBEING", "SEXUAL HEALTH", "BLOODWORK"), HealthTab.entries.map { it.label })
        assertEquals(listOf("N=1", "SUPPLEMENTS", "PLANNING", "TESTS"), LabTab.entries.map { it.label })
        assertEquals(listOf("TRAINING", "FOOD PLAN", "RACE FUEL"), PlanningSection.entries.map { it.label })
    }
}
