package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.LogGripRow
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StrengthStandardsTest {

    @Test
    fun levelsFollowBodyweightMultiples() {
        // squat for an 80 kg lifter: novice 60, intermediate 100, advanced 140, elite 180
        assertEquals(StrengthLevel.UNTRAINED, strengthLevelFor("barbell_squat", 59.0, 80.0))
        assertEquals(StrengthLevel.NOVICE, strengthLevelFor("barbell_squat", 60.0, 80.0))
        assertEquals(StrengthLevel.INTERMEDIATE, strengthLevelFor("barbell_squat", 120.0, 80.0))
        assertEquals(StrengthLevel.ADVANCED, strengthLevelFor("barbell_squat", 140.0, 80.0))
        assertEquals(StrengthLevel.ELITE, strengthLevelFor("barbell_squat", 200.0, 80.0))
    }

    @Test
    fun unknownLiftOrMissingBodyWeightHasNoLevel() {
        assertNull(strengthLevelFor("cable_fly", 50.0, 80.0))
        assertNull(strengthLevelFor("barbell_squat", 120.0, null))
        // weighted pull-ups use absolute added weight, so no body weight is needed
        assertEquals(StrengthLevel.INTERMEDIATE, strengthLevelFor("pull_up_weighted", 30.0, null))
    }

    @Test
    fun alternativeNamesCountAsTheSameLiftOnce() {
        assertEquals(StrengthLevel.INTERMEDIATE, strengthLevelFor("standing_military_press", 50.0, 80.0)) // 0.55 x 80 = 44
        val d = LocalDate.of(2026, 10, 1)
        val out = bestLifts(
            listOf(
                LiftSet("Front Barbell Squat", 60.0, 5, d), // 70.0
                LiftSet("Barbell Front Squat", 65.0, 5, d.plusDays(1)), // 75.8333
                LiftSet("Standing Military Press", 40.0, 5, d),
                LiftSet("Standing Barbell Press", 35.0, 5, d),
            ),
        ).associateBy { it.slug }
        assertEquals(setOf("front_barbell_squat", "military_press"), out.keys)
        assertEquals(65.0 * (1.0 + 5 / 30.0), out.getValue("front_barbell_squat").e1rmKg, 1e-9)
        assertEquals(d.plusDays(1), out.getValue("front_barbell_squat").date)
    }

    @Test
    fun epleyAndSlug() {
        assertEquals(116.6667, estimatedOneRepMaxKg(100.0, 5), 1e-3)
        assertEquals(100.0, estimatedOneRepMaxKg(100.0, 30), 0.0)
        assertEquals("barbell_squat", exerciseSlug("Barbell Squat"))
        assertEquals("bent_over_barbell_row", exerciseSlug("Bent-over barbell row!"))
    }

    @Test
    fun bestLiftsKeepsTheStrongestSetPerLiftWithItsDate() {
        val d1 = LocalDate.of(2026, 8, 1)
        val d2 = LocalDate.of(2026, 9, 1)
        val out = bestLifts(
            listOf(
                LiftSet("Barbell Squat", 100.0, 5, d1), // e1RM 116.7
                LiftSet("Barbell Squat", 110.0, 3, d2), // e1RM 121.0
                LiftSet("Barbell Squat", 0.0, 8, d2), // bodyweight / empty bar: ignored
                LiftSet("Military Press", 50.0, 1, d1),
            ),
        ).associateBy { it.slug }
        assertEquals(2, out.size)
        assertEquals(121.0, out.getValue("barbell_squat").e1rmKg, 1e-9)
        assertEquals(d2, out.getValue("barbell_squat").date)
        assertEquals(50.0 * (1.0 + 1 / 30.0), out.getValue("military_press").e1rmKg, 1e-9)
    }

    @Test
    fun gripReadingsAppearInTheLogAsABodyEntry() {
        val rows = listOf(LogGripRow(id = 7, occurredAt = "2026-10-13T09:30:00+00:00", hand = "right", kg = 46.5))
        val entries = buildLogEntries(
            meals = emptyList(), exerciseSessions = emptyList(), sleep = emptyList(), stool = emptyList(), encounters = emptyList(),
            grip = rows,
        )
        val e = entries.single()
        assertEquals(LogSource.Grip, e.source)
        assertEquals(LogEntryKind.Grip, e.kind)
        assertEquals(LogCategory.Body, e.kind.category)
        assertEquals(LocalDateTime.of(2026, 10, 13, 9, 30), e.timestamp)
        assertEquals("Grip right · %.1f kg".format(46.5), e.headline)
        assertEquals("grip_strength_log", e.source.table)
    }
}
