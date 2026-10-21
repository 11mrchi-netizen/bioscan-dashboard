package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthGateViewTest {
    @Test
    fun onceShownTheAppStaysThroughTransientStates() {
        assertEquals(GateView.APP_RECONNECTING, gateView(AuthState.INITIALIZING, appShown = true))
        assertEquals(GateView.APP_RECONNECTING, gateView(AuthState.REFRESH_NETWORK_ERROR, appShown = true))
        assertTrue(gateView(AuthState.INITIALIZING, true).showsApp)
    }

    @Test
    fun aColdStartWaitsForTheSession() {
        assertEquals(GateView.LOADING, gateView(AuthState.INITIALIZING, appShown = false))
        assertEquals(GateView.OFFLINE, gateView(AuthState.REFRESH_NETWORK_ERROR, appShown = false))
        assertEquals(GateView.APP, gateView(AuthState.AUTHENTICATED, appShown = false))
    }

    @Test
    fun aSessionReallyGoneAsksToSignIn() {
        for (shown in listOf(true, false)) {
            assertEquals(GateView.SIGN_IN, gateView(AuthState.NOT_AUTHENTICATED, shown))
            assertEquals(GateView.SESSION_EXPIRED, gateView(AuthState.REFRESH_FAILED, shown))
        }
    }
}
