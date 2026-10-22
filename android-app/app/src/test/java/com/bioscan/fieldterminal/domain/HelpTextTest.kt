package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// The help dialogs open on a short text and keep the registry's full methodology, limits and references one tap away
// (InfoHelpButton). This keeps the opening short for every registry entry.
class HelpTextTest {
    private val registry by lazy {
        parseMetricRegistry(File("../../supabase/functions/_shared/metric-registry/metric-registry.json").readText())
    }

    @Test
    fun `the summary is whole sentences up to the limit, at least the first`() {
        val text = "First sentence here. Second one follows. Third is longer than the limit allows for sure."
        assertEquals("First sentence here.", summarySentences(text, maxChars = 25))
        assertEquals("First sentence here. Second one follows.", summarySentences(text, maxChars = 45))
        assertEquals(text, summarySentences(text, maxChars = 500))
        // A first sentence longer than the limit is kept whole rather than cut mid-sentence.
        assertEquals("First sentence here.", summarySentences(text, maxChars = 5))
        // Decimals and abbreviations followed by a lower-case word are not sentence ends.
        assertEquals("HRV is 2.5 ms e.g. today.", summarySentences("HRV is 2.5 ms e.g. today. Next.", maxChars = 26))
    }

    @Test
    fun `every registry entry opens on a short summary`() {
        registry.values.forEach { m ->
            val s = m.summary()
            assertTrue("${m.key}: empty summary", s.isNotBlank())
            assertTrue("${m.key}: summary ${s.length} chars: $s", s.length <= 420)
        }
    }
}
