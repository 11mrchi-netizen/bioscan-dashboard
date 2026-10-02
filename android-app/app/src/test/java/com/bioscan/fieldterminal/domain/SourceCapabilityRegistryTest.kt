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

    @Test
    fun sleepScoreResolvesToZepp() {
        // sleep_score is Zepp-only (Health Connect has no equivalent).
        val resolved = liveSourceFor("sleep_score")
        assertEquals(DataSource.ZEPP, resolved?.source)
        assertEquals("sleep_daily.score", resolved?.supabaseColumn)
    }

    @Test
    fun sleepDurationResolvesToHealthConnect() {
        // Both Zepp and Health Connect are now LIVE for sleep_duration;
        // liveSourceFor returns the first match, which is Health Connect.
        val resolved = liveSourceFor("sleep_duration")
        assertEquals(DataSource.HEALTH_CONNECT, resolved?.source)
    }

    @Test
    fun zeppHeartRateRhrIsLive() {
        // Zepp's daily heart_rate (resting HR from band_data) is now LIVE.
        // liveSourceFor("heart_rate") is not in Health Connect's list (it
        // uses "resting_heart_rate"), so Zepp is the only match.
        val resolved = liveSourceFor("heart_rate")
        assertEquals(DataSource.ZEPP, resolved?.source)
    }
}
