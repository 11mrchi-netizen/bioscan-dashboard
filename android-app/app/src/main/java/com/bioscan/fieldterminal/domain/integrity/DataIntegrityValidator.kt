package com.bioscan.fieldterminal.domain.integrity

import com.bioscan.fieldterminal.data.model.ExerciseSessionRow
import com.bioscan.fieldterminal.domain.DISTANCE_SPEED_DIVERGENCE_FACTOR
import com.bioscan.fieldterminal.domain.MAX_FOOT_SPEED_KMH
import com.bioscan.fieldterminal.domain.SAME_RUN_DURATION_TOLERANCE_MIN
import com.bioscan.fieldterminal.domain.SAME_RUN_START_TOLERANCE_MIN
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

// DAV-179: Cross-domain data-quality and provenance checks engine.
// Provides reusable integrity and validation checks that protect the analytical foundation
// across Health Connect, manual logs, Zepp/Amazfit, nutrition, and future pipelines.
object DataIntegrityValidator {

    fun validateExerciseSessions(sessions: List<ExerciseSessionRow>): List<IntegrityIssue> {
        val issues = mutableListOf<IntegrityIssue>()

        // 1. Check individual session invariants
        sessions.forEach { s ->
            val id = "session:${s.type}:${s.startTime.take(19)}"

            // Provenance check
            if (s.source.isNullOrBlank()) {
                issues.add(
                    IntegrityIssue(
                        category = IntegrityCategory.PROVENANCE_MISSING,
                        domain = "exercise_sessions",
                        scope = "session_source",
                        recordIdentifier = id,
                        severity = IntegritySeverity.WARNING,
                        message = "Session lacks explicit provenance/source identifier",
                    )
                )
            }

            // Value bounds check
            val distance = s.distanceKm
            val duration = s.durationMin
            if (distance != null && distance < 0.0) {
                issues.add(
                    IntegrityIssue(
                        category = IntegrityCategory.INVALID_RANGE,
                        domain = "exercise_sessions",
                        scope = "distance_km",
                        recordIdentifier = id,
                        severity = IntegritySeverity.ERROR,
                        message = "Session has negative distance: $distance km",
                    )
                )
            }
            if (duration != null && duration <= 0.0) {
                issues.add(
                    IntegrityIssue(
                        category = IntegrityCategory.INVALID_RANGE,
                        domain = "exercise_sessions",
                        scope = "duration_min",
                        recordIdentifier = id,
                        severity = IntegritySeverity.ERROR,
                        message = "Session has non-positive duration: $duration min",
                    )
                )
            }

            // Implausible running pace check
            if (s.type == "run" && distance != null && duration != null && duration > 0) {
                val speedKmh = distance / (duration / 60.0)
                if (speedKmh > MAX_FOOT_SPEED_KMH) {
                    issues.add(
                        IntegrityIssue(
                            category = IntegrityCategory.INVALID_RANGE,
                            domain = "exercise_sessions",
                            scope = "running_pace",
                            recordIdentifier = id,
                            severity = IntegritySeverity.ERROR,
                            message = "Session exceeds maximum sustained foot speed: %.2f km/h > %.1f km/h".format(speedKmh, MAX_FOOT_SPEED_KMH),
                            details = mapOf("speed_kmh" to "%.2f".format(speedKmh)),
                        )
                    )
                }
            }

            // DAV-153 follow-up: distance/duration-implied speed vs. the
            // session's own independently-measured avg_speed_kmh. Catches
            // the real 2026-09-20 case (16.1 km/h implied vs. 4.96 km/h
            // measured) that MAX_FOOT_SPEED_KMH alone misses -- that check
            // only catches physically-impossible speeds, not internally
            // inconsistent ones. Flags already-synced rows retroactively,
            // not just new ingestion (see HealthConnectExerciseSyncRepository's
            // reconcileDistanceWithSpeed() for the write-time fix).
            if (distance != null && duration != null && duration > 0 && s.avgSpeedKmh != null && s.avgSpeedKmh > 0) {
                val impliedSpeedKmh = distance / (duration / 60.0)
                val speedImpliedKm = s.avgSpeedKmh * (duration / 60.0)
                if (speedImpliedKm > 0 && distance / speedImpliedKm > DISTANCE_SPEED_DIVERGENCE_FACTOR) {
                    issues.add(
                        IntegrityIssue(
                            category = IntegrityCategory.INVALID_RANGE,
                            domain = "exercise_sessions",
                            scope = "distance_speed_consistency",
                            recordIdentifier = id,
                            severity = IntegritySeverity.ERROR,
                            message = "Distance implies %.1f km/h but measured avg speed is %.1f km/h -- distance likely over-counted".format(impliedSpeedKmh, s.avgSpeedKmh),
                            details = mapOf(
                                "implied_speed_kmh" to "%.2f".format(impliedSpeedKmh),
                                "measured_speed_kmh" to "%.2f".format(s.avgSpeedKmh),
                                "speed_implied_distance_km" to "%.2f".format(speedImpliedKm),
                            ),
                        )
                    )
                }
            }
        }

        // 2. Cross-session duplicate detection (DAV-153 class issues)
        val sorted = sessions.filter { it.type == "run" }
            .sortedBy { runCatching { OffsetDateTime.parse(it.startTime).toInstant() }.getOrNull() }

        for (i in sorted.indices) {
            val a = sorted[i]
            val aStart = runCatching { OffsetDateTime.parse(a.startTime) }.getOrNull() ?: continue
            val aDur = a.durationMin ?: 0.0

            for (j in i + 1 until sorted.size) {
                val b = sorted[j]
                val bStart = runCatching { OffsetDateTime.parse(b.startTime) }.getOrNull() ?: continue
                val bDur = b.durationMin ?: 0.0

                if (aStart.toLocalDate() != bStart.toLocalDate()) break

                val startDiffMin = kotlin.math.abs(Duration.between(aStart, bStart).toMinutes())
                val durDiffMin = kotlin.math.abs(aDur - bDur)
                val durTolerance = maxOf(SAME_RUN_DURATION_TOLERANCE_MIN, minOf(aDur, bDur) * 0.2)

                if (startDiffMin <= SAME_RUN_START_TOLERANCE_MIN && durDiffMin <= durTolerance) {
                    issues.add(
                        IntegrityIssue(
                            category = IntegrityCategory.DUPLICATE_RECORD,
                            domain = "exercise_sessions",
                            scope = "run_duplicate",
                            recordIdentifier = "run:${a.startTime.take(19)}_and_${b.startTime.take(19)}",
                            severity = IntegritySeverity.WARNING,
                            message = "Potential duplicate run detected on ${aStart.toLocalDate()} within $startDiffMin min start delta",
                            details = mapOf(
                                "session_a_source" to (a.source ?: "unknown"),
                                "session_b_source" to (b.source ?: "unknown"),
                                "session_a_distance" to "${a.distanceKm ?: 0.0}",
                                "session_b_distance" to "${b.distanceKm ?: 0.0}",
                            ),
                        )
                    )
                }
            }
        }

        return issues
    }

    fun validateSyncStaleness(
        lastReadSyncAt: Instant?,
        lastWriteBackAt: Instant?,
        now: Instant = Instant.now(),
        staleThresholdHours: Long = 24,
    ): List<IntegrityIssue> {
        val issues = mutableListOf<IntegrityIssue>()

        if (lastReadSyncAt == null) {
            issues.add(
                IntegrityIssue(
                    category = IntegrityCategory.STALE_SYNC,
                    domain = "sync",
                    scope = "read_sync",
                    recordIdentifier = "sync_log:read",
                    severity = IntegritySeverity.WARNING,
                    message = "No Health Connect read sync watermark found",
                )
            )
        } else {
            val hoursSinceRead = Duration.between(lastReadSyncAt, now).toHours()
            if (hoursSinceRead > staleThresholdHours) {
                issues.add(
                    IntegrityIssue(
                        category = IntegrityCategory.STALE_SYNC,
                        domain = "sync",
                        scope = "read_sync",
                        recordIdentifier = "sync_log:read",
                        severity = IntegritySeverity.WARNING,
                        message = "Health Connect read sync is stale ($hoursSinceRead hours since last sync)",
                        details = mapOf("hours_stale" to hoursSinceRead.toString()),
                    )
                )
            }
        }

        if (lastWriteBackAt == null) {
            issues.add(
                IntegrityIssue(
                    category = IntegrityCategory.STALE_SYNC,
                    domain = "sync",
                    scope = "write_back",
                    recordIdentifier = "sync_log:write_back",
                    severity = IntegritySeverity.ERROR,
                    message = "No Health Connect write-back watermark found (write-back inactive)",
                )
            )
        } else {
            val hoursSinceWrite = Duration.between(lastWriteBackAt, now).toHours()
            if (hoursSinceWrite > staleThresholdHours) {
                issues.add(
                    IntegrityIssue(
                        category = IntegrityCategory.STALE_SYNC,
                        domain = "sync",
                        scope = "write_back",
                        recordIdentifier = "sync_log:write_back",
                        severity = IntegritySeverity.ERROR,
                        message = "Health Connect write-back has stalled ($hoursSinceWrite hours since last write)",
                        details = mapOf("hours_stale" to hoursSinceWrite.toString()),
                    )
                )
            }
        }

        return issues
    }

    fun validateDailyWearables(
        date: String,
        restingHeartRate: Double?,
        hrvRmssd: Double?,
        spo2Percentage: Double?,
    ): List<IntegrityIssue> {
        val issues = mutableListOf<IntegrityIssue>()
        val id = "wearable_daily:$date"

        if (restingHeartRate != null) {
            if (restingHeartRate <= 0.0) {
                issues.add(
                    IntegrityIssue(
                        category = IntegrityCategory.MISSING_VS_ZERO,
                        domain = "wearable",
                        scope = "resting_heart_rate",
                        recordIdentifier = id,
                        severity = IntegritySeverity.ERROR,
                        message = "Resting HR recorded as 0 bpm instead of null/missing",
                    )
                )
            } else if (restingHeartRate < 25.0 || restingHeartRate > 220.0) {
                issues.add(
                    IntegrityIssue(
                        category = IntegrityCategory.INVALID_RANGE,
                        domain = "wearable",
                        scope = "resting_heart_rate",
                        recordIdentifier = id,
                        severity = IntegritySeverity.ERROR,
                        message = "Resting HR outside plausible physiological bounds: $restingHeartRate bpm",
                    )
                )
            }
        }

        if (spo2Percentage != null) {
            if (spo2Percentage <= 0.0) {
                issues.add(
                    IntegrityIssue(
                        category = IntegrityCategory.MISSING_VS_ZERO,
                        domain = "wearable",
                        scope = "spo2",
                        recordIdentifier = id,
                        severity = IntegritySeverity.ERROR,
                        message = "SpO2 recorded as 0% instead of null/missing",
                    )
                )
            } else if (spo2Percentage > 100.0 || spo2Percentage < 50.0) {
                issues.add(
                    IntegrityIssue(
                        category = IntegrityCategory.INVALID_RANGE,
                        domain = "wearable",
                        scope = "spo2",
                        recordIdentifier = id,
                        severity = IntegritySeverity.ERROR,
                        message = "SpO2 outside physiological bounds: $spo2Percentage%",
                    )
                )
            }
        }

        return issues
    }

    fun validateNutritionMeal(
        mealId: Long,
        calories: Double?,
        proteinG: Double?,
        carbsG: Double?,
        fatG: Double?,
    ): List<IntegrityIssue> {
        val issues = mutableListOf<IntegrityIssue>()
        val id = "meals:$mealId"

        listOf("calories" to calories, "protein_g" to proteinG, "carbs_g" to carbsG, "fat_g" to fatG).forEach { (field, value) ->
            if (value != null && value < 0.0) {
                issues.add(
                    IntegrityIssue(
                        category = IntegrityCategory.INVALID_RANGE,
                        domain = "nutrition",
                        scope = field,
                        recordIdentifier = id,
                        severity = IntegritySeverity.ERROR,
                        message = "Nutrient $field cannot be negative: $value",
                    )
                )
            }
        }

        if (calories != null && calories > 5000.0) {
            issues.add(
                IntegrityIssue(
                    category = IntegrityCategory.INVALID_RANGE,
                    domain = "nutrition",
                    scope = "calories",
                    recordIdentifier = id,
                    severity = IntegritySeverity.WARNING,
                    message = "Extreme single-meal caloric value: $calories kcal",
                )
            )
        }

        return issues
    }
}
