package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Reads the real registry file (unit tests run with the module directory, android-app/app, as the working directory), so
// the Android parser and the shipped data cannot drift apart. Completeness against docs/analysis-layer-2/02 is enforced
// by the Node test (metricRegistry.test.ts); this guards the parse, the fields and the help text.
class MetricRegistryTest {
    private val file = File("../../supabase/functions/_shared/metric-registry/metric-registry.json")
    private val registry by lazy { parseMetricRegistry(file.readText()) }

    @Test
    fun testRegistry_parsesAndEveryEntryHasMethodologyLimitsAndVersion() {
        assertTrue("registry file at ${file.absolutePath}", file.exists())
        assertTrue(registry.size >= 30)
        registry.values.forEach { m ->
            assertTrue("${m.key} methodology", m.methodology.length >= 20)
            assertTrue("${m.key} limitations", m.limitations.isNotEmpty())
            assertTrue("${m.key} version", Regex("""\d+\.\d+\.\d+""").matches(m.methodologyVersion))
            if (m.kind == "composite") assertTrue("${m.key} composite is product-defined", m.productDefined)
            if (m.kind == "modeled" && m.references.isEmpty()) assertTrue("${m.key} modeled needs a basis", m.productDefined)
        }
    }

    @Test
    fun testHelpText_layoutAndProductDefinedLabel() {
        val hrv = registry.getValue("hrv").helpText()
        assertTrue(hrv.contains("Limits:"))
        assertTrue(hrv.contains("References:"))
        assertTrue(hrv.contains("doi:10.1007/s40279-013-0071-8"))
        assertTrue(hrv.endsWith("Methodology v1.0.0"))
        assertTrue(registry.getValue("sleep_index").helpText().contains("Product-defined: not clinically validated."))
    }

    @Test
    fun testParse_toleratesUnknownTopLevelKeys() {
        val parsed = parseMetricRegistry(
            """{"registryVersion":"1.0.0","note":"x","metrics":[{"key":"a","kind":"raw","methodology":"m","methodologyVersion":"1.0.0","extra":1}]}""",
        )
        assertEquals(listOf("a"), parsed.keys.toList())
    }
}
