package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.bioscan.fieldterminal.domain.vo2MaxRollingAverage
import com.bioscan.fieldterminal.ui.components.Card
import com.bioscan.fieldterminal.ui.components.DateTrendLine
import com.bioscan.fieldterminal.ui.components.PeriodToggle
import com.bioscan.fieldterminal.ui.components.RangeBar
import com.bioscan.fieldterminal.ui.components.TrendSeries
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles
import com.bioscan.fieldterminal.ui.theme.JetBrainsMono
import com.bioscan.fieldterminal.ui.theme.Saira
import com.bioscan.fieldterminal.ui.theme.SairaCondensed
import java.time.LocalDate

// Step 7 (Phase C), generalized in Phase G3 from runs-only to any exercise
// type via `exercise_sessions` (see data/TrainingRepository.kt). STRENGTH
// finally has a real data source -- Step 7's own finding was that none
// existed anywhere in this project; Health Connect is the first. Kept
// basic here per the user's own framing: counts/minutes this week, not a
// full per-lift breakdown -- that's Phase G5's "combined session detail"
// view, once one exists to link to.
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
        if (!overview.hasAnyEndurance) {
            Text("No endurance sessions logged yet.", style = FieldTextStyles.placeholderBody, color = FieldColors.InkMuted)
        } else {
            Card(title = "ENDURANCE — THIS WEEK") {
                BigValueRow(value = "%.1f".format(overview.thisWeekDistanceKm), unit = "KM")
                RangeBar(value = overview.thisWeekDistanceKm, max = 32.0, watchBelow = null, color = FieldColors.Orange)

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
                    Vo2MaxChart(overview.vo2MaxSeries)
                    Text(
                        "Wearable-estimated, not lab-confirmed.",
                        style = TextStyle(fontFamily = Saira, fontSize = 12.5.sp),
                        color = FieldColors.InkMuted,
                    )
                }
            }

            DistanceTotalsCard(overview)
        }

        Card(title = "STRENGTH") {
            if (overview.hasAnyStrength) {
                BigValueRow(value = "${overview.strengthSessionsThisWeek}", unit = if (overview.strengthSessionsThisWeek == 1) "SESSION" else "SESSIONS")
                StatLine("This week", "${overview.strengthMinutesThisWeek} min")
                Text(
                    "Synced from Health Connect. Per-lift/set detail isn't shown here yet.",
                    style = TextStyle(fontFamily = Saira, fontSize = 12.5.sp),
                    color = FieldColors.InkMuted,
                )
            } else {
                Text(
                    text = "No strength sessions synced yet.",
                    style = TextStyle(fontFamily = Saira, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
                    color = FieldColors.InkMuted,
                )
                Text(
                    text = "Health Connect is this app's real data source for strength training now — " +
                        "log a workout with any Health-Connect-aware app on your phone and it'll show up here after the next sync.",
                    style = TextStyle(fontFamily = Saira, fontSize = 12.5.sp),
                    color = FieldColors.InkMuted,
                )
            }
        }
    }
}

// Distance run over a selectable window -- computed client-side from the
// same `exercise_sessions` rows the ENDURANCE card above already fetched,
// using the existing sumDistanceKmSince(). No new query per period switch.
@Composable
private fun DistanceTotalsCard(overview: TrainingOverview) {
    var period by remember { mutableStateOf(TotalsPeriod.Week) }
    val totalKm = sumDistanceKmSince(overview.enduranceSessions, LocalDate.now(), period.days)

    Card(title = "DISTANCE TOTALS") {
        PeriodToggle(selected = period, onSelect = { period = it })
        BigValueRow(value = "%.1f".format(totalKm), unit = "KM")
        Text(
            "over the last ${period.label.lowercase()}",
            style = TextStyle(fontFamily = Saira, fontSize = 12.5.sp),
            color = FieldColors.InkMuted,
        )
    }
}

// DAV-80. Raw readings are the noisiest series (wearable-estimated, landing
// irregularly) so they get the dimmest line; the 28-day average -- the one
// actually worth reading fitness trend from -- gets the card's own amber
// accent. All three share one y-axis since they're the same unit at
// different smoothing, not different quantities.
@Composable
private fun Vo2MaxChart(series: List<Pair<LocalDate, Double>>) {
    if (series.size < 2) return
    DateTrendLine(
        series = listOf(
            TrendSeries(series, FieldColors.InkMuted),
            TrendSeries(vo2MaxRollingAverage(series, 7), FieldColors.Cyan),
            TrendSeries(vo2MaxRollingAverage(series, 28), FieldColors.Amber),
        ),
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LegendItem("RAW", FieldColors.InkMuted)
        LegendItem("7D AVG", FieldColors.Cyan)
        LegendItem("28D AVG", FieldColors.Amber)
    }
}

@Composable
private fun LegendItem(label: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(
            modifier = Modifier
                .padding(top = 1.dp)
                .height(3.dp)
                .width(12.dp)
                .background(color),
        )
        Text(label, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 10.5.sp), color = FieldColors.InkMuted)
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
        Text(label, style = TextStyle(fontFamily = Saira, fontSize = 14.5.sp), color = FieldColors.InkMuted)
        Text(value, style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 14.5.sp), color = FieldColors.Ink)
    }
}

private fun formatPace(minPerKm: Double): String {
    val min = minPerKm.toInt()
    val sec = ((minPerKm - min) * 60).toInt()
    return "%d:%02d /km".format(min, sec)
}
