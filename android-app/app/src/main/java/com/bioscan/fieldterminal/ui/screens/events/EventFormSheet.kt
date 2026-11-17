package com.bioscan.fieldterminal.ui.screens.events

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AthleteEventsRepository
import com.bioscan.fieldterminal.data.GeocodingRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.withCourse
import com.bioscan.fieldterminal.domain.events.AidStation
import com.bioscan.fieldterminal.domain.events.AthleteEvent
import com.bioscan.fieldterminal.domain.events.EventKind
import com.bioscan.fieldterminal.domain.events.EventPriority
import com.bioscan.fieldterminal.domain.events.EventSport
import com.bioscan.fieldterminal.domain.events.EventStatus
import com.bioscan.fieldterminal.domain.events.formatHoursMinutes
import com.bioscan.fieldterminal.domain.events.parseHoursMinutes
import com.bioscan.fieldterminal.domain.events.validateEvent
import com.bioscan.fieldterminal.domain.trail.CoursePoint
import com.bioscan.fieldterminal.domain.trail.courseProfileFromGpx
import com.bioscan.fieldterminal.ui.components.CourseSaver
import com.bioscan.fieldterminal.ui.components.DateField
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTFlow
import com.bioscan.fieldterminal.ui.components.FlowPage
import com.bioscan.fieldterminal.ui.components.FlowPager
import com.bioscan.fieldterminal.ui.components.FlowQuestion
import com.bioscan.fieldterminal.ui.components.rememberFlowState
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTConfirmDestructive
import com.bioscan.fieldterminal.ui.components.FTDropdown
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.NullableEventSaver
import com.bioscan.fieldterminal.ui.components.SegmentedToggle
import com.bioscan.fieldterminal.ui.components.TimeField
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.util.ExternalActivityGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Add or edit an event (domain/events/AthleteEvent.kt): what it is, when, how long it should take, the course (a GPX file
// picked from the phone, kept as a compact profile) and the aid stations. Fuel and hydration plans are built from this.

private val StationsSaver = listSaver<SnapshotStateList<AidStationDraft>, String>(
    save = { list -> list.map { listOf(it.name, it.km, it.cutoff, it.water.toString(), it.food.toString(), it.dropBag.toString()).joinToString("\u001F") } },
    restore = { saved ->
        mutableStateListOf<AidStationDraft>().apply {
            saved.forEach { s ->
                val f = s.split('\u001F')
                add(AidStationDraft(f.getOrElse(0) { "" }, f.getOrElse(1) { "" }, f.getOrElse(2) { "" }, f.getOrElse(3) { "true" } == "true", f.getOrElse(4) { "false" } == "true", f.getOrElse(5) { "false" } == "true"))
            }
        }
    },
)

private class AidStationDraft(name: String = "", km: String = "", cutoff: String = "", water: Boolean = true, food: Boolean = false, dropBag: Boolean = false) {
    var name by mutableStateOf(name)
    var km by mutableStateOf(km)
    var cutoff by mutableStateOf(cutoff)
    var water by mutableStateOf(water)
    var food by mutableStateOf(food)
    var dropBag by mutableStateOf(dropBag)
}

private fun Double.trimmed(): String = if (this % 1.0 == 0.0) "%.0f".format(this) else "%.1f".format(this)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventFormSheet(
    initial: AthleteEvent? = null,
    onDismiss: () -> Unit,
    onSaved: (AthleteEvent) -> Unit,
    onDeleted: () -> Unit = {},
) {
    val repo = remember { AthleteEventsRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }

    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var kind by rememberSaveable { mutableStateOf(initial?.kind ?: EventKind.RACE) }
    var sport by rememberSaveable { mutableStateOf(initial?.sport ?: EventSport.RUN) }
    var priority by rememberSaveable { mutableStateOf(initial?.priority ?: EventPriority.B) }
    var date by rememberSaveable { mutableStateOf(initial?.date ?: LocalDate.now().plusWeeks(4)) }
    var hasStart by rememberSaveable { mutableStateOf(initial?.start != null) }
    var startTime by rememberSaveable { mutableStateOf(initial?.start?.withZoneSameInstant(zone)?.toLocalTime() ?: LocalTime.of(9, 0)) }
    var target by rememberSaveable { mutableStateOf(initial?.targetTimeMin?.let(::formatHoursMinutes).orEmpty()) }
    var expected by rememberSaveable { mutableStateOf(initial?.expectedDurationMin?.let(::formatHoursMinutes).orEmpty()) }
    var distance by rememberSaveable { mutableStateOf(initial?.distanceKm?.trimmed().orEmpty()) }
    var ascent by rememberSaveable { mutableStateOf(initial?.ascentM?.trimmed().orEmpty()) }
    var location by rememberSaveable { mutableStateOf(initial?.locationName.orEmpty()) }
    var latLon by rememberSaveable { mutableStateOf(initial?.startLat?.let { lat -> initial.startLon?.let { lat to it } }) }
    var course by rememberSaveable(stateSaver = CourseSaver) { mutableStateOf(initial?.course.orEmpty()) }
    var courseFromFile by rememberSaveable(stateSaver = NullableEventSaver) { mutableStateOf<AthleteEvent?>(null) }
    var notes by rememberSaveable { mutableStateOf(initial?.notes.orEmpty()) }
    // State kept across the file picker: the app can be stopped, or killed and restored, while it is open.
    val stations = rememberSaveable(saver = StationsSaver) {
        mutableStateListOf<AidStationDraft>().apply {
            initial?.aidStations?.forEach {
                add(AidStationDraft(it.name, it.km.trimmed(), it.cutoffMin?.let(::formatHoursMinutes).orEmpty(), it.water, it.food, it.dropBag))
            }
        }
    }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }
    // The file picker takes the foreground, which makes the sheet ask to be dismissed; ignored while it is open.
    var picking by rememberSaveable { mutableStateOf(false) }

    fun build(): AthleteEvent {
        val base = AthleteEvent(
            id = initial?.id,
            name = name,
            kind = kind,
            sport = sport,
            priority = priority,
            date = date,
            start = if (hasStart) date.atTime(startTime).atZone(zone) else null,
            status = initial?.status ?: EventStatus.PLANNED,
            targetTimeMin = parseHoursMinutes(target),
            expectedDurationMin = parseHoursMinutes(expected),
            distanceKm = distance.replace(',', '.').toDoubleOrNull(),
            ascentM = ascent.replace(',', '.').toDoubleOrNull(),
            descentM = initial?.descentM,
            startLat = latLon?.first,
            startLon = latLon?.second,
            startElevationM = initial?.startElevationM,
            locationName = location,
            course = course,
            aidStations = stations.mapNotNull { s ->
                val km = s.km.replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
                AidStation(s.name, km, s.water, s.food, s.dropBag, parseHoursMinutes(s.cutoff))
            },
            calendarEventId = initial?.calendarEventId,
            exerciseSessionId = initial?.exerciseSessionId,
            notes = notes,
        )
        // A picked GPX file sets distance, ascent, descent and the start point from the course.
        return courseFromFile?.let { picked ->
            base.copy(
                course = picked.course, distanceKm = picked.distanceKm, ascentM = picked.ascentM, descentM = picked.descentM,
                startLat = picked.startLat, startLon = picked.startLon, startElevationM = picked.startElevationM,
            )
        } ?: base
    }

    val gpxPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        picking = false
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            error = null
            try {
                val profile = withContext(Dispatchers.IO) {
                    val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: throw IllegalStateException("Couldn't open the file.")
                    courseProfileFromGpx(text)
                }
                if (profile.points.size < 2) {
                    error = "That file has no route in it."
                } else {
                    val applied = build().withCourse(profile)
                    courseFromFile = applied
                    course = applied.course
                    distance = applied.distanceKm?.let { "%.1f".format(it) }.orEmpty()
                    ascent = applied.ascentM?.let { "%.0f".format(it) }.orEmpty()
                    latLon = applied.startLat?.let { lat -> applied.startLon?.let { lat to it } }
                    info = "Course read: %.1f km, %s m up.".format(profile.distanceM / 1000, profile.gainM?.let { "%.0f".format(it) } ?: "?")
                }
            } catch (e: Exception) {
                error = "Couldn't read the GPX file (${e.message ?: "unknown error"})."
            }
        }
    }

    val draft = build()
    val problems = validateEvent(draft)
    var stationIndex by rememberSaveable { mutableStateOf(0) }
    val flow = rememberFlowState()
    // WHAT, WHEN and the COURSE are asked; aid stations (one to a page) and notes are the optional extras.
    val pages = listOf(
        FlowPage("WHAT", ready = name.isNotBlank()),
        FlowPage("WHEN"),
        FlowPage("COURSE"),
        FlowPage("AID STATIONS", optional = true),
        FlowPage(if (initial?.id == null) "NOTES" else "NOTES · DELETE", optional = true),
    )
    FTFlow(
        title = if (initial?.id == null) "ADD EVENT" else "EDIT EVENT", pages = pages, state = flow, onClose = onDismiss,
        canSave = problems.isEmpty(), saving = saving, wash = FT.DomainTraining,
        error = error ?: problems.firstOrNull()?.takeIf { name.isNotBlank() && flow.page >= 2 },
        onSave = {
            saving = true
            error = null
            scope.launchSafe(onError = { error = describeLoadFailure(it) }, onDone = { saving = false }) {
                val saved = repo.save(draft, if (courseFromFile != null) "gpx_file" else null)
                onSaved(saved)
            }
        },
    ) { page ->
        when (page) {
            0 -> {
                FlowQuestion("What is it?", "A race, a long day or a hard day outside your plan. Fuel and hydration plans are built around it.")
                Column { Label("NAME"); FieldTextField(name, { name = it }, "e.g. City half marathon") }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) { Label("WHAT IT IS"); FTDropdown(EventKind.entries.map { it.key to it.label }, kind.key, "Kind") { kind = EventKind.of(it) } }
                    Column(Modifier.weight(1f)) { Label("SPORT"); FTDropdown(EventSport.entries.map { it.key to it.label }, sport.key, "Sport") { sport = EventSport.of(it) } }
                }
                Column {
                    Label("PRIORITY")
                    SegmentedToggle(EventPriority.entries, priority, { it.key }, { priority = it }, accentColor = FT.DomainTraining)
                    Text("A: the goal race. B: important. C: fits around the plan.", style = FTType.Caption, color = FT.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                }
            }
            1 -> {
                DateField("DATE", date) { date = it }
                Column {
                    Label("START")
                    SegmentedToggle(listOf(false, true), hasStart, { if (it) "SET A TIME" else "NO TIME YET" }, { hasStart = it }, accentColor = FT.DomainTraining)
                }
                if (hasStart) TimeField("START TIME", startTime) { startTime = it }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) { Label("TARGET TIME"); FieldTextField(target, { target = it }, "h:mm") }
                    Column(Modifier.weight(1f)) { Label("OR EXPECTED"); FieldTextField(expected, { expected = it }, "h:mm") }
                }
                Text("The target time when you have one; otherwise roughly how long it will take.", style = FTType.Caption, color = FT.TextSecondary)
            }
            2 -> {
                if (course.size >= 2) {
                    val km = course.last().distanceM / 1000
                    Text("%.1f km course, %d points.".format(km, course.size), style = FTType.BodySmall, color = FT.TextPrimary)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FTButton("REPLACE GPX", modifier = Modifier.weight(1f)) { picking = true; gpxPicker.launch(arrayOf("*/*")) }
                        FTButton("REMOVE", emphasis = FTButtonEmphasis.Tertiary, modifier = Modifier.weight(1f)) {
                            course = emptyList<CoursePoint>(); courseFromFile = null; info = null
                        }
                    }
                } else {
                    FTButton("ATTACH GPX FILE", modifier = Modifier.fillMaxWidth()) { picking = true; gpxPicker.launch(arrayOf("*/*")) }
                }
                info?.let { Text(it, style = FTType.Caption, color = FT.TextSecondary) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) { Label("DISTANCE KM"); FieldTextField(distance, { distance = it }, "e.g. 21.1", keyboardType = KeyboardType.Decimal) }
                    Column(Modifier.weight(1f)) { Label("ASCENT M"); FieldTextField(ascent, { ascent = it }, "e.g. 250", keyboardType = KeyboardType.Number) }
                }
                Column {
                    Label("PLACE")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        FieldTextField(location, { location = it; if (course.isEmpty()) latLon = null }, "Town or start area", modifier = Modifier.weight(1f))
                        FTButton("FIND", enabled = location.isNotBlank()) {
                            scope.launchSafe(onError = { error = describeLoadFailure(it) }) {
                                val found = GeocodingRepository().geocode(location.trim())
                                if (found == null) error = "Couldn't find that place." else { latLon = found; info = "Place found: weather will be read there." }
                            }
                        }
                    }
                    Text(
                        when {
                            latLon != null && course.isNotEmpty() -> "Weather is read at the course start."
                            latLon != null -> "Weather is read at this place."
                            else -> "Without a course or a place, weather uses your home location."
                        },
                        style = FTType.Caption, color = FT.TextSecondary, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            3 -> {
                // One station to a page: a long course's dozen stations never make a long list.
                val i = stationIndex.coerceIn(0, (stations.size - 1).coerceAtLeast(0))
                if (stations.isNotEmpty()) {
                    FlowPager(i, stations.size, { stationIndex = it }, what = "stations")
                    val s = stations[i]
                    Column { Label("NAME"); FieldTextField(s.name, { s.name = it }, "Name") }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) { Label("AT KM"); FieldTextField(s.km, { s.km = it }, "km", keyboardType = KeyboardType.Decimal) }
                        Column(Modifier.weight(1f)) { Label("CUTOFF"); FieldTextField(s.cutoff, { s.cutoff = it }, "h:mm") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ToggleChip("WATER", s.water, Modifier.weight(1f)) { s.water = it }
                        ToggleChip("FOOD", s.food, Modifier.weight(1f)) { s.food = it }
                        ToggleChip("DROP BAG", s.dropBag, Modifier.weight(1f)) { s.dropBag = it }
                    }
                } else {
                    FlowQuestion("No aid stations yet.", "Add one for each stop on the course: fuel plans use what each one has.")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FTButton("ADD AID STATION", modifier = Modifier.weight(1f)) {
                        stations.add(AidStationDraft(name = "Aid ${stations.size + 1}")); stationIndex = stations.size - 1
                    }
                    if (stations.isNotEmpty()) FTButton("REMOVE", emphasis = FTButtonEmphasis.Tertiary) {
                        stations.removeAt(i); stationIndex = (i - 1).coerceAtLeast(0)
                    }
                }
            }
            else -> {
                Column { Label("NOTES"); FieldTextField(notes, { notes = it }, "Bib pick-up, kit, anything worth noting", singleLine = false) }
                if (initial?.id != null) {
                    if (confirmingDelete) {
                        FTConfirmDestructive(
                            message = "Delete this event? Its fuel plans go with it.",
                            confirmLabel = "DELETE EVENT",
                            busy = saving,
                            onCancel = { confirmingDelete = false },
                            onConfirm = {
                                saving = true
                                scope.launchSafe(onError = { error = describeLoadFailure(it) }, onDone = { saving = false }) {
                                    repo.delete(initial.id)
                                    onDeleted()
                                }
                            },
                        )
                    } else {
                        FTButton("DELETE EVENT", emphasis = FTButtonEmphasis.Destructive) { confirmingDelete = true }
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun ToggleChip(label: String, on: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    FTButton(if (on) "✓ $label" else label, emphasis = if (on) FTButtonEmphasis.Secondary else FTButtonEmphasis.Tertiary, modifier = modifier) { onChange(!on) }
}
