package com.bioscan.fieldterminal.domain

// Phase G4. Plain, framework-free time-series shape for a single exercise
// session's on-demand Health Connect detail -- offsetSeconds is elapsed time
// from the session's own start, not a wall-clock timestamp, so it plots
// directly against a shared "minutes into the session" axis regardless of
// when the session happened. Never persisted to Supabase (same principle
// Step 14's GPX route already established for Drive: fetch fresh on demand,
// store nothing) -- see data/SessionDetailRepository.kt.
data class TimePoint(val offsetSeconds: Long, val value: Double)

data class SessionDetail(
    val heartRate: List<TimePoint>,
    val speedKmh: List<TimePoint>,
    val powerW: List<TimePoint>,
)
