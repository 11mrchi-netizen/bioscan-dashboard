package com.bioscan.hometerminal.env

import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvModelTest {
    private val t0 = Instant.parse("2026-11-10T00:00:00Z")
    private fun m(metric: EnvMetric, value: Double, afterSeconds: Long = 0, sensor: String = "s1") =
        Measurement(sensor, metric, value, null, t0.plusSeconds(afterSeconds))

    @Test
    fun temperatureIsHundredthsOfADegree() {
        assertEquals(23.5, MatterUnits.temperatureC(2350.0), 1e-9)
        assertEquals(-4.25, MatterUnits.temperatureC(-425.0), 1e-9)
    }

    @Test
    fun humidityIsHundredthsOfAPercentAndOutOfRangeIsDropped() {
        assertEquals(55.0, MatterUnits.humidityPct(5500.0)!!, 1e-9)
        assertNull(MatterUnits.humidityPct(10_001.0))
    }

    @Test
    fun illuminanceFollowsMattersLogScale() {
        // 10,000 × log10(lux) + 1
        assertEquals(1.0, MatterUnits.illuminanceLux(1.0)!!, 1e-9)
        assertEquals(100.0, MatterUnits.illuminanceLux(20_001.0)!!, 1e-6)
        assertEquals(0.0, MatterUnits.illuminanceLux(0.0)!!, 1e-9)
        assertNull(MatterUnits.illuminanceLux(65_535.0))
    }

    @Test
    fun theFirstValueIsWrittenAndAnUnchangedOneIsNot() {
        val gate = SampleGate()
        assertTrue(gate.admit(m(EnvMetric.TEMPERATURE_C, 22.0)))
        assertFalse(gate.admit(m(EnvMetric.TEMPERATURE_C, 22.05, 60)))
        assertTrue(gate.admit(m(EnvMetric.TEMPERATURE_C, 22.1, 120)))
    }

    @Test
    fun theSameValueIsWrittenAgainAfterFifteenMinutes() {
        val gate = SampleGate()
        assertTrue(gate.admit(m(EnvMetric.HUMIDITY_PCT, 50.0)))
        assertFalse(gate.admit(m(EnvMetric.HUMIDITY_PCT, 50.0, 14 * 60)))
        assertTrue(gate.admit(m(EnvMetric.HUMIDITY_PCT, 50.0, 15 * 60)))
    }

    @Test
    fun lightMovesBy10PercentOfTheLastValue() {
        val gate = SampleGate()
        assertTrue(gate.admit(m(EnvMetric.ILLUMINANCE_LUX, 300.0)))
        assertFalse(gate.admit(m(EnvMetric.ILLUMINANCE_LUX, 320.0, 10)))
        assertTrue(gate.admit(m(EnvMetric.ILLUMINANCE_LUX, 340.0, 20)))
    }

    @Test
    fun eachSensorAndMetricIsGatedOnItsOwn() {
        val gate = SampleGate()
        assertTrue(gate.admit(m(EnvMetric.TEMPERATURE_C, 22.0, sensor = "a")))
        assertTrue(gate.admit(m(EnvMetric.TEMPERATURE_C, 22.0, sensor = "b")))
        assertTrue(gate.admit(m(EnvMetric.HUMIDITY_PCT, 22.0, sensor = "a")))
    }

    @Test
    fun theOutboxKeepsOrderAndDropsOnlyWhatWasUploaded() = runBlocking {
        val dir = Files.createTempDirectory("outbox").toFile()
        val box = EnvOutbox(File(dir, "q.jsonl"))
        box.add(listOf("1", "2", "3"))
        val batch = box.peek(2)
        box.add(listOf("4"))
        box.drop(batch.size)
        assertEquals(listOf("1", "2"), batch)
        assertEquals(listOf("3", "4"), box.peek(10))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun aReadingSurvivesTheOutbox() {
        val reading = Measurement("dev", EnvMetric.CO2_PPM, 612.0, 612.0, t0)
        val back = EnvOutbox.decode(EnvOutbox.encode(reading))!!
        assertEquals(QueuedReading("dev", "co2_ppm", t0.toEpochMilli(), 612.0, 612.0), back)
        assertNull(EnvOutbox.decode("not json"))
    }
}
