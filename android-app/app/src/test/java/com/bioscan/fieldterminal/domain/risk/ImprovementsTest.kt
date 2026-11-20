package com.bioscan.fieldterminal.domain.risk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ImprovementsTest {
    private val start = LocalDate.of(2026, 11, 1)

    @Test
    fun lowersMapToTheAirMetricsAndPm25AlsoReadsIndoors() {
        assertEquals(listOf(ImprovementMetric.PM25, ImprovementMetric.PM25_INDOOR, ImprovementMetric.CO2), metricsFor(listOf("pm25", "co2", "plastics")))
        assertEquals(emptyList<ImprovementMetric>(), metricsFor(listOf("mould")))
    }

    @Test
    fun theFourWeeksBeforeAreComparedWithTheDaysSince() {
        val days = (-40..13).map { d ->
            val date = start.plusDays(d.toLong())
            AirDayValues(date, mapOf("pm25" to if (d < 0) 20.0 else 10.0, "co2" to 1200.0))
        }
        val r = beforeAfter(days, start, listOf(ImprovementMetric.PM25, ImprovementMetric.CO2, ImprovementMetric.PM25_INDOOR), start.plusDays(13))
        val pm = r.first { it.metric == ImprovementMetric.PM25 }
        assertEquals(28, pm.beforeDays) // only the 4 weeks before count, not all 40 days
        assertEquals(14, pm.afterDays)
        assertEquals(20.0, pm.before!!, 1e-9)
        assertEquals(10.0, pm.after!!, 1e-9)
        assertEquals(-0.5, pm.change!!, 1e-9)
        assertEquals(0.0, r.first { it.metric == ImprovementMetric.CO2 }.change!!, 1e-9)
        val indoor = r.first { it.metric == ImprovementMetric.PM25_INDOOR }
        assertFalse(indoor.readable)
        assertNull(indoor.change)
    }

    @Test
    fun tooFewDaysSinceTheStartIsNotReadYet() {
        val days = (-28..3).map { d -> AirDayValues(start.plusDays(d.toLong()), mapOf("pm25" to 12.0)) }
        val r = beforeAfter(days, start, listOf(ImprovementMetric.PM25), start.plusDays(3)).single()
        assertEquals(4, r.afterDays)
        assertFalse(r.readable)
        assertTrue(improvementKey("purifier") == "air:purifier")
    }
}
