package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.routine.PlacesRepository
import com.bioscan.fieldterminal.data.routine.RoutineSettingsStore
import com.bioscan.fieldterminal.data.routine.RoutineStream
import com.bioscan.fieldterminal.domain.routine.ActiveTravelWeek
import com.bioscan.fieldterminal.domain.routine.activeTravel
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.softLoad
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.Instant

// Routine intelligence R4 (docs/routine-intelligence/01-research-and-plan.md, section 5.2): walking and cycling between
// places, beside training load and never added to it. Shown only once the Locations stream is on.
@Composable
fun ActiveTravelCard() {
    val context = LocalContext.current
    val enabled = remember { RoutineSettingsStore.isEnabled(context, RoutineStream.LOCATION) }
    if (!enabled) return
    val week by produceState<ActiveTravelWeek?>(initialValue = null) {
        val now = Instant.now()
        value = softLoad { PlacesRepository(SupabaseClientProvider.client).loadTrips(now.minusSeconds(14 * 86_400)) }
            ?.let { activeTravel(it, now.toEpochMilli()) }
    }
    FTCard(
        title = "ACTIVE TRAVEL",
        info = "Minutes on foot or on a bicycle in trips between places, from location capture. Shown beside training load, " +
            "not added to it: session load needs a session's effort (RPE or heart rate), which a walk to the station does " +
            "not have. Workouts recorded by the watch are not trips and are not counted here.",
    ) {
        val w = week
        if (w == null) {
            Text("No trips recorded yet.", style = FTType.BodySmall, color = FT.TextSecondary)
        } else {
            Line("Last 7 days", "%.0f min · %d trips".format(w.lastWeekMin, w.trips))
            Line("On foot · bicycle", "%.0f · %.0f min".format(w.onFootMin, w.bicycleMin))
            Line("The 7 days before", "%.0f min".format(w.previousWeekMin))
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = FTType.Body, color = FT.TextPrimary)
        Text(value, style = FTType.Telemetry, color = FT.TextSecondary)
    }
}
