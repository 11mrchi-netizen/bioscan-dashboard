package com.bioscan.fieldterminal.ui.screens.fuel

import com.bioscan.fieldterminal.domain.fuel.FuelBlock
import org.junit.Assert.assertEquals
import org.junit.Test

class BlockSummaryTest {
    @Test
    fun clockShowsMinutesFromTheStart() {
        assertEquals("0:30", clock(30))
        assertEquals("1:05", clock(65))
        assertEquals("-0:30", clock(-30))
    }

    @Test
    fun aBlockReadsAsWhatHowMuchAndHowOften() {
        val gel = FuelBlock("g", "Gel", "gel", 30, 30, 10, carbsG = 25.0, sodiumMg = 50.0)
        assertEquals("25 g carbs · 50 mg Na · 10 × every 30 min from 0:30", blockSummary(gel))
        val pre = FuelBlock("p", "Pre", "preworkout", -30, 0, 1, caffeineMg = 200.0)
        assertEquals("200 mg caffeine · once at -0:30", blockSummary(pre))
    }
}
