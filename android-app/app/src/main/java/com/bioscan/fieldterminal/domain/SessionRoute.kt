package com.bioscan.fieldterminal.domain

// Phase G5. Plain, framework-free shape for one point of a session's route --
// see data/SessionDetailRepository.kt for where this gets built from Health
// Connect's ExerciseRoute.
// elevationM is nullable -- Health Connect's own ExerciseRoute.Location
// leaves altitude unset for some route points (confirmed by the Kotlin
// compiler rejecting a non-null assumption here, not assumed up front), so
// callers building an elevation profile need to filter, not default to 0.
data class RoutePoint(val offsetSeconds: Long, val lat: Double, val lon: Double, val elevationM: Double?)

// A session's route needs a separate per-session consent grant beyond this
// app's bulk Health Connect permissions -- flagged as an open question in
// Phase G4, resolved here: Health Connect reports which of these three
// states a given session is in directly on the record itself, no guessing
// needed. ConsentRequired means SessionDetailScreen must show a real "view
// route" action that launches Health Connect's own consent screen -- this
// app cannot read the route silently even though it already holds the bulk
// exercise-read grant.
sealed interface RouteAvailability {
    data object ConsentRequired : RouteAvailability
    data object NoRoute : RouteAvailability
    data class Available(val points: List<RoutePoint>) : RouteAvailability
}
