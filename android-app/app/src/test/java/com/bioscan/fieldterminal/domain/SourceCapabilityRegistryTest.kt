package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceCapabilityRegistryTest {

    @Test
    fun spo2ResolvesToHealthConnectNotBrokenZepp() {
        // Both sources have an "spo2" entry -- Health Connect LIVE, Zepp
        // BROKEN (still 404s). Must resolve to the one that actually works.
        val resolved = liveSourceFor("spo2")
        assertEquals(DataSource.HEALTH_CONNECT, resolved?.source)
    }

    @Test
    fun zeppNativeDerivedMetricsAreLive() {
        assertEquals(DataSource.ZEPP, liveSourceFor("gap_min_per_km")?.source)
        assertEquals(MetricKind.MODELED, liveSourceFor("gap_min_per_km")?.kind)
    }

    @Test
    fun unknownMetricResolvesToNothing() {
        assertNull(liveSourceFor("not_a_real_metric"))
    }

    @Test
    fun brokenZeppTrainingLoadHasNoLiveEntry() {
        // training_load only exists as a Zepp entry, and it's BROKEN -- no
        // source should be reported live for it.
        assertNull(liveSourceFor("training_load"))
    }
}
