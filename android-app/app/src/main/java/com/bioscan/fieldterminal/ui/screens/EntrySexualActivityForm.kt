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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.PeopleRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.NewPersonRow
import com.bioscan.fieldterminal.data.model.PersonRow
import com.bioscan.fieldterminal.data.model.SexualActivityInstance
import com.bioscan.fieldterminal.ui.components.DateField
import com.bioscan.fieldterminal.ui.components.FTRatingSlider
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.theme.FTType
import kotlinx.coroutines.launch
import java.time.LocalDate
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// + LOG: sexual activity, one or more instances, with the partner picker.

// Replaces the old per-occurrence MasturbationForm (DAV-91 rework): one
// daily row per activityType ("masturbation"/"intercourse"), holding a real
// list of that day's instances so per-instance detail (orgasm, intensity,
// and -- masturbation only -- watched-porn/load-size) survives, same pattern
// ExerciseDetailsForm's editable `exercises` list already uses below.
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
    partnerId: Long? = null,
) {
    var orgasm by mutableStateOf(orgasm)
    var orgasmIntensity by mutableStateOf(orgasmIntensity)
    var watchedPorn by mutableStateOf(watchedPorn)
    var loadSize by mutableStateOf(loadSize)
    var notes by mutableStateOf(notes)
    var partnerId by mutableStateOf(partnerId)
    var partnerName by mutableStateOf("")

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
    lockedActivityType: String? = null,
    initialDate: LocalDate = LocalDate.now(),
    initialInstances: List<SexualActivityInstance> = emptyList(),
    initialNotes: String = "",
    onSave: (date: String, activityType: String, instances: List<SexualActivityInstance>, notes: String?) -> Unit,
) {
    var date by remember { mutableStateOf(initialDate) }
    val instances = remember {
        val seeded = initialInstances.map { it.toEditable() }
        mutableStateListOf(*seeded.ifEmpty { listOf(EditableSexualActivityInstance()) }.toTypedArray())
    }
    var notes by remember { mutableStateOf(initialNotes) }
    var activityType by remember { mutableStateOf(lockedActivityType ?: "masturbation") }
    val isMasturbation = activityType == "masturbation"

    val peopleRepo = remember { PeopleRepository(SupabaseClientProvider.client) }
    var allPeople by remember { mutableStateOf<List<PersonRow>>(emptyList()) }
    LaunchedEffect(activityType) {
        if (activityType == "intercourse") {
            allPeople = try { peopleRepo.loadAll() } catch (e: Exception) { emptyList() }
        }
    }
    // Resolve partner names for existing instances when people list loads
    LaunchedEffect(allPeople) {
        instances.forEach { inst ->
            if (inst.partnerId != null && inst.partnerName.isEmpty()) {
                inst.partnerName = allPeople.firstOrNull { it.id == inst.partnerId }?.name ?: ""
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (lockedActivityType == null) {
            Column {
                FormLabel("TYPE")
                TextChipRow(listOf("Masturbation", "Intercourse"), if (isMasturbation) "Masturbation" else "Intercourse") {
                    activityType = if (it == "Intercourse") "intercourse" else "masturbation"
                }
            }
        }
        DateField("DATE", date, { date = it })
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FormLabel("INSTANCES")
            instances.forEachIndexed { i, instance ->
                SexualActivityInstanceEditor(
                    instance, isMasturbation, canRemove = instances.size > 1,
                    allPeople = allPeople,
                    peopleRepo = peopleRepo,
                ) { instances.removeAt(i) }
            }
            Text(
                "+ ADD INSTANCE",
                style = FTType.MonoCaption,
                color = FT.DomainLog,
                modifier = Modifier.ftClickable { instances.add(EditableSexualActivityInstance()) },
            )
        }
        Column { FormLabel("NOTES (OPTIONAL)"); FieldTextField(notes, { notes = it }, "Anything else worth noting", singleLine = false) }
        SaveButton(saving, true) {
            onSave(date.toString(), activityType, instances.map { it.toDto() }, notes.trim().ifBlank { null })
        }
    }
}

@Composable
private fun SexualActivityInstanceEditor(
    instance: EditableSexualActivityInstance,
    isMasturbation: Boolean,
    canRemove: Boolean,
    allPeople: List<PersonRow> = emptyList(),
    peopleRepo: PeopleRepository,
    onRemove: () -> Unit,
) {
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
        if (isMasturbation) {
            Column { FormLabel("WATCHED PORN"); TextChipRow(YES_NO_OPTIONS, instance.watchedPorn?.let { if (it) "Yes" else "No" }) { instance.watchedPorn = it?.let { s -> s == "Yes" } } }
            Column { FormLabel("LOAD SIZE 1-5 (OPTIONAL)"); TextChipRow(listOf("1", "2", "3", "4", "5"), instance.loadSize.ifBlank { null }, perRow = 5) { instance.loadSize = it ?: "" } }
        }
        if (!isMasturbation) {
            var partnerQuery by remember { mutableStateOf("") }
            var showNewPartnerSheet by remember { mutableStateOf(false) }
            var newPartnerInitialName by remember { mutableStateOf("") }
            val filteredPeople = remember(partnerQuery, allPeople) {
                if (partnerQuery.isBlank()) allPeople.take(5)
                else allPeople.filter { it.name.contains(partnerQuery, ignoreCase = true) }.take(8)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FormLabel("PARTNER (OPTIONAL)")
                if (instance.partnerId != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            instance.partnerName.ifEmpty { "#${instance.partnerId}" },
                            style = FTType.RowTitle,
                            color = FT.TextPrimary,
                        )
                        Text(
                            "CLEAR",
                            style = FTType.Micro,
                            color = FT.Bad,
                            modifier = Modifier.ftClickable {
                                instance.partnerId = null
                                instance.partnerName = ""
                                partnerQuery = ""
                            },
                        )
                    }
                } else {
                    FieldTextField(partnerQuery, { partnerQuery = it }, "Search people…")
                    filteredPeople.forEach { person ->
                        Text(
                            person.name,
                            style = FTType.BodySmall,
                            color = FT.TextSecondary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .ftClickable {
                                    instance.partnerId = person.id
                                    instance.partnerName = person.name
                                    partnerQuery = ""
                                }
                                .padding(vertical = 4.dp),
                        )
                    }
                    val trimmedQuery = partnerQuery.trim()
                    if (trimmedQuery.isNotEmpty() && filteredPeople.none { it.name.equals(trimmedQuery, ignoreCase = true) }) {
                        Text(
                            "+ ADD \"$trimmedQuery\"",
                            style = FTType.LabelCaps,
                            color = FT.Ok,
                            modifier = Modifier.ftClickable {
                                newPartnerInitialName = trimmedQuery
                                showNewPartnerSheet = true
                            },
                        )
                    }
                }
            }
            if (showNewPartnerSheet) {
                NewPartnerSheet(
                    initialName = newPartnerInitialName,
                    repo = peopleRepo,
                    onDismiss = { showNewPartnerSheet = false },
                    onCreated = { person ->
                        instance.partnerId = person.id
                        instance.partnerName = person.name
                        partnerQuery = ""
                        showNewPartnerSheet = false
                    },
                )
            }
        }
        Column { FormLabel("NOTES (OPTIONAL)"); FieldTextField(instance.notes, { instance.notes = it }, "Anything else about this instance", singleLine = false) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewPartnerSheet(
    initialName: String,
    repo: PeopleRepository,
    onDismiss: () -> Unit,
    onCreated: (PersonRow) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initialName) }
    var relationship by remember { mutableStateOf("") }
    var whereMet by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("") }
    var ageRange by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("") }
    var score by remember { mutableStateOf<Int?>(null) }
    var notes by remember { mutableStateOf("") }
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
            Text("NEW PARTNER", style = FTType.SectionTitle, color = FT.DomainLog)
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
                            val person = repo.createPerson(NewPersonRow(
                                name = name.trim(),
                                relationship = relationship.trim().ifBlank { null },
                                whereMet = whereMet.trim().ifBlank { null },
                                gender = gender.trim().ifBlank { null },
                                ageRange = ageRange.trim().ifBlank { null },
                                country = country.trim().ifBlank { null },
                                score = score,
                                notes = notes.trim().ifBlank { null },
                            ))
                            onCreated(person)
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
