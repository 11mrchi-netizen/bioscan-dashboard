package com.bioscan.fieldterminal.ui.screens.events

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AthleteEventsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.events.AthleteEvent
import com.bioscan.fieldterminal.domain.events.EventStatus
import com.bioscan.fieldterminal.domain.events.formatHoursMinutes
import com.bioscan.fieldterminal.domain.trail.TrailChartPoint
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTCardEmphasis
import com.bioscan.fieldterminal.ui.components.LoadErrorCard
import com.bioscan.fieldterminal.ui.components.PinnedPage
import com.bioscan.fieldterminal.ui.components.TrailElevationChart
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.components.rememberLoadGuard
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val EVENT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
private val EVENT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

fun eventWhenLabel(event: AthleteEvent, today: LocalDate = LocalDate.now()): String {
    val days = ChronoUnit.DAYS.between(today, event.date)
    val rel = when {
        days == 0L -> "TODAY"
        days == 1L -> "TOMORROW"
        days > 1 -> "IN $days DAYS"
        days == -1L -> "YESTERDAY"
        else -> "${-days} DAYS AGO"
    }
    return "${event.date.format(EVENT_DATE).uppercase()} · $rel"
}

fun eventSummaryLine(event: AthleteEvent): String = listOfNotNull(
    event.kind.label,
    event.sport.label,
    "PRIORITY ${event.priority.key}",
    event.distanceKm?.let { "%.1f KM".format(it) },
    event.ascentM?.let { "%.0f M UP".format(it) },
    event.plannedDurationMin?.let { (if (event.targetTimeMin != null) "TARGET " else "~") + formatHoursMinutes(it) },
).joinToString(" · ")

@Composable
fun EventScreen(eventId: Long, onBack: () -> Unit, onOpenSession: (Long) -> Unit) {
    val repo = remember { AthleteEventsRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var event by remember { mutableStateOf<AthleteEvent?>(null) }
    var editing by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val guard = rememberLoadGuard()
    LaunchedEffect(guard.attempt) { guard.run { event = repo.load(eventId) } }

    val e = event
    PinnedPage(
        onBack = onBack,
        title = e?.name ?: "EVENT",
        subtitle = e?.let { eventWhenLabel(it) },
        accent = FT.DomainTraining,
    ) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (guard.error != null) {
                LoadErrorCard(guard)
                return@Column
            }
            if (e == null) return@Column
            FTCard(title = "THE DAY", emphasis = FTCardEmphasis.Hero) {
                Text(eventSummaryLine(e), style = FTType.MonoCaption, color = FT.TextPrimary)
                e.start?.let { Text("START ${it.withZoneSameInstant(ZoneId.systemDefault()).format(EVENT_TIME)}", style = FTType.MonoCaption, color = FT.TextSecondary) }
                e.locationName?.let { Text(it, style = FTType.BodySmall, color = FT.TextSecondary) }
                if (e.status != EventStatus.PLANNED) Text(e.status.label, style = FTType.LabelCaps, color = FT.TextSecondary)
                e.notes?.let { Text(it, style = FTType.BodySmall, color = FT.TextSecondary, modifier = Modifier.padding(top = 6.dp)) }
            }
            if (e.course.size >= 2) {
                FTCard(title = "COURSE") {
                    val chart = remember(e.course) {
                        e.course.mapNotNull { p -> p.elevationM?.let { TrailChartPoint(p.distanceM.toLong(), it, null, null, null) } }
                    }
                    TrailElevationChart(chart)
                    Text(
                        listOfNotNull(
                            "%.1f km".format(e.course.last().distanceM / 1000),
                            e.ascentM?.let { "%.0f m up".format(it) },
                            e.descentM?.let { "%.0f m down".format(it) },
                        ).joinToString(" · "),
                        style = FTType.MonoCaption, color = FT.TextSecondary,
                    )
                }
            }
            if (e.aidStations.isNotEmpty()) {
                FTCard(title = "AID STATIONS") {
                    e.aidStations.sortedBy { it.km }.forEach { s ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("%.1f KM".format(s.km), style = FTType.MonoCaption, color = FT.DomainTraining)
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = FTType.BodySmall, color = FT.TextPrimary)
                                Text(
                                    listOfNotNull(
                                        "WATER".takeIf { s.water }, "FOOD".takeIf { s.food }, "DROP BAG".takeIf { s.dropBag },
                                        s.cutoffMin?.let { "CUTOFF ${formatHoursMinutes(it)}" },
                                    ).joinToString(" · ").ifBlank { "NOTHING OFFERED" },
                                    style = FTType.MonoCaption, color = FT.TextSecondary,
                                )
                            }
                        }
                    }
                }
            }
            actionError?.let { Text("Couldn't save: $it", style = FTType.BodySmall, color = FT.Critical) }
            FTButton("EDIT EVENT", emphasis = FTButtonEmphasis.Primary) { editing = true }
            e.exerciseSessionId?.let { sid -> FTButton("OPEN THE RECORDED SESSION") { onOpenSession(sid) } }
            if (e.status == EventStatus.PLANNED && !e.date.isAfter(LocalDate.now())) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FTButton("DID NOT START", emphasis = FTButtonEmphasis.Tertiary, modifier = Modifier.weight(1f)) {
                        scope.launchSafe(onError = { actionError = describeLoadFailure(it) }) { repo.setStatus(eventId, EventStatus.DNS); guard.retry() }
                    }
                    FTButton("CANCELLED", emphasis = FTButtonEmphasis.Tertiary, modifier = Modifier.weight(1f)) {
                        scope.launchSafe(onError = { actionError = describeLoadFailure(it) }) { repo.setStatus(eventId, EventStatus.CANCELLED); guard.retry() }
                    }
                }
            }
        }
    }
    if (editing && e != null) {
        EventFormSheet(
            initial = e,
            onDismiss = { editing = false },
            onSaved = { editing = false; guard.retry() },
            onDeleted = { editing = false; onBack() },
        )
    }
}
