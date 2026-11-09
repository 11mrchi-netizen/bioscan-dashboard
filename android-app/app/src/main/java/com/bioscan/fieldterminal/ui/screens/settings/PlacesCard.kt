package com.bioscan.fieldterminal.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.routine.MobilityDailyRow
import com.bioscan.fieldterminal.data.routine.LocationCollector
import com.bioscan.fieldterminal.data.routine.PlaceRow
import com.bioscan.fieldterminal.data.routine.PlacesRepository
import com.bioscan.fieldterminal.data.routine.TripRow
import com.bioscan.fieldterminal.domain.routine.DEFAULT_PLACE_RADIUS_M
import com.bioscan.fieldterminal.domain.routine.MANUAL_PLACE_RADII_M
import com.bioscan.fieldterminal.domain.routine.PlaceCategory
import com.bioscan.fieldterminal.domain.routine.parseCoordinates
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Routine intelligence R3 (docs/routine-intelligence/01-research-and-plan.md, section 10): name the places the phone has
// found, check the latest day of mobility and the commute against what you remember. Suggestions (Setup's home location,
// weekday office hours, OpenStreetMap around the point) are only offered; nothing is labelled without a tap. ADD PLACE
// names a place from coordinates (pasted, a map link, or where the phone is now) before or after it is visited.
// [showDay] adds the latest day of mobility and the commute (Setup); Lab › ROUTINE shows those in its own cards.
@Composable
fun PlacesCard(showDay: Boolean = true) {
    val scope = rememberCoroutineScope()
    val repo = remember { PlacesRepository(SupabaseClientProvider.client) }
    var reload by remember { mutableIntStateOf(0) }
    var places by remember { mutableStateOf<List<PlaceRow>>(emptyList()) }
    var totals by remember { mutableStateOf<Map<String, Pair<Double, Int>>>(emptyMap()) }
    var commutes by remember { mutableStateOf<List<TripRow>>(emptyList()) }
    var mobility by remember { mutableStateOf<MobilityDailyRow?>(null) }
    var workId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        scope.launchSafe(onError = { error = describeLoadFailure(it) }) {
            places = repo.loadPlaces()
            totals = repo.placeTotals()
            commutes = repo.recentCommutes()
            mobility = repo.latestMobility()
            workId = repo.workSuggestion()
            error = null
        }
    }

    FTCard(
        title = "PLACES",
        info = "Places are found from your visits (10 minutes or more within 150 m). Name them and give each a category: " +
            "commutes are trips between a Home and a Work place, and the day's time by category comes from these labels. " +
            "Suggestions come from your home location in Setup, where weekday office hours were spent, and OpenStreetMap " +
            "around the place (a point rounded to about 100 m). ADD PLACE names a place from its coordinates: it takes over " +
            "the visits already recorded inside its circle.",
    ) {
        error?.let { Text(it, style = FTType.BodySmall, color = FT.Critical) }

        if (showDay) {
            mobility?.let { m -> MobilitySummary(m) }

            val home = places.filter { it.category == PlaceCategory.HOME.key }.map { it.id }.toSet()
            val toWork = commutes.filter { it.fromPlaceId in home }
            val toHome = commutes.filter { it.toPlaceId in home }
            if (commutes.isNotEmpty()) {
                Text("COMMUTE · LAST 14 DAYS", style = FTType.Label, color = FT.TextSecondary)
                CommuteRow("To work", toWork)
                CommuteRow("To home", toHome)
            }
        }

        if (adding) {
            AddPlaceEditor(
                onCancel = { adding = false },
                onSave = { label, category, lat, lon, radius ->
                    scope.launchSafe(onError = { error = describeLoadFailure(it) }, onDone = { adding = false; reload++ }) {
                        repo.addPlace(label, category, lat, lon, radius)
                        // Commute flags follow the new place's category on the trips the worker rebuilds (the last 48 h).
                        runCatching { repo.refreshTrips() }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                    }
                },
            )
        } else {
            FTButton("ADD PLACE", modifier = Modifier.fillMaxWidth(), emphasis = FTButtonEmphasis.Secondary) { adding = true; editing = null }
        }

        val sorted = places.sortedByDescending { totals[it.id]?.first ?: 0.0 }.filter { totals.containsKey(it.id) || it.label != null }
        if (sorted.isEmpty()) {
            Text("No places yet. They appear after the first visits are recorded, or add one above.", style = FTType.BodySmall, color = FT.TextSecondary)
        } else {
            Text("PLACES · LAST 14 DAYS", style = FTType.Label, color = FT.TextSecondary)
            if (sorted.any { it.label == null }) {
                Text("Tap a place to name it and give it a category.", style = FTType.BodySmall, color = FT.TextSecondary)
            }
        }
        sorted.forEach { p ->
            val (hours, visits) = totals[p.id] ?: (0.0 to 0)
            val suggestion = when {
                p.id == workId -> "Work" to PlaceCategory.WORK
                p.osmName != null || p.osmCategory != null -> p.osmName to PlaceCategory.of(p.osmCategory)
                else -> null
            }
            if (editing == p.id) {
                PlaceEditor(
                    place = p,
                    suggestion = suggestion,
                    onCancel = { editing = null },
                    onSave = { label, category ->
                        scope.launchSafe(onError = { error = describeLoadFailure(it) }, onDone = { editing = null; reload++ }) {
                            repo.saveLabel(p.id, label, category)
                        }
                    },
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().ftClickable(shape = RoundedCornerShape(FT.RadiusSmall)) { editing = p.id },
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(p.label ?: "Unlabelled place", style = FTType.Body, color = if (p.label == null) FT.Warning else FT.TextPrimary)
                        Text("%.1f h · %d×".format(hours, visits), style = FTType.Telemetry, color = FT.TextSecondary)
                    }
                    val line = listOfNotNull(
                        PlaceCategory.of(p.category)?.label,
                        suggestion?.takeIf { p.label == null }?.let { (name, cat) -> "suggested: " + listOfNotNull(name, cat?.label).distinct().joinToString(" · ") },
                    ).joinToString(" · ")
                    if (line.isNotEmpty()) Text(line, style = FTType.BodySmall, color = FT.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun MobilitySummary(m: MobilityDailyRow) {
    fun h(key: String) = "%.1f h".format((m.minutesByCategory[key] ?: 0.0) / 60.0)
    Text("LATEST DAY · ${m.date}", style = FTType.Label, color = FT.TextSecondary)
    StatusLine("Covered", "${m.coveragePct} % · ${m.dayType.replace('_', ' ')}", ok = m.coveragePct >= 80)
    StatusLine("Home · Work · Transit", "${h("home")} · ${h("work")} · ${h("transit")}")
    StatusLine("Places · new", "${m.nPlaces} · ${m.newPlaces}")
    StatusLine("Outdoors in daylight (est.)", "%.0f min".format(m.outdoorDaylightMin))
    StatusLine("Like the same weekday", m.routineSimilarity?.let { "%.0f %%".format(it * 100) } ?: "-")
}

@Composable
private fun CommuteRow(label: String, trips: List<TripRow>) {
    if (trips.isEmpty()) return
    val minutes = trips.mapNotNull { t ->
        val s = com.bioscan.fieldterminal.data.routine.parseTimestamp(t.startAt)
        val e = com.bioscan.fieldterminal.data.routine.parseTimestamp(t.endAt)
        if (s == null || e == null) null else (e.toEpochMilli() - s.toEpochMilli()) / 60_000.0
    }.sorted()
    if (minutes.isEmpty()) return
    val median = minutes[minutes.size / 2]
    val active = trips.map { it.activeMin }.sorted()[trips.size / 2]
    StatusLine(label, "%.0f min (%.0f-%.0f) · %.0f active · %d×".format(median, minutes.first(), minutes.last(), active, trips.size))
}

@Composable
private fun StatusLine(label: String, value: String, ok: Boolean? = null) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = FTType.Body, color = FT.TextPrimary)
        Text(value, style = FTType.Telemetry, color = when (ok) { true -> FT.Emerald; false -> FT.Warning; null -> FT.TextSecondary })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceEditor(
    place: PlaceRow,
    suggestion: Pair<String?, PlaceCategory?>?,
    onCancel: () -> Unit,
    onSave: (String?, PlaceCategory?) -> Unit,
) {
    var label by remember(place.id) { mutableStateOf(place.label ?: "") }
    var category by remember(place.id) { mutableStateOf(PlaceCategory.of(place.category)) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FieldTextField(value = label, onValueChange = { label = it }, placeholder = "Name (Home, Office, Gym…)")
        Chips(PlaceCategory.entries, category, { it.label }) { category = it }
        if (suggestion != null) {
            FTButton("USE SUGGESTION", modifier = Modifier.fillMaxWidth(), emphasis = FTButtonEmphasis.Tertiary) {
                suggestion.first?.let { label = it }
                suggestion.second?.let { category = it }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FTButton("CANCEL", modifier = Modifier.weight(1f), onClick = onCancel)
            FTButton("SAVE", modifier = Modifier.weight(1f), emphasis = FTButtonEmphasis.Primary) { onSave(label, category) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Chips(options: List<T>, selected: T?, label: (T) -> String, onPick: (T?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { c ->
            val on = selected == c
            val shape = RoundedCornerShape(FT.RadiusSmall)
            Text(
                label(c),
                style = FTType.Label,
                color = if (on) FT.Emerald else FT.TextSecondary,
                modifier = Modifier
                    .border(FT.BorderWidth, if (on) FT.Emerald else FT.GlassBorder, shape)
                    .background(if (on) FT.Emerald.copy(alpha = 0.12f) else Color.Transparent, shape)
                    .ftClickable(shape = shape) { onPick(if (on) null else c) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

// A place from coordinates: pasted ("25.0330, 121.5654", Google Maps' long-press copy), a map link, or one fix from the
// phone now. Saved only with a name and a point.
@Composable
private fun AddPlaceEditor(
    onCancel: () -> Unit,
    onSave: (String, PlaceCategory?, Double, Double, Double) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<PlaceCategory?>(null) }
    var coords by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf(DEFAULT_PLACE_RADIUS_M) }
    var locating by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val point = parseCoordinates(coords)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("NEW PLACE", style = FTType.Label, color = FT.TextSecondary)
        FieldTextField(value = label, onValueChange = { label = it }, placeholder = "Name (Home, Office, Gym…)")
        FieldTextField(value = coords, onValueChange = { coords = it; note = null }, placeholder = "25.0330, 121.5654 or a map link")
        Text(
            when {
                note != null -> note!!
                point != null -> "%.5f, %.5f".format(point.first, point.second)
                coords.isBlank() -> "In Google Maps, long-press the spot and tap the coordinates to copy them."
                else -> "No coordinates found in that text."
            },
            style = FTType.BodySmall,
            color = if (coords.isNotBlank() && point == null && note == null) FT.Warning else FT.TextSecondary,
        )
        FTButton(if (locating) "LOCATING…" else "USE CURRENT LOCATION", modifier = Modifier.fillMaxWidth(), emphasis = FTButtonEmphasis.Tertiary) {
            if (locating) return@FTButton
            locating = true
            scope.launchSafe(onError = { note = describeLoadFailure(it) }, onDone = { locating = false }) {
                val fix = LocationCollector.currentFix(context)
                if (fix == null) {
                    note = "No location: grant precise location in Data capture, or paste coordinates."
                } else {
                    coords = "%.6f, %.6f".format(java.util.Locale.ROOT, fix.latitude, fix.longitude)
                    note = "Here, to about %.0f m.".format(fix.accuracy)
                }
            }
        }
        Text("RADIUS", style = FTType.Label, color = FT.TextSecondary)
        Chips(MANUAL_PLACE_RADII_M, radius, { "%.0f m".format(it) }) { picked -> picked?.let { radius = it } }
        Text("CATEGORY", style = FTType.Label, color = FT.TextSecondary)
        Chips(PlaceCategory.entries, category, { it.label }) { category = it }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FTButton("CANCEL", modifier = Modifier.weight(1f), onClick = onCancel)
            FTButton(
                "SAVE",
                modifier = Modifier.weight(1f),
                emphasis = FTButtonEmphasis.Primary,
                enabled = label.isNotBlank() && point != null,
            ) { point?.let { (lat, lon) -> onSave(label, category, lat, lon, radius) } }
        }
    }
}
