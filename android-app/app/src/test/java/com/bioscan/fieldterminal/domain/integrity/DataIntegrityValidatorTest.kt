package com.bioscan.fieldterminal.domain.integrity

import com.bioscan.fieldterminal.data.model.ExerciseSessionRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class DataIntegrityValidatorTest {

    @Test
    fun testDetectsDuplicateSessions() {
        // DAV-153: September 20 duplicate run
        val sessions = listOf(
            ExerciseSessionRow(
                type = "run",
                startTime = "2026-09-20T08:00:15Z",
                durationMin = 52.4,
                distanceKm = 10.25,
                source = "health_connect",
            ),
            ExerciseSessionRow(
                type = "run",
                startTime = "2026-09-20T08:01:02Z",
                durationMin = 52.0,
                distanceKm = 10.20,
                source = "health_connect",
            ),
        )

        val issues = DataIntegrityValidator.validateExerciseSessions(sessions)
        val dupIssues = issues.filter { it.category == IntegrityCategory.DUPLICATE_RECORD }
        assertEquals(1, dupIssues.size)
        assertTrue(dupIssues.first().message.contains("Potential duplicate run"))
    }

    @Test
    fun testDetectsStaleSyncAndWriteBack() {
        // DAV-154: Health Connect write-back stopped on September 16
        val now = Instant.parse("2026-09-21T12:00:00Z")
        val lastWriteBack = Instant.parse("2026-09-16T21:00:00Z") // 5 days stale
        val lastReadSync = now.minus(Duration.ofHours(2)) // fresh

        val issues = DataIntegrityValidator.validateSyncStaleness(lastReadSync, lastWriteBack, now)
        val writeBackIssues = issues.filter { it.scope == "write_back" }
        assertEquals(1, writeBackIssues.size)
        assertEquals(IntegritySeverity.ERROR, writeBackIssues.first().severity)
        assertTrue(writeBackIssues.first().message.contains("Health Connect write-back has stalled"))
    }

    @Test
    fun testDetectsMissingVsZeroConfusion() {
        val issues = DataIntegrityValidator.validateDailyWearables(
            date = "2026-09-21",
            restingHeartRate = 0.0, // Erroneous 0 instead of null
            hrvRmssd = 45.0,
            spo2Percentage = 0.0, // Erroneous 0 instead of null
        )

        val zeroIssues = issues.filter { it.category == IntegrityCategory.MISSING_VS_ZERO }
        assertEquals(2, zeroIssues.size)
    }

    @Test
    fun testDetectsDistanceSpeedInconsistency() {
        // The real 2026-09-20 row -- passes hasPlausiblePace (16.1 km/h is
        // under the 22 km/h foot-speed ceiling) and isn't a cross-row
        // duplicate (only one session that day), but its own avg_speed_kmh
        // disagrees with the summed distance by ~3.25x. WARNING, not ERROR:
        // a broader account-wide scan found 62 rows past this same
        // threshold, most of them plausible technical trail runs -- this
        // check flags a disagreement worth a human look, not a confirmed bug.
        val sessions = listOf(
            ExerciseSessionRow(
                type = "run",
                startTime = "2026-09-20T11:31:22Z",
                durationMin = 249.0,
                distanceKm = 66.8707763748878,
                avgSpeedKmh = 4.958999991416932,
                source = "health_connect",
            ),
        )

        val issues = DataIntegrityValidator.validateExerciseSessions(sessions)
        val consistencyIssues = issues.filter { it.scope == "distance_speed_consistency" }
        assertEquals(1, consistencyIssues.size)
        assertEquals(IntegritySeverity.WARNING, consistencyIssues.first().severity)
    }

    @Test
    fun testDetectsInvalidRanges() {
        val sessions = listOf(
            ExerciseSessionRow(
                type = "run",
                startTime = "2026-09-21T07:00:00Z",
                durationMin = -10.0, // Negative duration
                distanceKm = -5.0, // Negative distance
                source = "manual",
            ),
        )

        val issues = DataIntegrityValidator.validateExerciseSessions(sessions)
        val rangeErrors = issues.filter { it.category == IntegrityCategory.INVALID_RANGE }
        assertEquals(2, rangeErrors.size)
    }
}
