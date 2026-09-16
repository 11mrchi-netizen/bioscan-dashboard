package com.bioscan.fieldterminal.domain

// Phase G4. Plain, framework-free time-series shape for a single exercise
// session's on-demand Health Connect detail -- offsetSeconds is elapsed time
// from the session's own start, not a wall-clock timestamp, so it plots
// directly against a shared "minutes into the session" axis regardless of
// when the session happened. Never persisted to Supabase (same principle
// Step 14's GPX route already established for Drive: fetch fresh on demand,
// store nothing) -- see data/SessionDetailRepository.kt.
data class TimePoint(val offsetSeconds: Long, val value: Double)

// caloriesKcal (Phase G5) is a running sum, not a raw sample series like the
// other three -- ActiveCaloriesBurnedRecord only reports kcal per interval
// (same interval-not-sample shape as ElevationGainedRecord), so
// SessionDetailRepository sums successive intervals into a stepped
// cumulative curve rather than reading anything HC calls a "sample."
data class SessionDetail(
    val heartRate: List<TimePoint>,
    val speedKmh: List<TimePoint>,
    val powerW: List<TimePoint>,
    val caloriesKcal: List<TimePoint>,
)
