package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.routine.PlacesRepository
import com.bioscan.fieldterminal.data.routine.RoutineRepository
import com.bioscan.fieldterminal.data.routine.RoutineSettingsStore
import com.bioscan.fieldterminal.data.routine.RoutineStream
import com.bioscan.fieldterminal.domain.routine.PlaceCategory
import com.bioscan.fieldterminal.domain.routine.PlacedVisit
import com.bioscan.fieldterminal.domain.routine.RoutineYesterday
import com.bioscan.fieldterminal.domain.routine.localDateOf
import com.bioscan.fieldterminal.domain.routine.routineLines
import com.bioscan.fieldterminal.domain.routine.unloggedExerciseVisits
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.softLoad
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Routine intelligence R5: yesterday's routine on Today, in up to three sentences; the full view is Lab › ROUTINE. Shown
// only once a routine stream is on, and only when yesterday has something to say.
@Composable
fun RoutineTodayCard() {
    val context = LocalContext.current
    val on = remember {
        RoutineSettingsStore.isEnabled(context, RoutineStream.LOCATION) || RoutineSettingsStore.isEnabled(context, RoutineStream.PHONE_USAGE)
    }
    if (!on) return
    val lines by produceState<List<String>>(initialValue = emptyList()) {
        value = softLoad {
            val zone = ZoneId.systemDefault()
            val yesterday = LocalDate.now(zone).minusDays(1)
            val repo = RoutineRepository(SupabaseClientProvider.client)
            val mobility = repo.mobilitySince(yesterday).firstOrNull { it.date == yesterday.toString() }
            val homeIds = PlacesRepository(SupabaseClientProvider.client).loadPlaces()
                .filter { it.category == PlaceCategory.HOME.key }.map { it.id }.toSet()
            val toWork = repo.commutesSince(Instant.now().minusSeconds(28 * 86_400)).filter { it.fromPlaceId in homeIds }
            val yesterdays = toWork.filter { localDateOf(it.startMs, zone) == yesterday }
            val earlier = toWork.filter { localDateOf(it.startMs, zone) < yesterday }.map { it.minutes }.sorted()
            val phone = repo.phoneSince(yesterday).firstOrNull { it.date == yesterday }
            routineLines(
                RoutineYesterday(
                    date = yesterday,
                    coveragePct = mobility?.coveragePct,
                    homeMin = mobility?.minutesByCategory?.get(PlaceCategory.HOME.key),
                    workMin = mobility?.minutesByCategory?.get(PlaceCategory.WORK.key),
                    transitMin = mobility?.minutesByCategory?.get("transit"),
                    commuteMin = yesterdays.takeIf { it.isNotEmpty() }?.sumOf { it.minutes },
                    usualCommuteMin = earlier.takeIf { it.size >= 3 }?.let { it[it.size / 2] },
                    bedWindowMin = phone?.bedWindowMin,
                ),
            )
        } ?: emptyList()
    }
    if (lines.isEmpty()) return
    FTCard(
        title = "YESTERDAY'S ROUTINE",
        info = "Where the day went, the commute against its usual (the median of the last four weeks, from three commutes) " +
            "and the phone in the hour before sleep. Lab › ROUTINE has the week.",
    ) {
        lines.forEach { Text(it, style = FTType.Body, color = FT.TextPrimary) }
    }
}

private val visitFmt = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm")

// Routine intelligence R5 (plan section 5.2, without notifications): visits to an Exercise place with no session recorded,
// over the last 7 days, at the top of the Log. Sessions come from the watch, so the line says what to check, nothing more.
@Composable
fun UnloggedVisitsCard() {
    val context = LocalContext.current
    val on = remember { RoutineSettingsStore.isEnabled(context, RoutineStream.LOCATION) }
    if (!on) return
    val visits by produceState<List<PlacedVisit>>(initialValue = emptyList()) {
        value = softLoad {
            val since = Instant.now().minusSeconds(7 * 86_400)
            val repo = RoutineRepository(SupabaseClientProvider.client)
            val places = PlacesRepository(SupabaseClientProvider.client).loadPlaces()
            unloggedExerciseVisits(repo.visitsSince(since, places), repo.sessionStartsSince(since.minusSeconds(3600)), System.currentTimeMillis())
        } ?: emptyList()
    }
    if (visits.isEmpty()) return
    Column(Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) {
        FTCard(
            title = "UNLOGGED VISITS",
            info = "Visits of 30 minutes or more to a place labelled Exercise, with no session recorded within half an hour of " +
                "them. If you trained, sync the watch or add the workout in Zepp; if not, nothing to do.",
        ) {
            val zone = ZoneId.systemDefault()
            visits.take(5).forEach { v ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(v.label ?: "Exercise place", style = FTType.Body, color = FT.TextPrimary)
                    Text(
                        "${Instant.ofEpochMilli(v.startMs).atZone(zone).format(visitFmt)} · ${(v.endMs - v.startMs) / 60_000} min",
                        style = FTType.Telemetry,
                        color = FT.TextSecondary,
                    )
                }
            }
        }
    }
}
