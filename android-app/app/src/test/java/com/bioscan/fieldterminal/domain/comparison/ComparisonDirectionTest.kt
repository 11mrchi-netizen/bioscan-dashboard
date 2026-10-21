package com.bioscan.fieldterminal.domain.comparison

import com.bioscan.fieldterminal.domain.analysis.ComparisonState
import com.bioscan.fieldterminal.domain.analysis.Directionality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

// Analysis review A3: bands come from the favorable position, ranks use the window the label names, ordinals are right.
class ComparisonDirectionTest {
    private val asOf = LocalDate.of(2026, 10, 12)
    private fun history(values: List<Double>) = values.mapIndexed { i, v -> asOf.minusDays((values.size - i).toLong()) to v }

    @Test
    fun theHighestRestingHeartRateIsBottomNotTop() {
        val past = history((0 until 20).map { 50.0 + it % 5 }) // 50..54
        val high = comparePersonal("resting_heart_rate", 60.0, past, setOf("w"), setOf("w"), "test", asOf)
        assertEquals(ComparisonState.OK, high.state)
        assertEquals(PercentileBand.BOTTOM_DECILE, presentComparison(high).band)
        val low = comparePersonal("resting_heart_rate", 45.0, past, setOf("w"), setOf("w"), "test", asOf)
        assertEquals(PercentileBand.TOP_DECILE, presentComparison(low).band)
    }

    @Test
    fun aHigherBetterMetricKeepsItsRawPosition() {
        val past = history((0 until 20).map { 40.0 + it })
        val top = comparePersonal("hrv", 100.0, past, setOf("w"), setOf("w"), "test", asOf)
        assertEquals(PercentileBand.TOP_DECILE, presentComparison(top).band)
    }

    @Test
    fun aMetricWithNoBetterEndGetsNoBand() {
        val past = history((0 until 20).map { 70.0 + it * 0.1 })
        assertNull(presentComparison(comparePersonal("body_weight", 90.0, past, setOf("w"), setOf("w"), "test", asOf)).band)
    }

    @Test
    fun theRankUsesTheLast28DaysOnly() {
        // 200 old days far above, 20 recent days below today: within the window today ranks first.
        val old = (0 until 200).map { asOf.minusDays(40L + it) to 200.0 }
        val recent = (1..20).map { asOf.minusDays(it.toLong()) to 50.0 }
        val result = comparePersonal("hrv", 60.0, old + recent, setOf("w"), setOf("w"), "test", asOf)
        assertEquals(20, result.rankDenominator)
        assertEquals(100.0, result.percentile!!, 1e-9)
    }

    @Test
    fun ordinalsAreEnglish() {
        assertEquals(listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "71st", "102nd", "111th"),
            listOf(1, 2, 3, 4, 11, 12, 13, 21, 22, 71, 102, 111).map(::ordinal))
    }

    @Test
    fun favorablePercentileMirrorsLowerBetter() {
        val past = history((0 until 20).map { 50.0 + it })
        val r = comparePersonal("resting_heart_rate", 52.0, past, setOf("w"), setOf("w"), "test", asOf)
        assertEquals(100.0 - r.percentile!!, favorablePercentile(r)!!, 1e-9)
        assertEquals(Directionality.LOWER_BETTER, r.directionality)
    }
}
