package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.ExerciseSessionRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TrainingTest {

    @Test
    fun testVo2MaxTimeframeFiltering() {
        val today = LocalDate.of(2026, 9, 21)
        val series = listOf(
            LocalDate.of(2026, 5, 1) to 48.0, // ~4.5 months ago
            LocalDate.of(2026, 7, 1) to 49.0, // ~2.5 months ago
            LocalDate.of(2026, 8, 1) to 50.0, // ~1.5 months ago (inside 2M)
            LocalDate.of(2026, 9, 15) to 51.0, // ~6 days ago (inside 2M)
        )

        val data2M = prepareVo2MaxTrendData(series, Vo2MaxTimeframe.TwoMonths, today)
        assertEquals(2, data2M.raw.size)
        assertEquals(LocalDate.of(2026, 8, 1), data2M.raw[0].first)
        assertEquals(LocalDate.of(2026, 9, 15), data2M.raw[1].first)

        val data6M = prepareVo2MaxTrendData(series, Vo2MaxTimeframe.SixMonths, today)
        assertEquals(4, data6M.raw.size)

        val dataAll = prepareVo2MaxTrendData(series, Vo2MaxTimeframe.All, today)
        assertEquals(4, dataAll.raw.size)
    }

    @Test
    fun testSeptember20DoubledDistanceRegression() {
        // DAV-153: The September 20 run was recorded twice via Health Connect with slight time offset
        val today = LocalDate.of(2026, 9, 21)
        val sessions = listOf(
            ExerciseSessionRow(
                type = "run",
                startTime = "2026-09-20T08:00:15Z",
                durationMin = 52.4,
                distanceKm = 10.25,
                avgHr = 158.0,
                source = "health_connect",
            ),
            ExerciseSessionRow(
                type = "run",
                startTime = "2026-09-20T08:01:02Z",
                durationMin = 52.0,
                distanceKm = 10.20,
                avgHr = null,
                source = "health_connect",
            ),
        )

        val deduped = dedupeRunSessions(sessions)
        assertEquals(1, deduped.size)
        // Session with higher fidelity (has avgHr, higher distance) is retained
        assertEquals(10.25, deduped.first().distanceKm ?: 0.0, 0.001)

        val weeklyDistance = sumDistanceKmSince(deduped, today, 7)
        assertEquals(10.25, weeklyDistance, 0.001)
    }

    @Test
    fun testManualTakesPrecedenceOverHealthConnectDuplicate() {
        val sessions = listOf(
            ExerciseSessionRow(
                type = "run",
                startTime = "2026-09-10T07:30:00Z",
                durationMin = 45.0,
                distanceKm = 7.5,
                source = "manual",
            ),
            ExerciseSessionRow(
                type = "run",
                startTime = "2026-09-10T07:30:45Z",
                durationMin = 44.8,
                distanceKm = 14.2, // corrupted HC distance before fix
                source = "health_connect",
            ),
        )

        val deduped = dedupeRunSessions(sessions)
        assertEquals(1, deduped.size)
        assertEquals("manual", deduped.first().source)
        assertEquals(7.5, deduped.first().distanceKm ?: 0.0, 0.001)
    }

    @Test
    fun testTwoLegitimateRunsOnSameDayPreserved() {
        val today = LocalDate.of(2026, 9, 21)
        val sessions = listOf(
            ExerciseSessionRow(
                type = "run",
                startTime = "2026-09-20T07:00:00Z",
                durationMin = 30.0,
                distanceKm = 5.0,
                source = "health_connect",
            ),
            ExerciseSessionRow(
                type = "run",
                startTime = "2026-09-20T18:00:00Z",
                durationMin = 35.0,
                distanceKm = 6.0,
                source = "health_connect",
            ),
        )

        val deduped = dedupeRunSessions(sessions)
        assertEquals(2, deduped.size)
        val totalDistance = sumDistanceKmSince(deduped, today, 7)
        assertEquals(11.0, totalDistance, 0.001)
    }

    @Test
    fun testReconcileDistanceWithSpeedUsesRealSeptember20Case() {
        // The real 2026-09-20 row: 66.87km / 249min summed distance, but its
        // own avg_speed_kmh (from independent SpeedRecord samples, consistent
        // with the row's 1118m elevation gain / trail route_type) implies
        // only ~20.6km. This slipped past dedupeRunSessions (only one row
        // that day) and hasPlausiblePace (16.1 km/h is under the 22 km/h
        // foot-speed ceiling) -- found live on-device, not by either shipped
        // regression test.
        val reconciled = reconcileDistanceWithSpeed(distanceKm = 66.8707763748878, durationMin = 249.0, avgSpeedKmh = 4.958999991416932)
        assertEquals(20.58, reconciled, 0.01)
    }

    @Test
    fun testReconcileDistanceWithSpeedLeavesConsistentDistanceAlone() {
        // A real, unremarkable run: implied and measured speed roughly agree.
        val reconciled = reconcileDistanceWithSpeed(distanceKm = 13.02, durationMin = 82.45, avgSpeedKmh = 9.5)
        assertEquals(13.02, reconciled, 0.001)
    }

    @Test
    fun testPlausiblePaceFiltering() {
        val impossiblePaceRun = ExerciseSessionRow(
            type = "run",
            startTime = "2026-09-18T08:00:00Z",
            durationMin = 82.0,
            distanceKm = 38.49, // 28.16 km/h -> impossible sustained foot speed
            source = "health_connect",
        )

        val deduped = dedupeRunSessions(listOf(impossiblePaceRun))
        assertTrue(deduped.isEmpty())
    }
}
