package com.bioscan.fieldterminal.domain.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The same cases as supabase/functions/_shared/training-core/gear.test.ts: the phone and the web must pre-fill alike.
class GearTest {
    private val rules = listOf(
        GearRule(movementKey("Deadlift"), "straps", 140.0),
        GearRule(movementKey("Deadlift"), "belt", 160.0),
        GearRule(movementKey("Back Squat"), "belt", null),
    )

    @Test
    fun rulesApplyFromTheirThresholdUpwardPerLift() {
        assertEquals(emptyList<String>(), gearForLoad(rules, "Deadlift", 139.9))
        assertEquals(listOf("straps"), gearForLoad(rules, "Deadlift", 140.0))
        assertEquals(listOf("straps", "belt"), gearForLoad(rules, "Barbell Deadlift", 180.0))
        assertEquals(listOf("belt"), gearForLoad(rules, "Back Squat", 20.0))
        assertEquals(listOf("belt"), gearForLoad(rules, "Back Squat", null))
        assertEquals(emptyList<String>(), gearForLoad(rules, "Deadlift", null))
        assertEquals(emptyList<String>(), gearForLoad(rules, "Bench Press", 100.0))
    }

    @Test
    fun rulesReadAsALineLowestThresholdFirst() {
        assertEquals("straps from 140 kg · belt from 160 kg", describeGearRules(rules, "Deadlift"))
        assertEquals("belt", describeGearRules(rules, "Back Squat"))
        assertEquals("", describeGearRules(rules, "Row"))
    }

    @Test
    fun aPrefillLeavesALiftWithNoRulesNotRecorded() {
        assertNull(suggestGear(rules, "Bench Press", 100.0))
        assertEquals(emptyList<String>(), suggestGear(rules, "Deadlift", 100.0))
        assertEquals(listOf("straps"), suggestGear(rules, "Deadlift", 150.0))
    }

    @Test
    fun absentGearIsNotRecordedAndAnEmptyListIsRecordedNone() {
        assertNull(recordedGear(null))
        assertEquals(emptyList<String>(), recordedGear(emptyList()))
        assertEquals(listOf("straps", "belt"), recordedGear(listOf("belt", "nonsense", "straps", "belt")))
    }
}
