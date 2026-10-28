package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.fuel.FuelSymptom
import org.junit.Assert.assertEquals
import org.junit.Test

class EffectiveGutScoreTest {
    @Test
    fun ticksRaiseTheGutScoreTheToleranceSuggestionSees() {
        assertEquals(1, effectiveGutScore(1, emptySet()))
        assertEquals(3, effectiveGutScore(1, setOf(FuelSymptom.REFLUX)))
        assertEquals(5, effectiveGutScore(1, setOf(FuelSymptom.NAUSEA)))
        assertEquals(7, effectiveGutScore(2, setOf(FuelSymptom.VOMITING, FuelSymptom.REFLUX)))
    }

    @Test
    fun aHigherScoredGutIsNeverLowered() {
        assertEquals(8, effectiveGutScore(8, setOf(FuelSymptom.REFLUX)))
    }
}
