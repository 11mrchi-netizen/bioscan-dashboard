package com.bioscan.fieldterminal.domain

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Pool
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.ui.graphics.vector.ImageVector
import com.bioscan.fieldterminal.data.model.ExerciseSessionRow

// DAV-282: one reusable icon/label vocabulary over exercise_sessions.type +
// details.routeType, replacing the raw string comparisons duplicated across
// Training.kt, TrainingRepository.kt, MapScreen.kt and SessionDetailScreen.kt.
// `type` itself is the closed vocabulary healthconnect/HealthConnectExerciseTypes.kt
// already maps Health Connect into (run/walk/hike/ride/swim/strength/yoga/other);
// CONDITIONING and CLIMBING have no ingestion path producing them yet -- included
// because DAV-282 requires coverage for them once DAV-294's conditioning-session
// work (or a future Zepp code) starts populating those values.
enum class SportType(val label: String, val icon: ImageVector) {
    RUN("Run", Icons.AutoMirrored.Filled.DirectionsRun),
    TRAIL_RUN("Trail run", Icons.Filled.Terrain),
    WALK("Walk", Icons.AutoMirrored.Filled.DirectionsWalk),
    HIKE("Hike", Icons.Filled.Hiking),
    RIDE("Ride", Icons.AutoMirrored.Filled.DirectionsBike),
    SWIM("Swim", Icons.Filled.Pool),
    STRENGTH("Strength", Icons.Filled.FitnessCenter),
    CONDITIONING("Conditioning", Icons.Filled.Whatshot),
    CLIMBING("Climbing", Icons.Filled.Landscape),
    MOBILITY("Mobility", Icons.Filled.SelfImprovement),
    OTHER("Other", Icons.Filled.Help);

    companion object {
        // routeType == "trail" is the user-confirmed signal (ROUTE_TYPE_OPTIONS in
        // AddEntrySheet.kt, and the MARK AS TRAIL action writes exactly this value).
        // An untagged run that merely *looks* like a trail run (suspectedTrailReason)
        // stays RUN here -- that suspicion is deliberately detection-only until a
        // person confirms it, same convention this codebase already follows for
        // reconcileDistanceWithSpeed.
        fun from(type: String, routeType: String?): SportType = when (type) {
            "run" -> if (routeType == "trail") TRAIL_RUN else RUN
            "walk" -> WALK
            "hike" -> HIKE
            "ride" -> RIDE
            "swim" -> SWIM
            "strength" -> STRENGTH
            "yoga" -> MOBILITY
            "conditioning" -> CONDITIONING
            "climbing" -> CLIMBING
            else -> OTHER
        }

        fun from(session: ExerciseSessionRow): SportType = from(session.type, session.details.routeType)
    }
}
