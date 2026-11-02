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
        assertEquals(listOf("CARDIO", "SLEEP", "BODY", "CONCERNS", "WELLBEING", "SEXUAL HEALTH", "BLOODWORK"), HealthTab.entries.map { it.label })
        assertEquals(listOf("N=1", "SUPPLEMENTS", "TESTS", "ROUTINE"), LabTab.entries.map { it.label })
    }

    // Scoring system rebuild: the dock is TODAY · PLAN · + LOG · LAB · ME; the status and user routes keep their strings.
    @Test
    fun dockFollowsTheScoringSystemStructure() {
        assertEquals(listOf("TODAY", "PLAN", "+ LOG", "LAB", "ME"), TopLevelTab.entries.map { it.label })
        assertEquals(listOf("status", "plan", "log", "lab", "user"), TopLevelTab.entries.map { it.route })
        assertEquals(listOf("TRAINING", "FOOD", "RACE", "SESSIONS"), PlanTab.entries.map { it.label })
        assertEquals(listOf("HEALTHSPAN", "PERFORMANCE", "BODY", "HEALTH", "BLOODWORK"), MeTab.entries.map { it.label })
    }
}
