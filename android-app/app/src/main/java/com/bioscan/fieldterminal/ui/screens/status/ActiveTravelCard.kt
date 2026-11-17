package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.routine.PlacesRepository
import com.bioscan.fieldterminal.data.routine.RoutineSettingsStore
import com.bioscan.fieldterminal.data.routine.RoutineStream
import com.bioscan.fieldterminal.domain.routine.ActiveTravelWeek
import com.bioscan.fieldterminal.domain.routine.activeTravel
import com.bioscan.fieldterminal.ui.components.LoadErrorLine
import com.bioscan.fieldterminal.ui.components.produceGuarded
import com.bioscan.fieldterminal.ui.components.rememberLoadGuard
import com.bioscan.fieldterminal.ui.screens.detail.Section
import com.bioscan.fieldterminal.ui.screens.detail.StatTiles
import com.bioscan.fieldterminal.ui.theme.FTType
import java.time.Instant
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Routine intelligence R4 (docs/routine-intelligence/01-research-and-plan.md, section 5.2): walking and cycling between
// places, beside training load and never added to it. Shown only once the Locations stream is on.
@Composable
fun ActiveTravelCard() {
    val context = LocalContext.current
    val enabled = remember { RoutineSettingsStore.isEnabled(context, RoutineStream.LOCATION) }
    if (!enabled) return
    val guard = rememberLoadGuard(refreshOnUpdates = false)
    val week by produceGuarded<ActiveTravelWeek?>(guard, null) {
        val now = Instant.now()
        activeTravel(PlacesRepository(SupabaseClientProvider.client).loadTrips(now.minusSeconds(14 * 86_400)), now.toEpochMilli())
    }
    Section(
        "ACTIVE TRAVEL · 7 DAYS",
        info = "Minutes on foot or on a bicycle in trips between places, from location capture. Shown beside training load, " +
            "not added to it: session load needs a session's effort (RPE or heart rate), which a walk to the station does " +
            "not have. Workouts recorded by the watch are not trips and are not counted here.",
    ) {
        val w = week
        if (guard.error != null) {
            LoadErrorLine(guard, "your trips")
        } else if (w == null) {
            Text("No trips recorded yet.", style = FTType.BodySmall, color = FT.TextSecondary)
        } else {
            StatTiles(
                listOf(
                    Triple("ON FOOT", "%.0f min".format(w.onFootMin), null),
                    Triple("BICYCLE", "%.0f min".format(w.bicycleMin), null),
                    Triple("TRIPS", "${w.trips}", null),
                    Triple("WEEK BEFORE", "%.0f min".format(w.previousWeekMin), "total %.0f min this week".format(w.lastWeekMin)),
                ),
            )
        }
    }
}
