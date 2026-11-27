package com.bioscan.fieldterminal.ui.nav

import com.bioscan.fieldterminal.ui.screens.today.DetailPage
import com.bioscan.fieldterminal.ui.screens.today.FullPicture
import com.bioscan.fieldterminal.domain.score.DomainKey
import org.junit.Assert.assertEquals
import org.junit.Test

// The dock and its tabs, and where the rings' full-picture tiles lead (the pages that replaced the old Training,
// Nutrition and Health tiles; only LAB's old route string is kept, so a saved back stack still resolves).
class NavigationMapTest {
    @Test
    fun onlyLabKeepsItsOldTileRoute() {
        assertEquals(listOf("tile_labs"), TileRoute.entries.map { it.route })
        assertEquals(listOf("N=1", "TESTS", "EXPLORE"), LabTab.entries.map { it.label })
    }

    // Scoring system rebuild: the dock is TODAY · PLAN · + LOG · LAB · ME; the status and user routes keep their strings.
    @Test
    fun dockFollowsTheScoringSystemStructure() {
        assertEquals(listOf("TODAY", "PLAN", "+ LOG", "INSIGHTS", "ME"), TopLevelTab.entries.map { it.label })
        assertEquals(listOf("status", "plan", "log", "lab", "user"), TopLevelTab.entries.map { it.route })
        assertEquals(listOf("TRAINING", "FOOD", "RACE", "REGIMEN"), PlanTab.entries.map { it.label })
        // Me's row is five tabs (A11); Bloodwork, Intimacy and Exposures are opened from Health and keep their entries for saved state.
        assertEquals(listOf("HEALTHSPAN", "PERFORMANCE", "BODY", "HEALTH", "ROUTINE"), MeTab.row.map { it.label })
        assertEquals(listOf(MeTab.Health, MeTab.Health, MeTab.Health), listOf(MeTab.Bloodwork, MeTab.Intimacy, MeTab.Exposures).map { it.rowTab })
        assertEquals(MeTab.row, MeTab.row.map { it.rowTab })
    }

    // The map's change requests: each ring's tiles open pages of their own; FUEL's are Nutrition, Hydration, Digestion.
    @Test
    fun fullPictureTilesLeadToTheirOwnPages() {
        fun titles(key: DomainKey) = FullPicture.entries.filter { it.domain == key }.map { it.title to it.detail }
        assertEquals(listOf("Last night" to null, "Sleep" to DetailPage.SLEEP), titles(DomainKey.SLEEP))
        assertEquals(listOf("Heart" to DetailPage.CARDIO, "Sexual health" to DetailPage.SEXUAL), titles(DomainKey.RECOVERY))
        assertEquals(listOf("Training load" to DetailPage.LOAD, "Injury" to DetailPage.INJURY), titles(DomainKey.LOAD))
        assertEquals(listOf("Nutrition" to DetailPage.NUTRITION, "Hydration" to DetailPage.HYDRATION, "Digestion" to DetailPage.DIGESTION), titles(DomainKey.FUEL))
        assertEquals(listOf("Stress through the day" to null, "Wellbeing" to DetailPage.WELLBEING), titles(DomainKey.BALANCE))
        assertEquals(DetailPage.entries.size, DetailPage.entries.map { it.route }.toSet().size)
    }
}
