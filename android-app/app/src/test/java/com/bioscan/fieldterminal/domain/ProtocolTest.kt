package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private val all = ProtocolLifecycleState.entries

    @Test
    fun testTheFullForwardChainIsReachable() {
        assertTrue(isValidProtocolTransition(ProtocolLifecycleState.PROPOSED, ProtocolLifecycleState.ACTIVE))
        assertTrue(isValidProtocolTransition(ProtocolLifecycleState.ACTIVE, ProtocolLifecycleState.EVALUATING))
        assertTrue(isValidProtocolTransition(ProtocolLifecycleState.EVALUATING, ProtocolLifecycleState.COMPLETED))
    }

    @Test
    fun testStoppedIsReachableFromEverywhereButItself() {
        for (from in all) {
            if (from == ProtocolLifecycleState.STOPPED) {
                for (to in all) assertFalse(isValidProtocolTransition(from, to))
            } else {
                assertTrue("$from -> STOPPED", isValidProtocolTransition(from, ProtocolLifecycleState.STOPPED))
            }
        }
    }

    @Test
    fun testNoSkippingAndNoGoingBackwards() {
        assertFalse(isValidProtocolTransition(ProtocolLifecycleState.PROPOSED, ProtocolLifecycleState.EVALUATING))
        assertFalse(isValidProtocolTransition(ProtocolLifecycleState.ACTIVE, ProtocolLifecycleState.PROPOSED))
    }

    @Test
    fun testFromKeyRoundTripsEveryEntrysKey() {
        for (s in all) assertEquals(s, ProtocolLifecycleState.fromKey(s.key))
        assertEquals(null, ProtocolLifecycleState.fromKey("not_a_real_state"))
    }
}
