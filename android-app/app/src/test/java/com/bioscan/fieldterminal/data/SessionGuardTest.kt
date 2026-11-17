package com.bioscan.fieldterminal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionGuardTest {
    private val key = "sb_publishable_test"

    @Test
    fun `a data request with no user token needs the session`() {
        assertTrue(needsSession("/rest/v1/wearable_daily", null, key))
        assertTrue(needsSession("/rest/v1/rpc/some_function", "Bearer $key", key))
        assertTrue(needsSession("/functions/v1/zepp-extract", null, key))
    }

    @Test
    fun `a request that already carries a user token is left alone`() {
        assertFalse(needsSession("/rest/v1/wearable_daily", "Bearer eyJhbGciOi.user.jwt", key))
    }

    @Test
    fun `sign-in and token refresh are never held back`() {
        assertFalse(needsSession("/auth/v1/token", null, key))
        assertFalse(needsSession("/auth/v1/logout", "Bearer $key", key))
    }

    @Test
    fun `the message says the session, not the network`() {
        assertEquals("The session is not ready yet. Try again in a moment.", NoSessionException().message)
    }
}
