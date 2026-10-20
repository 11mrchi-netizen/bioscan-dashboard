package com.bioscan.fieldterminal.domain.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class FormStorageTest {

    @Test
    fun everyFindingHasAStoredValueForItsLastTimeLine() {
        val m = analyseRunningForm(SyntheticRunner(RunnerSpec(fps = 120.0)).clip(), FormSetup()).metrics!!
        val values = formMetricValues(m)
        val findings = formFindings(m)
        assertTrue(findings.isNotEmpty())
        findings.forEach { f -> assertTrue("no stored value for ${f.key}", f.key in values) }
    }

    @Test
    fun lastTimeSaysWhenAChangeIsWithinTheAnalysersError() {
        val before = mapOf("shin_angle" to MetricSummary(12.0, 1.0, 9), "cadence" to MetricSummary(168.0, 0.0, 9))
        val on = LocalDate.of(2026, 10, 6)
        assertEquals("Last time 12° (6 Oct)", sinceLastTime("shin_angle", 7.0, before, on))
        assertEquals("Last time 12° (6 Oct), about the same", sinceLastTime("shin_angle", 10.5, before, on))
        assertEquals("Last time 168 spm (6 Oct), about the same", sinceLastTime("cadence", 170.0, before, on))
        assertNull(sinceLastTime("knee_landing", 20.0, before, on))
        assertNull(sinceLastTime("shin_angle", 7.0, null, null))
    }

    @Test
    fun aVideosRecordingTimeIsReadAndAMissingClockIsNoDate() {
        assertEquals(Instant.parse("2026-10-19T07:15:30Z"), parseVideoDate("20261019T071530.000Z"))
        assertEquals(Instant.parse("2026-10-19T07:15:30Z"), parseVideoDate("20261019T071530Z"))
        assertNull(parseVideoDate("19040101T000000.000Z"))
        assertNull(parseVideoDate(null))
        assertNull(parseVideoDate("not a date"))
    }

    @Test
    fun theClipIsMatchedToTheRunItWasFilmedDuring() {
        val day = LocalDate.of(2026, 10, 19)
        val runs = listOf(
            RunChoice(1, day.atTime(6, 30), 40.0, "06:30"),
            RunChoice(2, day.atTime(17, 0), 60.0, "17:00"),
        )
        assertEquals(1L, runForClip(day.atTime(6, 55), runs))
        assertEquals(2L, runForClip(day.atTime(16, 52), runs))
        assertNull(runForClip(day.atTime(12, 0), runs))
        assertNull(runForClip(null, runs))
        assertNull(runForClip(LocalDateTime.of(day, java.time.LocalTime.NOON), emptyList()))
    }
}
