package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterventionTest {
    private val all = InterventionLifecycleState.entries

    @Test
    fun testFullForwardChainIsReachableOneStepAtATime() {
        val chain = listOf(
            InterventionLifecycleState.PROPOSED,
            InterventionLifecycleState.BASELINE,
            InterventionLifecycleState.ACTIVE,
            InterventionLifecycleState.EVALUATING,
        )
        for (i in 0 until chain.size - 1) {
            assertTrue("${chain[i]} -> ${chain[i + 1]}", isValidInterventionTransition(chain[i], chain[i + 1]))
        }
        for (outcome in listOf(InterventionLifecycleState.VALIDATED, InterventionLifecycleState.MAINTAINED, InterventionLifecycleState.MODIFIED, InterventionLifecycleState.STOPPED)) {
            assertTrue("EVALUATING -> $outcome", isValidInterventionTransition(InterventionLifecycleState.EVALUATING, outcome))
        }
    }

    @Test
    fun testStoppedIsReachableFromEveryNonTerminalStateAndHasNoTransitionsOut() {
        for (from in all) {
            if (from == InterventionLifecycleState.STOPPED) {
                for (to in all) assertFalse("STOPPED -> $to", isValidInterventionTransition(from, to))
            } else {
                assertTrue("$from -> STOPPED", isValidInterventionTransition(from, InterventionLifecycleState.STOPPED))
            }
        }
    }

    @Test
    fun testValidatedMaintainedModifiedAreTerminalHere() {
        for (from in listOf(InterventionLifecycleState.VALIDATED, InterventionLifecycleState.MAINTAINED, InterventionLifecycleState.MODIFIED)) {
            for (to in all) {
                assertEquals("$from -> $to", to == InterventionLifecycleState.STOPPED, isValidInterventionTransition(from, to))
            }
        }
    }

    @Test
    fun testNoSkippingAStepAndNoGoingBackwards() {
        assertFalse(isValidInterventionTransition(InterventionLifecycleState.PROPOSED, InterventionLifecycleState.ACTIVE))
        assertFalse(isValidInterventionTransition(InterventionLifecycleState.PROPOSED, InterventionLifecycleState.EVALUATING))
        assertFalse(isValidInterventionTransition(InterventionLifecycleState.ACTIVE, InterventionLifecycleState.PROPOSED))
        assertFalse(isValidInterventionTransition(InterventionLifecycleState.EVALUATING, InterventionLifecycleState.BASELINE))
        assertFalse(isValidInterventionTransition(InterventionLifecycleState.ACTIVE, InterventionLifecycleState.BASELINE))
    }

    @Test
    fun testAStateIsNeverItsOwnTransition() {
        for (s in all) assertFalse(s.name, isValidInterventionTransition(s, s))
    }

    @Test
    fun testFromKeyRoundTripsEveryEntrysKey() {
        for (s in all) assertEquals(s, InterventionLifecycleState.fromKey(s.key))
        assertEquals(null, InterventionLifecycleState.fromKey("not_a_real_state"))
    }
}
