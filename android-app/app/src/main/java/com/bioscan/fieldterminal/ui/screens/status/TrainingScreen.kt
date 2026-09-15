package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TrainingOverview
import com.bioscan.fieldterminal.data.TrainingRepository
import com.bioscan.fieldterminal.domain.TotalsPeriod
import com.bioscan.fieldterminal.domain.sumDistanceKmSince
import com.bioscan.fieldterminal.ui.components.Card
import com.bioscan.fieldterminal.ui.components.PeriodToggle
import com.bioscan.fieldterminal.ui.components.RangeBar
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles
import com.bioscan.fieldterminal.ui.theme.JetBrainsMono
import com.bioscan.fieldterminal.ui.theme.Saira
import com.bioscan.fieldterminal.ui.theme.SairaCondensed
import java.time.LocalDate

// Step 7 (Phase C). Real Endurance data from `runs`/`wearable_daily` -- data
// index.html's own fetchDashboardData() already fetches into
// DASHBOARD_DATA.runs, but its Training panel's render() never actually
// reads from it (confirmed by reading the function directly): every number
// shown there, strength AND endurance alike, is a hardcoded literal from a
// one-off narrative pass ("17.9km this week", "+9.4% pace trend", every
// squat/deadlift/pull-up PR), not a live query.
//
// Strength has no live Supabase source at all -- no exercises/lifts table
// exists; the web panel's own "SOURCE: LIVE — get_exercise_history per lift"
// label refers to a Wellness Project MCP tool only ever run manually in a
// chat, never synced into a queryable table. Shown here as an honest empty
// state rather than porting the web's stale hardcoded numbers as if real.
// See ROADMAP.md P8 Step 7 for the full finding.
@Composable
fun TrainingScreen() {
    var overview by remember { mutableStateOf<TrainingOverview?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        overview = TrainingRepository(SupabaseClientProvider.client).loadOverview()
        isLoading = false
    }

    when {
        isLoading -> Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = FieldColors.Amber)
        }
        else -> TrainingContent(overview!!)
    }
}

@Composable
private fun TrainingContent(overview: TrainingOverview) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (!overview.hasAnyRuns) {
            Text("No runs logged yet.", style = FieldTextStyles.placeholderBody, color = FieldColors.InkMuted)
        } else {
            Card(title = "ENDURANCE — THIS WEEK") {
                BigValueRow(value = "%.1f".format(overview.thisWeekDistanceKm), unit = "KM")
                RangeBar(value = overview.thisWeekDistanceKm, max = 32.0, watchBelow = null, color = FieldColors.Cyan)

                overview.avgPaceThisWeek?.let { pace ->
                    StatLine("Avg pace this week", formatPace(pace))
                }
                overview.longestRunKm?.let { longest ->
                    StatLine("Longest run (all-time)", "%.2f km".format(longest))
                }
                StatLine("4-week avg", "%.1f km/wk".format(overview.fourWeekAvgKmPerWeek))
            }

            overview.latestVo2Max?.let { vo2 ->
                Card(title = "VO2MAX (EST.)") {
                    BigValueRow(value = "%.1f".format(vo2), unit = "")
                    Text(
                        "Wearable-estimated, not lab-confirmed.",
                        style = TextStyle(fontFamily = Saira, fontSize = 11.sp),
                        color = FieldColors.InkMuted,
                    )
                }
            }

            DistanceTotalsCard(overview)
        }

        Card(title = "STRENGTH") {
            Text(
                text = "No data source yet.",
                style = TextStyle(fontFamily = Saira, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp),
                color = FieldColors.InkMuted,
            )
            Text(
                text = "Per-lift history isn't synced to a Supabase table this app can read — it's only ever pulled manually in a Wellness Project chat, never stored anywhere queryable. Nothing to show honestly until that changes.",
                style = TextStyle(fontFamily = Saira, fontSize = 11.sp),
                color = FieldColors.InkMuted,
            )
        }
    }
}

// Distance run over a selectable window -- computed client-side from the
// same `runs` rows the ENDURANCE card above already fetched, using the
// existing sumDistanceKmSince(). No new query per period switch.
@Composable
private fun DistanceTotalsCard(overview: TrainingOverview) {
    var period by remember { mutableStateOf(TotalsPeriod.Week) }
    val totalKm = sumDistanceKmSince(overview.runs, LocalDate.now(), period.days)

    Card(title = "DISTANCE TOTALS") {
        PeriodToggle(selected = period, onSelect = { period = it })
        BigValueRow(value = "%.1f".format(totalKm), unit = "KM")
        Text(
            "over the last ${period.label.lowercase()}",
            style = TextStyle(fontFamily = Saira, fontSize = 11.sp),
            color = FieldColors.InkMuted,
        )
    }
}

@Composable
private fun BigValueRow(value: String, unit: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = value,
            style = TextStyle(fontFamily = SairaCondensed, fontWeight = FontWeight.Bold, fontSize = 34.sp),
            color = FieldColors.Ink,
        )
        if (unit.isNotEmpty()) {
            Text(
                text = " $unit",
                style = FieldTextStyles.headerContext,
                color = FieldColors.InkMuted,
                modifier = Modifier.padding(bottom = 5.dp),
            )
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = TextStyle(fontFamily = Saira, fontSize = 13.sp), color = FieldColors.InkMuted)
        Text(value, style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 13.sp), color = FieldColors.Ink)
    }
}

private fun formatPace(minPerKm: Double): String {
    val min = minPerKm.toInt()
    val sec = ((minPerKm - min) * 60).toInt()
    return "%d:%02d /km".format(min, sec)
}
