package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.IntimacyRepository
import com.bioscan.fieldterminal.data.PeopleRepository
import com.bioscan.fieldterminal.data.catching
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.EncounterEditRow
import com.bioscan.fieldterminal.data.model.LogEncounterRow
import com.bioscan.fieldterminal.data.model.NewPersonRow
import com.bioscan.fieldterminal.data.model.PersonProfileRow
import com.bioscan.fieldterminal.data.model.PersonRow
import com.bioscan.fieldterminal.data.model.SexualActivityInstance
import com.bioscan.fieldterminal.domain.IntimacyVocab
import com.bioscan.fieldterminal.domain.VisitSpan
import com.bioscan.fieldterminal.domain.suggestVisit
import com.bioscan.fieldterminal.domain.toAppIso
import com.bioscan.fieldterminal.domain.withSaved
import com.bioscan.fieldterminal.ui.components.DateField
import com.bioscan.fieldterminal.ui.components.DateTimeField
import com.bioscan.fieldterminal.ui.components.FTRatingSlider
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.FlowEssentials
import com.bioscan.fieldterminal.ui.components.FlowMore
import com.bioscan.fieldterminal.ui.components.FormBackHandler
import com.bioscan.fieldterminal.ui.components.LocalFlowStartPage
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.theme.FTType
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// + LOG: the one Sex / encounter entry -- an encounter with one or more partners (with or without sex), or solo.

// The Intimacy chip's form: with a partner (an encounter, with or without sex) or solo. The two write different tables
// (encounters, sexual_activity_daily), so the choice is asked on the first page of each and each form keeps its own state.
@Composable
internal fun IntimacyForm(
    saving: Boolean,
    onSaveEncounter: (List<EncounterEditRow>) -> Unit,
    onSaveSolo: (date: String, instances: List<SexualActivityInstance>, notes: String?) -> Unit,
) {
    var solo by rememberSaveable { mutableStateOf(false) }
    val who: @Composable () -> Unit = {
        Column {
            FormLabel("WHO")
            TextChipRow(INTIMACY_WHO, if (solo) INTIMACY_WHO[1] else INTIMACY_WHO[0], perRow = 2) { picked -> if (picked != null) solo = picked == INTIMACY_WHO[1] }
        }
    }
    if (solo) SexualActivityForm(saving, header = who, onSave = onSaveSolo) else EncounterForm(saving, header = who, onSave = onSaveEncounter)
}

private val INTIMACY_WHO = listOf("With partner", "Solo")

// What is logged per partner. A group encounter saves one encounters row per partner, each with its own lists.
private class EditablePartner(initial: LogEncounterRow? = null) {
    var partnerId by mutableStateOf(initial?.personId)
    var partnerName by mutableStateOf(initial?.people?.name ?: "")
    var rounds by mutableStateOf(initial?.rounds)
    var orgasmCount by mutableStateOf(initial?.orgasmCount)
    var orgasmIntensity by mutableStateOf(initial?.orgasmIntensity)
    var activities by mutableStateOf(initial?.activities.orEmpty().toSet())
    var positions by mutableStateOf(initial?.positions.orEmpty().toSet())
    var toys by mutableStateOf(initial?.toys.orEmpty().toSet())
    var protection by mutableStateOf(initial?.protection.orEmpty().toSet())
    var ejaculation by mutableStateOf(initial?.ejaculationLocation.orEmpty().toSet())
    var myRating by mutableStateOf(initial?.myRating)
}

// An encounter with one or more partners. WHEN, SEX, FORMAT, type, location, duration and notes are shared; everything
// else is per partner. SEX decides whether it counts as intercourse or is a date or meetup without; a new one starts on
// Yes, a saved one on what was saved. Adding saves one row per partner; editing (initial != null) is one partner's row.
//
// DAV-158: a real time of day (encounters.occurred_at), not the bare date the table started with. Of the schema's three
// rating columns only my_rating is surfaced ("exactly one rating system", DAV-159).
@Composable
internal fun EncounterForm(
    saving: Boolean,
    initial: LogEncounterRow? = null,
    initialDateTime: LocalDateTime = LocalDateTime.now(),
    header: (@Composable () -> Unit)? = null,
    onSave: (List<EncounterEditRow>) -> Unit,
) {
    var dateTime by remember { mutableStateOf(initialDateTime) }
    val partners = remember { mutableStateListOf(EditablePartner(initial)) }
    var sexual by remember { mutableStateOf(initial?.sexual ?: true) }
    var format by remember { mutableStateOf(initial?.format.orEmpty().toSet()) }
    var encounterType by remember { mutableStateOf(initial?.encounterType ?: "") }
    var locationType by remember { mutableStateOf(initial?.locationType ?: "") }
    var durationMin by remember { mutableStateOf(initial?.durationMin?.toString() ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    var placeId by remember { mutableStateOf(initial?.placeId) }

    // The stay the location history has around WHEN: offered as the place and the time there, never applied unasked.
    // Nothing is offered when location capture is off or has no stay then.
    var visits by remember { mutableStateOf<List<Pair<VisitSpan, String?>>>(emptyList()) }
    val day = dateTime.toLocalDate()
    LaunchedEffect(day) {
        visits = catching { IntimacyRepository(SupabaseClientProvider.client).visitsAround(day) }.getOrDefault(emptyList())
    }
    val visit = remember(visits, dateTime) { suggestVisit(visits.map { it.first }, dateTime)?.let { v -> visits.first { it.first == v } } }

    // Pages only where the subject changes (Logging Flows Redesign Plan): THE ENCOUNTER (when, the place your location
    // history suggests, sex or not and the format, the shared details folded), then one page per partner (who and the
    // rating, the rounds and orgasms, the lists folded until opened). Every field is optional, so SAVE is always offered.
    // The fit test opens each page in turn (LocalFlowStartPage); the app starts on THE ENCOUNTER.
    val testStart = LocalFlowStartPage.current
    var page by rememberSaveable { mutableStateOf(testStart) }
    if (page > partners.size) page = partners.size
    FormBackHandler(enabled = page > 0) { page -= 1 }
    val save = {
        // The sex fields are cleared when SEX is No, so a meetup never carries a stale round count or tag.
        fun tags(picked: Set<String>) = picked.toList().takeIf { sexual && it.isNotEmpty() }
        onSave(
            partners.map { p ->
                EncounterEditRow(
                    date = dateTime.toLocalDate().toString(),
                    occurredAt = dateTime.toAppIso(),
                    personId = p.partnerId,
                    placeId = placeId,
                    encounterType = encounterType.trim().ifBlank { null },
                    locationType = locationType.trim().ifBlank { null },
                    durationMin = durationMin.toIntOrNull(),
                    activities = tags(p.activities),
                    sexual = sexual,
                    format = tags(format),
                    positions = tags(p.positions),
                    toys = tags(p.toys),
                    protection = tags(p.protection),
                    ejaculationLocation = tags(p.ejaculation),
                    rounds = p.rounds.takeIf { sexual },
                    orgasmCount = p.orgasmCount.takeIf { sexual },
                    orgasmIntensity = p.orgasmIntensity.takeIf { sexual && (p.orgasmCount ?: 0) > 0 },
                    myRating = p.myRating,
                    notes = notes.trim().ifBlank { null },
                )
            }
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val partnerLabel = { i: Int -> partners[i].partnerName.ifBlank { if (partners.size > 1) "PARTNER ${i + 1}" else "PARTNER" }.uppercase() }
        Text(
            "${page + 1} / ${partners.size + 1} · ${if (page == 0) "THE ENCOUNTER" else partnerLabel(page - 1)}",
            style = FTType.MonoCaption, color = FT.TextSecondary,
        )
        if (page == 0) {
            FlowEssentials {
                header?.invoke()
                DateTimeField("WHEN", dateTime, { dateTime = it })
                visit?.let { (span, name) ->
                    val used = placeId != null && placeId == span.placeId && durationMin == span.minutes.toString()
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            FormLabel("FROM YOUR LOCATION")
                            Text(
                                "${name ?: "A place with no name yet"} \u00b7 ${span.start.format(VISIT_TIME)}\u2013${span.end.format(VISIT_TIME)} \u00b7 ${span.minutes} min there",
                                style = FTType.BodySmall, color = FT.TextSecondary,
                            )
                        }
                        Text(
                            if (used) "USED" else "USE",
                            style = FTType.MonoCaption,
                            color = if (used) FT.TextMuted else FT.DomainLog,
                            modifier = Modifier.ftClickable {
                                placeId = span.placeId
                                if (name != null && locationType.isBlank()) locationType = name
                                durationMin = span.minutes.toString()
                            },
                        )
                    }
                }
                Column { FormLabel("SEX"); TextChipRow(YES_NO_OPTIONS, if (sexual) "Yes" else "No", perRow = 2) { picked -> if (picked != null) sexual = picked == "Yes" } }
            }
            if (sexual) MultiChipSection("FORMAT", IntimacyVocab.formats, format) { format = it }
            FlowMore("encounter_details", label = "DETAILS", summary = "Type, location, duration, notes") {
                Column { FormLabel("TYPE (OPTIONAL)"); FieldTextField(encounterType, { encounterType = it }, "e.g. date, call, hangout") }
                Column { FormLabel("LOCATION (OPTIONAL)"); FieldTextField(locationType, { locationType = it }, "e.g. their place, restaurant") }
                Column { FormLabel("DURATION MIN (OPTIONAL)"); FieldTextField(durationMin, { durationMin = it }, "e.g. 90", keyboardType = KeyboardType.Number) }
                Column { FormLabel("NOTES (OPTIONAL)"); FieldTextField(notes, { notes = it }, "Notes...", singleLine = false) }
            }
            FTButton("${partnerLabel(0)} ›", modifier = Modifier.fillMaxWidth(), emphasis = FTButtonEmphasis.Secondary) { page = 1 }
        } else {
            val i = page - 1
            val partner = partners[i]
            FlowEssentials {
                PartnerPicker(partner.partnerId, partner.partnerName) { person -> partner.partnerId = person?.id; partner.partnerName = person?.name ?: "" }
                Column { FormLabel("RATING 1-5 (OPTIONAL)"); IntChipRow(1..5, partner.myRating) { partner.myRating = it } }
            }
            if (sexual) {
                Column { FormLabel("ROUNDS (OPTIONAL)"); IntChipRow(1..5, partner.rounds) { partner.rounds = it } }
                Column { FormLabel("MY ORGASMS (OPTIONAL)"); IntChipRow(0..5, partner.orgasmCount) { partner.orgasmCount = it } }
                if ((partner.orgasmCount ?: 0) > 0) {
                    FTRatingSlider("STRONGEST ORGASM", partner.orgasmIntensity, 0..10, FT.Metric.Arousal, { partner.orgasmIntensity = it }, lowWord = "faint", highWord = "intense")
                }
                MultiChipSection("ACTIVITIES", IntimacyVocab.activities, partner.activities) { partner.activities = it }
                MultiChipSection("POSITIONS", IntimacyVocab.positions, partner.positions) { partner.positions = it }
                MultiChipSection("TOYS", IntimacyVocab.toys, partner.toys) { partner.toys = it }
                MultiChipSection("PROTECTION", IntimacyVocab.protection, partner.protection) { partner.protection = it }
                MultiChipSection("EJACULATION", IntimacyVocab.ejaculationLocation, partner.ejaculation) { partner.ejaculation = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // A new encounter can take more partners (a group: one row each); an edit is one partner's row.
                if (initial == null) Text("+ ADD PARTNER", style = FTType.MonoCaption, color = FT.DomainLog, modifier = Modifier.ftClickable { partners.add(EditablePartner()); page = partners.size })
                if (partners.size > 1) Text("REMOVE PARTNER", style = FTType.MonoCaption, color = FT.Bad, modifier = Modifier.ftClickable { partners.removeAt(i); page = i.coerceAtLeast(1).coerceAtMost(partners.size) })
            }
            if (i < partners.lastIndex) FTButton("${partnerLabel(i + 1)} ›", modifier = Modifier.fillMaxWidth(), emphasis = FTButtonEmphasis.Secondary) { page += 1 }
        }
        SaveButton(saving, true) { save() }
    }
}

private val VISIT_TIME = DateTimeFormatter.ofPattern("HH:mm")

// A multiple-choice chip list that stays folded until tapped, so the lists do not bury the form. The header shows what
// is picked; a section that already has picks opens unfolded.
@Composable
internal fun MultiChipSection(label: String, vocabulary: List<String>, selected: Set<String>, onChange: (Set<String>) -> Unit) {
    var open by rememberSaveable { mutableStateOf(selected.isNotEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().ftClickable { open = !open },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FormLabel(if (selected.isEmpty()) label else "$label \u00b7 ${selected.size}")
            Text(if (open) "HIDE" else "CHOOSE", style = FTType.Micro, color = FT.DomainLog)
        }
        if (open) {
            ChipGrid(withSaved(vocabulary, selected), perRow = 3, role = Role.Checkbox, isSelected = { it in selected }) {
                onChange(if (it in selected) selected - it else selected + it)
            }
        } else if (selected.isNotEmpty()) {
            Text(selected.joinToString(", "), style = FTType.BodySmall, color = FT.TextSecondary)
        }
    }
}

// Search the people table, pick one, or add a new person. `onPick(null)` clears the partner.
@Composable
private fun PartnerPicker(partnerId: Long?, partnerName: String, onPick: (PersonRow?) -> Unit) {
    val peopleRepo = remember { PeopleRepository(SupabaseClientProvider.client) }
    var allPeople by remember { mutableStateOf<List<PersonRow>>(emptyList()) }
    LaunchedEffect(Unit) { allPeople = try { peopleRepo.loadAll() } catch (e: Exception) { emptyList() } }
    var partnerQuery by remember { mutableStateOf("") }
    var newPartnerName by remember { mutableStateOf<String?>(null) }
    val filteredPeople = remember(partnerQuery, allPeople) {
        if (partnerQuery.isBlank()) allPeople.take(5)
        else allPeople.filter { it.name.contains(partnerQuery, ignoreCase = true) }.take(8)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FormLabel("PARTNER (OPTIONAL)")
        if (partnerId != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    partnerName.ifEmpty { allPeople.firstOrNull { it.id == partnerId }?.name ?: "#$partnerId" },
                    style = FTType.RowTitle,
                    color = FT.TextPrimary,
                )
                Text("CLEAR", style = FTType.Micro, color = FT.Bad, modifier = Modifier.ftClickable { onPick(null); partnerQuery = "" })
            }
        } else {
            FieldTextField(partnerQuery, { partnerQuery = it }, "Search people\u2026")
            filteredPeople.forEach { person ->
                Text(
                    person.name,
                    style = FTType.BodySmall,
                    color = FT.TextSecondary,
                    modifier = Modifier.fillMaxWidth().ftClickable { onPick(person); partnerQuery = "" }.padding(vertical = 4.dp),
                )
            }
            val trimmedQuery = partnerQuery.trim()
            if (trimmedQuery.isNotEmpty() && filteredPeople.none { it.name.equals(trimmedQuery, ignoreCase = true) }) {
                Text("+ ADD \"$trimmedQuery\"", style = FTType.LabelCaps, color = FT.Ok, modifier = Modifier.ftClickable { newPartnerName = trimmedQuery })
            }
        }
    }
    newPartnerName?.let { name ->
        NewPartnerSheet(
            initialName = name,
            repo = peopleRepo,
            onDismiss = { newPartnerName = null },
            onCreated = { person -> onPick(person); partnerQuery = ""; newPartnerName = null },
        )
    }
}

// Solo. One daily row in sexual_activity_daily holding a real list of that day's instances, so per-instance detail
// (orgasm, intensity, watched porn, load size) survives -- the same pattern ExerciseDetailsForm's editable `exercises`
// list uses. Partnered sex is an encounter (EncounterForm); partnerId stays on the DTO only so an old row's instances
// round-trip unchanged.
private fun SexualActivityInstance.toEditable() = EditableSexualActivityInstance(
    orgasm = orgasm,
    orgasmIntensity = orgasmIntensity?.toString() ?: "",
    watchedPorn = watchedPorn,
    loadSize = loadSize?.toString() ?: "",
    notes = notes ?: "",
    partnerId = partnerId,
)

private class EditableSexualActivityInstance(
    orgasm: Boolean = false,
    orgasmIntensity: String = "",
    watchedPorn: Boolean? = null,
    loadSize: String = "",
    notes: String = "",
    private val partnerId: Long? = null,
) {
    var orgasm by mutableStateOf(orgasm)
    var orgasmIntensity by mutableStateOf(orgasmIntensity)
    var watchedPorn by mutableStateOf(watchedPorn)
    var loadSize by mutableStateOf(loadSize)
    var notes by mutableStateOf(notes)

    fun toDto() = SexualActivityInstance(
        orgasm = orgasm,
        orgasmIntensity = orgasmIntensity.toIntOrNull(),
        watchedPorn = watchedPorn,
        loadSize = loadSize.toIntOrNull(),
        notes = notes.trim().ifBlank { null },
        partnerId = partnerId,
    )
}

@Composable
internal fun SexualActivityForm(
    saving: Boolean,
    initialDate: LocalDate = LocalDate.now(),
    initialInstances: List<SexualActivityInstance> = emptyList(),
    initialNotes: String = "",
    header: (@Composable () -> Unit)? = null,
    onSave: (date: String, instances: List<SexualActivityInstance>, notes: String?) -> Unit,
) {
    var date by remember { mutableStateOf(initialDate) }
    val instances = remember {
        val seeded = initialInstances.map { it.toEditable() }
        mutableStateListOf(*seeded.ifEmpty { listOf(EditableSexualActivityInstance()) }.toTypedArray())
    }
    var notes by remember { mutableStateOf(initialNotes) }

    // One page (Logging Flows Redesign Plan): the day, the instances one under the other with + ADD INSTANCE, the notes.
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        FlowEssentials {
            header?.invoke()
            DateField("DATE", date, { date = it })
        }
        instances.forEachIndexed { i, instance ->
            if (instances.size > 1) FormLabel("INSTANCE ${i + 1}")
            SexualActivityInstanceEditor(instance, canRemove = instances.size > 1) { instances.removeAt(i) }
        }
        Text(
            "+ ADD INSTANCE",
            style = FTType.MonoCaption,
            color = FT.DomainLog,
            modifier = Modifier.ftClickable { instances.add(EditableSexualActivityInstance()) },
        )
        Column { FormLabel("NOTES (OPTIONAL)"); FieldTextField(notes, { notes = it }, "Anything else worth noting", singleLine = false) }
        SaveButton(saving, true) { onSave(date.toString(), instances.map { it.toDto() }, notes.trim().ifBlank { null }) }
    }
}

@Composable
private fun SexualActivityInstanceEditor(instance: EditableSexualActivityInstance, canRemove: Boolean, onRemove: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusModule)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            FormLabel("ORGASM")
            if (canRemove) {
                Text(
                    "REMOVE",
                    style = FTType.MonoCaption,
                    color = FT.Bad,
                    modifier = Modifier.ftClickable { onRemove() },
                )
            }
        }
        TextChipRow(YES_NO_OPTIONS, if (instance.orgasm) "Yes" else "No") { instance.orgasm = it == "Yes" }
        if (instance.orgasm) {
            FTRatingSlider("ORGASM INTENSITY", instance.orgasmIntensity.toIntOrNull(), 0..10, FT.Metric.Arousal, { instance.orgasmIntensity = it?.toString() ?: "" }, lowWord = "faint", highWord = "intense")
        }
        Column { FormLabel("WATCHED PORN"); TextChipRow(YES_NO_OPTIONS, instance.watchedPorn?.let { if (it) "Yes" else "No" }) { instance.watchedPorn = it?.let { s -> s == "Yes" } } }
        Column { FormLabel("LOAD SIZE 1-5 (OPTIONAL)"); TextChipRow(listOf("1", "2", "3", "4", "5"), instance.loadSize.ifBlank { null }, perRow = 5) { instance.loadSize = it ?: "" } }
        Column { FormLabel("NOTES (OPTIONAL)"); FieldTextField(instance.notes, { instance.notes = it }, "Anything else about this instance", singleLine = false) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
// A new person, or with [existing] the same fields of a person already on file (ME › INTIMACY › partner › EDIT).
internal fun NewPartnerSheet(
    initialName: String,
    repo: PeopleRepository,
    onDismiss: () -> Unit,
    existing: PersonProfileRow? = null,
    onCreated: (PersonRow) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name ?: initialName) }
    var relationship by remember { mutableStateOf(existing?.relationship ?: "") }
    var whereMet by remember { mutableStateOf(existing?.whereMet ?: "") }
    var gender by remember { mutableStateOf(existing?.gender ?: "") }
    var ageRange by remember { mutableStateOf(existing?.ageRange ?: "") }
    var country by remember { mutableStateOf(existing?.country ?: "") }
    var score by remember { mutableStateOf(existing?.score) }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = FT.SheetShape,
        containerColor = FT.Surface,
        contentColor = FT.TextPrimary,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(if (existing == null) "NEW PARTNER" else "EDIT PARTNER", style = FTType.SectionTitle, color = FT.DomainLog)
            Column { FormLabel("NAME"); FieldTextField(name, { name = it }, "") }
            Column { FormLabel("RELATIONSHIP (OPTIONAL)"); FieldTextField(relationship, { relationship = it }, "e.g. FWB, Dating, Hookup") }
            Column { FormLabel("WHERE MET (OPTIONAL)"); FieldTextField(whereMet, { whereMet = it }, "e.g. Tinder, Work, Mutual Friends") }
            Column { FormLabel("GENDER (OPTIONAL)"); FieldTextField(gender, { gender = it }, "e.g. Female, Non-binary") }
            Column { FormLabel("AGE RANGE (OPTIONAL)"); FieldTextField(ageRange, { ageRange = it }, "e.g. 25-30") }
            Column { FormLabel("COUNTRY (OPTIONAL)"); FieldTextField(country, { country = it }, "e.g. Australia") }
            Column {
                FormLabel("SCORE (OPTIONAL)")
                TextChipRow(listOf("1", "2", "3", "4", "5"), score?.toString(), perRow = 5) { score = it?.toIntOrNull() }
            }
            Column { FormLabel("NOTES (OPTIONAL)"); FieldTextField(notes, { notes = it }, "", singleLine = false) }
            error?.let { Text("Couldn't create ($it).", style = FTType.Caption, color = FT.Bad) }
            SaveButton(saving, name.isNotBlank()) {
                if (!saving && name.isNotBlank()) {
                    saving = true
                    scope.launch {
                        try {
                            val row = NewPersonRow(
                                name = name.trim(),
                                relationship = relationship.trim().ifBlank { null },
                                whereMet = whereMet.trim().ifBlank { null },
                                gender = gender.trim().ifBlank { null },
                                ageRange = ageRange.trim().ifBlank { null },
                                country = country.trim().ifBlank { null },
                                score = score,
                                notes = notes.trim().ifBlank { null },
                            )
                            if (existing == null) {
                                onCreated(repo.createPerson(row))
                            } else {
                                IntimacyRepository(SupabaseClientProvider.client).updatePerson(existing.id, row)
                                onCreated(PersonRow(existing.id, row.name))
                            }
                        } catch (e: Exception) {
                            error = e.message
                            saving = false
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
