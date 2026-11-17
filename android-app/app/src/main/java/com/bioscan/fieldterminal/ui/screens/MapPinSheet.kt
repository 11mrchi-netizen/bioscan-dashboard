package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AddEntryRepository
import com.bioscan.fieldterminal.data.MapSettingsStore
import com.bioscan.fieldterminal.data.PeopleRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.PersonRow
import com.bioscan.fieldterminal.data.model.PlannedRouteRow
import com.bioscan.fieldterminal.domain.GpxPoint
import com.bioscan.fieldterminal.domain.MapEvent
import com.bioscan.fieldterminal.domain.MapEventCategory
import com.bioscan.fieldterminal.domain.MapPin
import com.bioscan.fieldterminal.domain.classifyMapEvent
import com.bioscan.fieldterminal.domain.classifySessionKind
import com.bioscan.fieldterminal.domain.parseSessionZonedDateTime
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.theme.FTType
import kotlinx.coroutines.launch
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// The Map: the sheet a pin opens, with the person met there and the encounters logged with them.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PinDetailSheet(
    pin: MapPin,
    routePoints: List<GpxPoint>?,
    routeError: String?,
    plannedPreview: PlannedRouteRow?,
    weatherState: WeatherUiState,
    onRequestWeather: () -> Unit,
    onDismiss: () -> Unit,
) {
    val event = pin.event
    val category = remember(event.id, event.colorId, event.title) { classifyMapEvent(event.colorId, event.title) }
    val context = LocalContext.current
    val home = remember { MapSettingsStore.getHome(context) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = FT.SheetShape,
        containerColor = FT.Surface,
        contentColor = FT.TextPrimary,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                when (category) {
                    MapEventCategory.Training -> sessionCardTitle(classifySessionKind(event.title))
                    MapEventCategory.Encounter -> "ENCOUNTER"
                    MapEventCategory.Social -> "SOCIAL EVENT"
                    MapEventCategory.Other -> "EVENT"
                },
                style = sheetHeaderTitleStyle,
                color = FT.DomainMap,
            )
            Text(
                text = event.title.trim().ifBlank { "Untitled event" },
                style = FTType.SectionTitle,
                color = FT.TextPrimary,
            )
            event.description.takeIf { it.isNotBlank() }?.let {
                Text(it, style = FTType.BodySmall, color = FT.TextSecondary)
            }
            Text(
                text = formatEventMeta(event),
                style = FTType.Value,
                color = FT.TextSecondary,
            )
            Text(
                text = if (pin.isHomeFallback) "Pinned at home — this event has no location." else (event.location ?: ""),
                style = FTType.Caption,
                color = FT.TextSecondary,
            )

            when (category) {
                MapEventCategory.Training -> {
                    // DAV-149: a cached DAV-148 preview (real Mountain Index/
                    // KM-effort/grade-band terrain analysis) takes priority
                    // over the live naive GPX summary below when one exists
                    // for this event -- the live fetch stays as the fallback
                    // for an event the pipeline hasn't reached yet, per this
                    // ticket's own "design so future metrics can be added
                    // without changing the route-preview contract."
                    when {
                        plannedPreview != null -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                plannedRouteSummary(plannedPreview),
                                style = FTType.Value,
                                color = FT.DomainTraining,
                            )
                            plannedPreview.confidenceTier?.let { tier ->
                                val staleSuffix = if (plannedPreview.isStale) " · MAY BE STALE" else ""
                                Text(
                                    "CONFIDENCE: ${tier.uppercase()}$staleSuffix",
                                    style = FTType.MonoCaption,
                                    color = if (plannedPreview.isStale) FT.Warn else FT.TextSecondary,
                                )
                            }
                        }
                        routeError != null -> Text(
                            "Couldn't load the route (${routeError}).",
                            style = FTType.BodySmall,
                            color = FT.TextSecondary,
                        )
                        routePoints != null -> Text(
                            routeSummary(routePoints),
                            style = FTType.Value,
                            color = FT.DomainTraining,
                        )
                        event.gpxLink == null -> Text(
                            "No route file linked to this session.",
                            style = FTType.Caption,
                            color = FT.TextSecondary,
                        )
                    }
                    WeatherRow(weatherState, onClick = onRequestWeather)
                }
                MapEventCategory.Encounter, MapEventCategory.Social -> PartnerSection(event)
                MapEventCategory.Other -> {}
            }

            if (!pin.isHomeFallback && home != null) {
                FTButton(label = "DIRECTIONS") {
                    context.startActivity(directionsIntent(home.first, home.second, pin.lat, pin.lon))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

// NoMatch shows a search field over the 168-row `people` table
// plus a create-new action -- per the user's own choice, not create-only,
// since a typo or nickname in the calendar title shouldn't force a
// duplicate partner record. Deliberately no "change match" action once
// Matched -- a wrong auto-match is a real, accepted rough edge for this
// phase's "keep it minimal" scope, not solved here.
internal sealed interface PartnerUiState {
    data object Loading : PartnerUiState
    data class Matched(val person: PersonRow) : PartnerUiState
    data object NoMatch : PartnerUiState
    data class Error(val message: String) : PartnerUiState
}

@Composable
private fun PartnerSection(event: MapEvent) {
    val repo = remember { PeopleRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var state by remember(event.id) { mutableStateOf<PartnerUiState>(PartnerUiState.Loading) }
    var query by remember(event.id) { mutableStateOf("") }
    var searchResults by remember(event.id) { mutableStateOf<List<PersonRow>>(emptyList()) }
    var creating by remember(event.id) { mutableStateOf(false) }

    LaunchedEffect(event.id, event.title) {
        state = PartnerUiState.Loading
        state = try {
            repo.findByNameInTitle(event.title)?.let { PartnerUiState.Matched(it) } ?: PartnerUiState.NoMatch
        } catch (e: Exception) {
            PartnerUiState.Error(e.message ?: "Couldn't check for a matching partner.")
        }
    }

    LaunchedEffect(query, state) {
        searchResults = if (state is PartnerUiState.NoMatch && query.isNotBlank()) {
            try {
                repo.search(query)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    FTCard(title = "PARTNER") {
        when (val s = state) {
            PartnerUiState.Loading -> Text(
                "Checking this event's title for a partner match…",
                style = FTType.BodySmall,
                color = FT.TextSecondary,
            )
            is PartnerUiState.Matched -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    s.person.name,
                    style = FTType.RowTitle,
                    color = FT.TextPrimary,
                )
                EncounterLogRow(event = event, personId = s.person.id)
            }
            is PartnerUiState.Error -> Text(
                "Couldn't check for a matching partner (${s.message}).",
                style = FTType.BodySmall,
                color = FT.TextSecondary,
            )
            PartnerUiState.NoMatch -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "No partner matched in this event's title.",
                    style = FTType.BodySmall,
                    color = FT.TextSecondary,
                )
                FieldTextField(value = query, onValueChange = { query = it }, placeholder = "Search partners…")
                searchResults.forEach { person ->
                    Text(
                        person.name,
                        style = FTType.BodySmall,
                        color = FT.DomainMap,
                        modifier = Modifier
                            .fillMaxWidth()
                            .ftClickable {
                                state = PartnerUiState.Matched(person)
                            }
                            .padding(vertical = 6.dp),
                    )
                }
                if (query.isNotBlank() && !creating) {
                    FTButton(label = "NEW PARTNER: \"${query.trim()}\"") {
                        creating = true
                        scope.launch {
                            state = try {
                                PartnerUiState.Matched(repo.createPerson(query.trim()))
                            } catch (e: Exception) {
                                PartnerUiState.Error(e.message ?: "Couldn't create this partner.")
                            } finally {
                                creating = false
                            }
                        }
                    }
                }
            }
        }
    }
}

// Minimal by the user's own choice -- date (from the event) +
// optional type/notes, same 3 real fields the Log tab's own EncounterForm
// collects (see AddEntrySheet.kt), just with person_id and the real
// calendar title threaded through too. The encounters table's richer unused
// columns (activities, ratings, location_type, duration) stay untouched, as
// does `status` (left at its 'logged' default -- the 'pending' state is
// explicitly not used by this phase).
@Composable
private fun EncounterLogRow(event: MapEvent, personId: Long) {
    var type by remember(event.id) { mutableStateOf("") }
    var notes by remember(event.id) { mutableStateOf("") }
    var saving by remember(event.id) { mutableStateOf(false) }
    var saved by remember(event.id) { mutableStateOf(false) }
    var error by remember(event.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    if (saved) {
        Text(
            "Encounter logged.",
            style = FTType.RowTitle,
            color = FT.Ok,
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("LOG ENCOUNTER", style = sheetActionLabelStyle, color = FT.TextSecondary)
        FieldTextField(value = type, onValueChange = { type = it }, placeholder = "Type (optional) — e.g. date, call, hangout")
        FieldTextField(value = notes, onValueChange = { notes = it }, placeholder = "Notes (optional)", singleLine = false)
        error?.let {
            Text("Couldn't log this encounter (${it}).", style = FTType.Caption, color = FT.TextSecondary)
        }
        FTButton(label = if (saving) "SAVING…" else "LOG ENCOUNTER") {
            if (!saving) {
                saving = true
                error = null
                scope.launch {
                    try {
                        val date = parseSessionZonedDateTime(event.startIso).toLocalDate().toString()
                        AddEntryRepository(SupabaseClientProvider.client).addEncounter(
                            date = date,
                            encounterType = type.trim().ifBlank { null },
                            notes = notes.trim().ifBlank { null },
                            personId = personId,
                            calendarEventTitle = event.title,
                        )
                        saved = true
                    } catch (e: Exception) {
                        error = e.message ?: "Unknown error."
                    } finally {
                        saving = false
                    }
                }
            }
        }
    }
}
