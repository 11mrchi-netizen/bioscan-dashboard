package com.bioscan.fieldterminal.domain

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogAgentTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testDecode_serverProposalShape() {
        val raw = """
            {"id":"p1","kind":"drink","quote":"a 500 ml beer","summary":"500 ml alcohol","warnings":["time assumed 21:05"],
             "needs":["abv_percent"],"beverage_class":"alcohol","volume_ml":500,"abv_percent":null,"alcohol_g":null,
             "logged_at":"2026-10-12T21:05:00+08:00"}
        """.trimIndent()
        val p = json.decodeFromString<LogProposal>(raw)
        assertEquals(500.0, p.volumeMl!!, 1e-9)
        assertNull(p.abvPercent)
        assertEquals(listOf("strength"), p.missing())
        assertEquals(2026, p.whenLocal()!!.year)
        assertEquals(21, p.whenLocal()!!.hour)
    }

    @Test
    fun testMissing_alcoholNeedsStrengthAndVolume() {
        val base = LogProposal(id = "p1", kind = "drink", beverageClass = "alcohol", volumeMl = 500.0)
        assertEquals(listOf("strength"), base.missing())
        assertTrue(base.copy(abvPercent = 5.0).isReady())
        assertEquals(listOf("strength"), base.copy(abvPercent = 100.1).missing())
        assertEquals(listOf("volume", "strength"), base.copy(volumeMl = null).missing())
        assertTrue(LogProposal(id = "p2", kind = "drink", beverageClass = "coffee", volumeMl = 250.0).isReady())
    }

    @Test
    fun testMissing_wellbeingNeedsEveryMentionedScoreAndAtLeastOne() {
        val p = LogProposal(id = "p1", kind = "wellbeing", needs = listOf("mood"), energy = 7)
        assertEquals(listOf("mood"), p.missing())
        assertTrue(p.copy(mood = 6).isReady())
        assertEquals(listOf("score"), LogProposal(id = "p2", kind = "wellbeing").missing())
        assertEquals(listOf("score 0-10"), LogProposal(id = "p3", kind = "wellbeing", energy = 11).missing())
    }

    @Test
    fun testMissing_supplementRecoveryAndMeal() {
        assertEquals(listOf("supplement"), LogProposal(id = "a", kind = "supplement_taken", supplementName = "vitamin").missing())
        assertTrue(LogProposal(id = "a", kind = "supplement_taken", supplementId = 4).isReady())
        assertTrue(LogProposal(id = "b", kind = "recovery", name = "sauna").isReady())
        assertFalse(LogProposal(id = "c", kind = "meal", text = "  ").isReady())
        assertFalse(LogProposal(id = "d", kind = "mystery").isReady())
    }

    @Test
    fun testWhenLocal_keepsLocalDigitsAndLabelsThemUtc() {
        val p = LogProposal(id = "p1", kind = "drink", loggedAt = "2026-10-11T18:30:00+08:00")
        assertEquals("2026-10-11T18:30Z", p.whenLocal()!!.toAppIso())
        assertEquals("2026-10-11", p.localDate())
        assertEquals("2026-10-10", LogProposal(id = "w", kind = "wellbeing", date = "2026-10-10").localDate())
        assertNull(LogProposal(id = "x", kind = "drink", loggedAt = "not a time").whenLocal())
    }
}
