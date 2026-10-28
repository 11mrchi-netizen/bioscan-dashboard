package com.bioscan.fieldterminal.ui.components

import com.bioscan.fieldterminal.domain.MetricState
import com.bioscan.fieldterminal.domain.analysis.Directionality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WindowStateWordTest {
    private val steady = listOf(50.0, 52.0, 48.0, 51.0, 49.0, 50.0, 53.0, 47.0)

    @Test
    fun underFiveReadingsIsBuilding() {
        assertEquals(MetricState.Building, windowStateWord(listOf(1.0, 2.0, 3.0), Directionality.HIGHER_BETTER).state)
        assertNull(windowBaseline(listOf(1.0, 2.0)))
    }

    @Test
    fun aReadingInsideTheBandIsWithinBand() {
        val w = windowStateWord(steady + 50.5, Directionality.HIGHER_BETTER)
        assertEquals("WITHIN BAND", w.word)
        assertEquals(MetricState.Neutral, w.state)
    }

    @Test
    fun aReadingBeyondTheLimitsIsNamedAndColouredByWhatItMeans() {
        val high = windowStateWord(steady + 70.0, Directionality.HIGHER_BETTER)
        assertEquals("ABOVE BAND", high.word)
        assertEquals(MetricState.Optimal, high.state)
        val low = windowStateWord(steady + 30.0, Directionality.HIGHER_BETTER)
        assertEquals("BELOW BAND", low.word)
        assertEquals(MetricState.Warning, low.state)
        // For resting heart rate, higher is the worse direction.
        assertEquals(MetricState.Warning, windowStateWord(steady + 70.0, Directionality.LOWER_BETTER).state)
    }
}
