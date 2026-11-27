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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.routine.AppWeekTotal
import com.bioscan.fieldterminal.data.routine.PlaceRow
import com.bioscan.fieldterminal.data.routine.PlacesRepository
import com.bioscan.fieldterminal.data.routine.RoutineRepository
import com.bioscan.fieldterminal.data.routine.TransitResult
import com.bioscan.fieldterminal.domain.routine.CommuteJourney
import com.bioscan.fieldterminal.domain.routine.CommuteOption
import com.bioscan.fieldterminal.domain.routine.CommuteStats
import com.bioscan.fieldterminal.domain.routine.CommuteWay
import com.bioscan.fieldterminal.domain.routine.PhoneWeek
import com.bioscan.fieldterminal.domain.routine.PlaceCategory
import com.bioscan.fieldterminal.domain.routine.StripDay
import com.bioscan.fieldterminal.domain.routine.StripGroup
import com.bioscan.fieldterminal.domain.routine.commuteJourneys
import com.bioscan.fieldterminal.domain.routine.commuteOptions
import com.bioscan.fieldterminal.domain.routine.commuteStats
import com.bioscan.fieldterminal.domain.routine.homeWorkProblem
import com.bioscan.fieldterminal.domain.routine.phoneWeek
import com.bioscan.fieldterminal.domain.routine.weekStrip
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.components.rememberCacheVersion
import com.bioscan.fieldterminal.ui.screens.routine.ModeBar
import com.bioscan.fieldterminal.ui.screens.routine.ModeLegend
import com.bioscan.fieldterminal.ui.screens.routine.RoutineTimeSection
import com.bioscan.fieldterminal.ui.screens.routine.clock
import com.bioscan.fieldterminal.ui.screens.routine.clockOfMinute
import com.bioscan.fieldterminal.ui.screens.routine.hm
import com.bioscan.fieldterminal.ui.screens.routine.modeLine
import com.bioscan.fieldterminal.ui.screens.settings.PlacesCard
import com.bioscan.fieldterminal.ui.theme.FTType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Routine intelligence R5 (docs/routine-intelligence/01-research-and-plan.md, section 10), now the top of Me › ROUTINE (it was Lab › ROUTINE):
// practices, logged time and the week's time (R7), the day map and the commute with other ways to make it.
// The week by place, the places list and the phone are in the page below it. Views of what was recorded, not findings: what goes with
// what lives on N=1's evidence board, under the contract's rules.
@Composable
fun RoutineTab(onOpenDay: (LocalDate) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val placesRepo = remember { PlacesRepository(SupabaseClientProvider.client) }
    var places by remember { mutableStateOf<List<PlaceRow>>(emptyList()) }
    var journeys by remember { mutableStateOf<List<CommuteJourney>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    val cacheVersion = rememberCacheVersion()
    LaunchedEffect(cacheVersion, reload) {
        scope.launchSafe(onError = { error = describeLoadFailure(it) }) {
            places = placesRepo.loadPlaces()
            val categories = places.associate { it.id to PlaceCategory.of(it.category) }
            journeys = commuteJourneys(placesRepo.tripsSince(Instant.now().minusSeconds(28 * 86_400)), categoryOf = { categories[it] })
            error = null
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp)) {
        error?.let { Text(it, style = FTType.BodySmall, color = FT.Bad) }
        // Routine R7: practices, logged time and the week's time (ui/screens/routine/PracticesCards.kt).
        RoutineTimeSection()
        FTButton("OPEN DAY MAP", modifier = Modifier.fillMaxWidth(), emphasis = FTButtonEmphasis.Primary) { onOpenDay(LocalDate.now()) }
        CommuteCard(journeys, places, onOpenDay, onFixHome = { setup ->
            scope.launchSafe(onError = { error = describeLoadFailure(it) }, onDone = { reload++ }) {
                placesRepo.saveLabel(setup.id, "Home", PlaceCategory.HOME)
            }
        })
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = FTType.Body, color = FT.TextPrimary)
        Text(value, style = FTType.Telemetry, color = FT.TextSecondary)
    }
}

@Composable
private fun DirectionLines(s: CommuteStats) {
    Line(s.way.label, "${hm(s.medianMin)} · ${s.n}×")
    Text(
        "Middle half ${hm(s.q1Min)}-${hm(s.q3Min)}, range ${hm(s.minMin)}-${hm(s.maxMin)} · usually leaves " +
            "${clockOfMinute(s.medianDepartMinOfDay)} · %.1f km".format(s.medianDistanceKm),
        style = FTType.BodySmall,
        color = FT.TextSecondary,
    )
    modeLine(s.medianByMode).takeIf { it.isNotEmpty() }?.let { Text("Typical: $it", style = FTType.BodySmall, color = FT.TextSecondary) }
    s.byDeparture?.let { d ->
        Text(
            "Leaving before ${clockOfMinute(d.splitMinOfDay)}: ${hm(d.earlyMedianMin)} (${d.earlyN}×); from then on: " +
                "${hm(d.lateMedianMin)} (${d.lateN}×). What was recorded, not a cause.",
            style = FTType.BodySmall,
            color = FT.TextSecondary,
        )
    }
}

private val journeyDayFmt = DateTimeFormatter.ofPattern("EEE d MMM")

@Composable
private fun CommuteCard(
    journeys: List<CommuteJourney>?,
    places: List<PlaceRow>,
    onOpenDay: (LocalDate) -> Unit,
    onFixHome: (PlaceRow) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var options by remember { mutableStateOf<List<CommuteOption>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val zone = remember { ZoneId.systemDefault() }
    val home = places.firstOrNull { it.category == PlaceCategory.HOME.key }
    val work = places.firstOrNull { it.category == PlaceCategory.WORK.key }
    val stats = journeys?.let { commuteStats(it, zone) }.orEmpty()

    FTCard(
        title = "COMMUTE",
        info = "Door to door between your Home and Work places over the last four weeks, through stops of up to 15 minutes " +
            "on the way (a longer stop makes two outings). The middle half is the 25th to 75th percentile. Modes come from " +
            "Android's activity recognition; waiting is time standing still on the way. Alternatives are estimates from " +
            "Taipei's MRT stations and travel times and YouBike docks with bikes and free slots right now (TDX): street " +
            "distance is taken as 1.3 times the straight line, walking 80 m/min, YouBike 220 m/min, plus a platform wait. " +
            "TDX only sends whole station lists; your places never leave the phone.",
    ) {
        val problem = homeWorkProblem(places.map { it.toPlace() }, places.firstOrNull { it.source == "home_setting" }?.id)
        if (problem != null) {
            Text(problem, style = FTType.BodySmall, color = FT.Warn)
            val setup = places.firstOrNull { it.source == "home_setting" }
            if (home == null && setup != null) {
                FTButton("MAKE THE SETUP PLACE HOME", modifier = Modifier.fillMaxWidth(), emphasis = FTButtonEmphasis.Primary) { onFixHome(setup) }
            }
            Text("Or label places on the day map (OPEN DAY MAP above) or in Setup › Profile & goals › Data capture › PLACES.", style = FTType.BodySmall, color = FT.TextSecondary)
            return@FTCard
        }
        if (journeys == null) {
            Text("Loading…", style = FTType.BodySmall, color = FT.TextSecondary)
            return@FTCard
        }
        if (journeys.isEmpty()) {
            Text("No commutes recorded in the last four weeks.", style = FTType.BodySmall, color = FT.TextSecondary)
        } else {
            CommuteWay.entries.forEach { w -> stats[w]?.let { DirectionLines(it) } }
            Text("RECENT", style = FTType.Label, color = FT.TextSecondary)
            val recent = journeys.sortedByDescending { it.departMs }.take(10)
            val scale = recent.maxOf { it.minutes }
            recent.forEach { j ->
                val day = Instant.ofEpochMilli(j.departMs).atZone(zone).toLocalDate()
                Column(
                    Modifier.fillMaxWidth().ftClickable(shape = RoundedCornerShape(FT.RadiusSmall)) { onOpenDay(day) }.padding(vertical = 3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "${day.format(journeyDayFmt)} · ${if (j.way == CommuteWay.TO_WORK) "→ work" else "→ home"} · ${clock(j.departMs)}-${clock(j.arriveMs)}",
                            style = FTType.BodySmall,
                            color = FT.TextPrimary,
                        )
                        Text(hm(j.minutes), style = FTType.Telemetry, color = FT.TextPrimary)
                    }
                    ModeBar(j.byMode, scale)
                }
            }
            ModeLegend()
        }
        val usual = stats[CommuteWay.TO_WORK]?.medianMin
        status?.let { Text(it, style = FTType.BodySmall, color = FT.TextSecondary) }
        options?.let { list ->
            Text("OTHER WAYS TO WORK (ESTIMATES)", style = FTType.Label, color = FT.TextSecondary)
            if (list.isEmpty()) Text("No option found near both places.", style = FTType.BodySmall, color = FT.TextSecondary)
            list.forEach { o ->
                val vs = usual?.let { u -> val diff = o.minutes - u; if (diff >= 0) " · ${hm(diff)} longer" else " · ${hm(-diff)} shorter" }.orEmpty()
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Line(o.kind.label, "${hm(o.minutes)}$vs")
                    val via = if (o.via.isEmpty()) "" else o.via.joinToString(" → ") + " · "
                    Text("$via${hm(o.activeMinutes)} on foot or bicycle", style = FTType.BodySmall, color = FT.TextSecondary)
                }
            }
        }
        if (home != null && work != null) {
            FTButton(if (busy) "LOOKING UP…" else "LOOK UP OTHER WAYS", enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                busy = true
                scope.launchSafe(onError = { status = describeLoadFailure(it) }, onDone = { busy = false }) {
                    when (val r = RoutineRepository(SupabaseClientProvider.client).transit()) {
                        is TransitResult.Ready -> {
                            options = commuteOptions(home.lat to home.lon, work.lat to work.lon, r.data.stations, r.data.hops, r.data.docks)
                            status = null
                        }
                        TransitResult.NotConfigured -> status = "Needs a TDX account: set TDX_CLIENT_ID and TDX_CLIENT_SECRET on the transit-alternatives function."
                        is TransitResult.Failed -> status = "TDX could not be reached: ${r.message}"
                    }
                }
            }
        }
    }
}
