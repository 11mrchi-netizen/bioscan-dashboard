package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthEventsTest {

    @Test
    fun activeAndMonitoredEventsAreOpen() {
        assertTrue(isHealthEventOpen("active"))
        assertTrue(isHealthEventOpen("monitoring"))
    }

    @Test
    fun aResolvedEventIsClosedAtOnce() {
        // The status alone decides: a knee resolved three days ago no longer pins the Status figure.
        assertFalse(isHealthEventOpen("resolved"))
        assertFalse(isHealthEventOpen(""))
    }
}
