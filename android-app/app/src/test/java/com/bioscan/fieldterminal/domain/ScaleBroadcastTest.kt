package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScaleBroadcastTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // Captured with nRF Connect: a finished weigh-in (75.0 kg confirmed) and the idle frame.
    private val weighIn = "0B1212121201015D02EE13A62F6D238F8FB497"
    private val idle = "0B12121212010000000000002A6D238F8FB497"

    @Test
    fun finishedWeighInDecodes() {
        val r = decodeScaleBroadcast(hex(weighIn + ""))
        assertNotNull(r)
        assertEquals(75.0, r!!.weightKg, 1e-9)
        assertEquals(503.0, r.impedanceOhm, 1e-9)
        assertEquals("6D:23:8F:8F:B4:97", r.mac)
    }

    @Test
    fun idleFrameIsNotAReading() = assertNull(decodeScaleBroadcast(hex(idle)))

    @Test
    fun corruptedChecksumIsRejected() = assertNull(decodeScaleBroadcast(hex(weighIn.replaceRange(24, 26, "2E"))))

    @Test
    fun wrongLengthOrHeaderIsRejected() {
        assertNull(decodeScaleBroadcast(hex(weighIn.dropLast(2))))
        assertNull(decodeScaleBroadcast(hex("0C" + weighIn.drop(2))))
    }
}
