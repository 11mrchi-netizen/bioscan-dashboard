package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class RunCategoryTest {
    private fun classify(
        route: String? = null,
        type: String? = null,
        sport: String? = "1",
        km: Double = 10.0,
        gain: Double? = 30.0,
        histogram: Map<Int, Double>? = null,
        avgHr: Double? = null,
        z4: Double? = 160.0,
    ) = classifyRun(route, type, sport, km, gain, histogram, avgHr, z4)

    @Test
    fun trailComesFromTheTagOrZeppsTrailSport() {
        assertEquals(RunCategory.TRAIL, classify(route = "trail").category)
        assertEquals(RunCategory.TRAIL, classify(sport = ZEPP_TRAIL_SPORT).category)
        // A long trail run stays trail.
        assertEquals(RunCategory.TRAIL, classify(route = "trail", type = "long").category)
        assertEquals(RunCategory.QUALITY, classify(route = "mixed", type = "hills").category)
    }

    @Test
    fun aTaggedRunTypeDecides() {
        listOf("tempo", "threshold", "intervals", "hills", "race").forEach {
            assertEquals(it, RunClassification(RunCategory.QUALITY, RunCategoryBasis.TAG), classify(type = it, histogram = mapOf(130 to 3600.0)))
        }
        listOf("easy", "long", "recovery").forEach {
            assertEquals(it, RunClassification(RunCategory.EASY, RunCategoryBasis.TAG), classify(type = it, histogram = mapOf(170 to 3600.0)))
        }
    }

    @Test
    fun aBigClimbIsAHillSession() {
        assertEquals(RunClassification(RunCategory.QUALITY, RunCategoryBasis.CLIMB), classify(km = 6.0, gain = 210.0, histogram = mapOf(140 to 2000.0)))
        // 24 m/km is not enough.
        assertEquals(RunCategory.EASY, classify(km = 10.0, gain = 240.0, histogram = mapOf(140 to 2000.0)).category)
    }

    @Test
    fun heartRateTimeAtThresholdDecidesAnUntypedRun() {
        // 25 % of the time at 160 or above: quality; 15 %: easy.
        assertEquals(
            RunClassification(RunCategory.QUALITY, RunCategoryBasis.HEART_RATE_TIME),
            classify(histogram = mapOf(150 to 750.0, 165 to 250.0)),
        )
        assertEquals(RunCategory.EASY, classify(histogram = mapOf(150 to 850.0, 165 to 150.0)).category)
        assertEquals(0.25, shareAtOrAbove(mapOf(150 to 750.0, 160 to 250.0), 160.0)!!, 1e-9)
    }

    @Test
    fun withoutAHistogramTheAverageDecides() {
        assertEquals(RunClassification(RunCategory.QUALITY, RunCategoryBasis.AVERAGE_HEART_RATE), classify(avgHr = 162.0))
        assertEquals(RunClassification(RunCategory.EASY, RunCategoryBasis.AVERAGE_HEART_RATE), classify(avgHr = 150.0))
        assertEquals(RunClassification(RunCategory.EASY, RunCategoryBasis.NO_HEART_RATE), classify())
        assertEquals(RunClassification(RunCategory.EASY, RunCategoryBasis.NO_HEART_RATE), classify(avgHr = 170.0, z4 = null))
    }
}
