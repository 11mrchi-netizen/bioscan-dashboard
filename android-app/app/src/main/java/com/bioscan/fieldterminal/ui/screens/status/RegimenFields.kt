package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.RegimenDraft
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.SupplementChangeRow
import com.bioscan.fieldterminal.data.SupplementsRepository
import com.bioscan.fieldterminal.data.model.SupplementRow
import com.bioscan.fieldterminal.domain.CycleJson
import com.bioscan.fieldterminal.domain.DoseStepJson
import com.bioscan.fieldterminal.domain.DoseStepsJson
import com.bioscan.fieldterminal.domain.REGIMEN_FORMS
import com.bioscan.fieldterminal.domain.REGIMEN_ROUTES
import com.bioscan.fieldterminal.domain.REGIMEN_SLOTS
import com.bioscan.fieldterminal.domain.REGIMEN_UNITS
import com.bioscan.fieldterminal.domain.RegimenKind
import com.bioscan.fieldterminal.domain.RegimenSpec
import com.bioscan.fieldterminal.domain.ScheduleJson
import com.bioscan.fieldterminal.domain.ScheduleType
import com.bioscan.fieldterminal.domain.formatAmount
import com.bioscan.fieldterminal.domain.regimenSpec
import com.bioscan.fieldterminal.domain.scheduleWords
import com.bioscan.fieldterminal.domain.spec
import com.bioscan.fieldterminal.domain.words
import com.bioscan.fieldterminal.domain.doseTextOn
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

// The regimen form's fields (domain/Regimen.kt): what it is, one dose as a number and a unit, how it is taken, the times
// of day, how often, an optional on/off cycle and optional planned dose steps. SupplementFormSheet holds the name, the
// ingredients, the barcode and the save; this file holds the regimen itself and its history.

class EditableStep(amount: String = "", weeks: String = "") {
    var amount by mutableStateOf(amount)
    var weeks by mutableStateOf(weeks)
}

class RegimenEditorState(existing: SupplementRow?, today: LocalDate = LocalDate.now()) {
    private val spec: RegimenSpec? = existing?.spec()
    var kind by mutableStateOf(spec?.kind ?: RegimenKind.SUPPLEMENT)
    var form by mutableStateOf(spec?.form)
    var route by mutableStateOf(spec?.route ?: "oral")
    var amount by mutableStateOf(spec?.amount?.let(::formatAmount) ?: "")
    var unit by mutableStateOf(spec?.unit ?: "mg")
    var asNeeded by mutableStateOf(spec?.asNeeded ?: false)
    val slots = mutableStateListOf<String>().apply { addAll(spec?.slots?.takeIf { it.isNotEmpty() } ?: listOf("morning")) }
    var type by mutableStateOf(spec?.type?.takeIf { it != ScheduleType.AS_NEEDED } ?: ScheduleType.DAILY)
    var everyN by mutableStateOf(spec?.everyNDays?.toString() ?: "2")
    val weekdays = mutableStateListOf<Int>().apply { addAll(spec?.weekdays?.map { it.value }?.sorted() ?: emptyList()) }
    var cycleOn by mutableStateOf(spec?.cycle != null)
    var cycleInWeeks by mutableStateOf(spec?.cycle?.let { it.onDays % 7 == 0 && it.offDays % 7 == 0 } ?: true)
    var onLength by mutableStateOf(spec?.cycle?.let { (if (it.onDays % 7 == 0 && it.offDays % 7 == 0) it.onDays / 7 else it.onDays).toString() } ?: "8")
    var offLength by mutableStateOf(spec?.cycle?.let { (if (it.onDays % 7 == 0 && it.offDays % 7 == 0) it.offDays / 7 else it.offDays).toString() } ?: "4")
    val cycleStart: LocalDate = spec?.cycle?.start ?: today
    var stepsOn by mutableStateOf(spec?.steps != null)
    val steps = mutableStateListOf<EditableStep>().apply {
        spec?.steps?.steps?.forEach { add(EditableStep(formatAmount(it.amount), it.weeks?.toString() ?: "")) }
    }
    val stepsStart: LocalDate = spec?.steps?.start?.let(LocalDate::parse) ?: today

    /** Kind-led defaults: a new peptide is an injection under the skin in mcg; a hormone or medication keeps what is set. */
    fun chooseKind(k: RegimenKind) {
        kind = k
        if (k == RegimenKind.PEPTIDE && form == null) {
            form = "injection"; route = "subcutaneous"; if (amount.isBlank()) unit = "mcg"
        }
    }

    /** The draft to save, or the first thing that is missing. */
    fun draft(name: String): Pair<RegimenDraft?, String?> {
        if (name.isBlank()) return null to "Add a name."
        val a = amount.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 } ?: return null to "Add the dose as a number."
        if (!asNeeded && slots.isEmpty()) return null to "Pick at least one time of day, or As needed."
        val n = everyN.toIntOrNull()
        if (!asNeeded && type == ScheduleType.EVERY_N_DAYS && (n == null || n < 2)) return null to "Every N days needs N of 2 or more."
        if (!asNeeded && type == ScheduleType.WEEKDAYS && weekdays.isEmpty()) return null to "Pick the days of the week."
        val cycle = if (!asNeeded && cycleOn) {
            val on = onLength.toIntOrNull()?.takeIf { it > 0 } ?: return null to "The cycle needs its on length."
            val off = offLength.toIntOrNull()?.takeIf { it > 0 } ?: return null to "The cycle needs its off length."
            val mult = if (cycleInWeeks) 7 else 1
            CycleJson(on * mult, off * mult, cycleStart.toString())
        } else null
        val stepJson = if (stepsOn && steps.isNotEmpty()) {
            val parsed = steps.mapIndexed { i, st ->
                val sa = st.amount.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 } ?: return null to "Each dose step needs an amount."
                val w = st.weeks.toIntOrNull()?.takeIf { it > 0 }
                if (i < steps.lastIndex && w == null) return null to "Each dose step but the last needs its length in weeks."
                DoseStepJson(if (i == steps.lastIndex) null else w, sa)
            }
            DoseStepsJson(stepsStart.toString(), parsed)
        } else null
        val schedule = when {
            asNeeded -> ScheduleJson("as_needed")
            type == ScheduleType.EVERY_N_DAYS -> ScheduleJson("every_n_days", n = n, cycle = cycle)
            type == ScheduleType.WEEKDAYS -> ScheduleJson("weekdays", days = weekdays.sorted(), cycle = cycle)
            else -> ScheduleJson("daily", cycle = cycle)
        }
        return RegimenDraft(
            name = name.trim(), kind = kind.key, form = form, route = route, amount = a, unit = unit,
            slots = if (asNeeded) emptyList() else REGIMEN_SLOTS.filter { it in slots }, schedule = schedule, steps = stepJson,
        ) to null
    }

    /** The first slot, or as-needed: what the interaction check's slot gaps use. */
    val firstSlot: String get() = if (asNeeded) "as-needed" else REGIMEN_SLOTS.firstOrNull { it in slots } ?: "morning"
    val everyNDaysOrNull: Int? get() = everyN.toIntOrNull()?.takeIf { !asNeeded && type == ScheduleType.EVERY_N_DAYS && it >= 2 }
}

@Composable
fun RegimenFields(state: RegimenEditorState) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column {
            Label("WHAT IT IS")
            Chips(RegimenKind.entries.map { it.label }, listOf(state.kind.label), perRow = 3) { picked ->
                RegimenKind.entries.firstOrNull { it.label == picked }?.let(state::chooseKind)
            }
        }

        Column {
            Label("ONE DOSE")
            FieldTextField(state.amount, { state.amount = it }, "e.g. 5000", keyboardType = KeyboardType.Decimal)
            Spacer(Modifier.width(4.dp))
            Box(Modifier.padding(top = 8.dp)) {
                Chips(REGIMEN_UNITS, listOf(state.unit), perRow = 5) { state.unit = it }
            }
        }

        Column {
            Label("FORM · HOW IT IS TAKEN")
            Chips(REGIMEN_FORMS.map { it.second }, listOfNotNull(REGIMEN_FORMS.firstOrNull { it.first == state.form }?.second), perRow = 4) { picked ->
                val key = REGIMEN_FORMS.first { it.second == picked }.first
                state.form = if (state.form == key) null else key
                if (key == "injection" && state.route == "oral") state.route = "subcutaneous"
                if (key == "nasal_spray") state.route = "nasal"
                if (key == "cream" || key == "patch") state.route = "topical"
            }
            Box(Modifier.padding(top = 8.dp)) {
                Chips(REGIMEN_ROUTES.map { it.second }, listOfNotNull(REGIMEN_ROUTES.firstOrNull { it.first == state.route }?.second), perRow = 4) { picked ->
                    state.route = REGIMEN_ROUTES.first { it.second == picked }.first
                }
            }
        }

        Column {
            Label("WHEN")
            Chips(REGIMEN_SLOTS.map { it.uppercase() } + "AS NEEDED", if (state.asNeeded) listOf("AS NEEDED") else state.slots.map { it.uppercase() }, perRow = 4) { picked ->
                if (picked == "AS NEEDED") {
                    state.asNeeded = !state.asNeeded
                } else {
                    state.asNeeded = false
                    val slot = picked.lowercase()
                    if (slot in state.slots) state.slots.remove(slot) else state.slots.add(slot)
                }
            }
            if (!state.asNeeded && state.slots.size > 1) {
                Hint("${state.slots.size} doses a day: each time is its own reminder, and logging one dose ticks the first time still open.")
            }
        }

        if (!state.asNeeded) {
            Column {
                Label("HOW OFTEN")
                val labels = listOf("DAILY", "EVERY N DAYS", "DAYS OF THE WEEK")
                val types = listOf(ScheduleType.DAILY, ScheduleType.EVERY_N_DAYS, ScheduleType.WEEKDAYS)
                Chips(labels, listOf(labels[types.indexOf(state.type)]), perRow = 3) { picked -> state.type = types[labels.indexOf(picked)] }
                when (state.type) {
                    ScheduleType.EVERY_N_DAYS -> Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Every", style = FTType.BodySmall, color = FT.TextSecondary)
                        FieldTextField(state.everyN, { state.everyN = it }, "3", keyboardType = KeyboardType.Number, modifier = Modifier.width(64.dp))
                        Text("days, counted from the last dose", style = FTType.BodySmall, color = FT.TextSecondary)
                    }
                    ScheduleType.WEEKDAYS -> Box(Modifier.padding(top = 8.dp)) {
                        val names = DayOfWeek.entries.map { it.name.take(3) }
                        Chips(names, state.weekdays.map { names[it - 1] }, perRow = 7) { picked ->
                            val d = names.indexOf(picked) + 1
                            if (d in state.weekdays) state.weekdays.remove(d) else state.weekdays.add(d)
                        }
                    }
                    else -> Unit
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch("On/off cycle", state.cycleOn) { state.cycleOn = it }
                if (state.cycleOn) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FieldTextField(state.onLength, { state.onLength = it }, "8", keyboardType = KeyboardType.Number, modifier = Modifier.width(56.dp))
                        Text("on,", style = FTType.BodySmall, color = FT.TextSecondary)
                        FieldTextField(state.offLength, { state.offLength = it }, "4", keyboardType = KeyboardType.Number, modifier = Modifier.width(56.dp))
                        Text("off", style = FTType.BodySmall, color = FT.TextSecondary)
                        Box(Modifier.weight(1f)) {
                            Chips(listOf("WEEKS", "DAYS"), listOf(if (state.cycleInWeeks) "WEEKS" else "DAYS"), perRow = 2) { state.cycleInWeeks = it == "WEEKS" }
                        }
                    }
                    Hint("Counted from ${state.cycleStart}. Off days are never due and never count as missed.")
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch("Planned dose changes (titration or taper)", state.stepsOn) { on ->
                state.stepsOn = on
                if (on && state.steps.isEmpty()) {
                    state.steps.add(EditableStep(state.amount, "4"))
                    state.steps.add(EditableStep("", ""))
                }
            }
            if (state.stepsOn) {
                state.steps.forEachIndexed { i, st ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${i + 1}.", style = FTType.MonoCaption, color = FT.TextMuted)
                        FieldTextField(st.amount, { st.amount = it }, "dose", keyboardType = KeyboardType.Decimal, modifier = Modifier.width(72.dp))
                        Text(state.unit, style = FTType.BodySmall, color = FT.TextSecondary)
                        if (i < state.steps.lastIndex) {
                            Text("for", style = FTType.BodySmall, color = FT.TextSecondary)
                            FieldTextField(st.weeks, { st.weeks = it }, "4", keyboardType = KeyboardType.Number, modifier = Modifier.width(48.dp))
                            Text("weeks", style = FTType.BodySmall, color = FT.TextSecondary)
                        } else {
                            Text("from then on", style = FTType.BodySmall, color = FT.TextSecondary)
                        }
                        Spacer(Modifier.weight(1f))
                        if (state.steps.size > 1) {
                            Text("✕", style = FTType.MonoCaption, color = FT.Critical, modifier = Modifier.ftClickable { state.steps.removeAt(i) }.padding(6.dp))
                        }
                    }
                }
                Text("+ ADD A STEP", style = FTType.MonoCaption, color = FT.Emerald,
                    modifier = Modifier.ftClickable { state.steps.add(state.steps.size - 1, EditableStep("", "4")) })
                Hint("Starting ${state.stepsStart}. Each day's reminder and log carry that day's dose; the history records each change.")
            }
        }
    }
}

/** Every start, stop, restart and change of this item, newest first. */
@Composable
fun RegimenHistory(supplementId: Long) {
    var rows by remember { mutableStateOf<List<SupplementChangeRow>?>(null) }
    LaunchedEffect(supplementId) {
        rows = runCatching { SupplementsRepository(SupabaseClientProvider.client).loadChanges(supplementId) }.getOrElse { emptyList() }
    }
    val list = rows ?: return
    if (list.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label("HISTORY")
        list.forEach { c ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(changeDay(c.changedAt), style = FTType.MonoCaption, color = FT.TextMuted, modifier = Modifier.width(78.dp))
                Text(changeWords(c), style = FTType.BodySmall, color = FT.TextSecondary, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun changeDay(ts: String): String = runCatching {
    OffsetDateTime.parse(ts).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate().let { "${it.dayOfMonth} ${it.month.name.take(3)} ${it.year % 100}" }
}.getOrDefault(ts.take(10))

/** A snapshot (supplement_snapshot() in the migration) read back as a spec, for the words. */
internal fun snapshotSpec(o: JsonObject): RegimenSpec {
    fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    return regimenSpec(
        timeOfDay = str("time_of_day") ?: "morning",
        everyNDays = (o["every_n_days"] as? JsonPrimitive)?.intOrNull,
        dose = str("dose").orEmpty(),
        kind = str("kind"),
        doseAmount = (o["dose_amount"] as? JsonPrimitive)?.doubleOrNull,
        doseUnit = str("dose_unit"),
        slots = runCatching { o["slots"]!!.jsonArray.map { it.jsonPrimitive.content } }.getOrDefault(emptyList()),
        schedule = (o["schedule"] as? JsonObject)?.let { runCatching { json.decodeFromJsonElement(ScheduleJson.serializer(), it) }.getOrNull() },
        doseSteps = (o["dose_steps"] as? JsonObject)?.let { runCatching { json.decodeFromJsonElement(DoseStepsJson.serializer(), it) }.getOrNull() },
    )
}

internal fun changeWords(c: SupplementChangeRow): String {
    val after = snapshotSpec(c.after)
    val before = c.before?.let(::snapshotSpec)
    val day = runCatching { OffsetDateTime.parse(c.changedAt).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate() }.getOrDefault(LocalDate.now())
    val parts = mutableListOf<String>()
    if ("started" in c.changes) parts += "Started: ${after.doseTextOn(day)}, ${after.scheduleWords().lowercase()}"
    if ("restarted" in c.changes) parts += "Restarted: ${after.doseTextOn(day)}, ${after.scheduleWords().lowercase()}"
    if ("stopped" in c.changes) parts += "Stopped"
    if ("dose" in c.changes && "started" !in c.changes) {
        parts += if (before != null) "Dose ${before.doseTextOn(day)} → ${after.doseTextOn(day)}" else "Dose ${after.doseTextOn(day)}"
    }
    if ("schedule" in c.changes && "started" !in c.changes) {
        val cyc = after.cycle?.let { " (${it.words()})" } ?: ""
        parts += if (before != null) "${before.scheduleWords()} → ${after.scheduleWords()}$cyc" else after.scheduleWords() + cyc
    }
    return parts.joinToString(" · ").ifEmpty { c.changes.joinToString(", ") }
}

@Composable
private fun Label(text: String) {
    Text(text, style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = FTType.Caption, color = FT.TextMuted, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Switch(text: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().ftClickable { onChange(!on) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(if (on) "☑" else "☐", style = FTType.RowTitle, color = if (on) FT.Emerald else FT.TextSecondary)
        Text(text, style = FTType.BodySmall, color = FT.TextPrimary)
    }
}

/** Chips in rows of [perRow]; [selected] are highlighted; a tap reports the label. */
@Composable
private fun Chips(options: List<String>, selected: List<String>, perRow: Int, onTap: (String) -> Unit) {
    val shape = RoundedCornerShape(FT.RadiusSmall)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { option ->
                    val on = option in selected
                    Box(
                        Modifier.weight(1f)
                            .border(FT.BorderWidth, if (on) FT.Emerald else FT.GlassBorder, shape)
                            .background(if (on) FT.Emerald.copy(alpha = 0.14f) else FT.GlassFill, shape)
                            .ftClickable(shape = shape) { onTap(option) }
                            .padding(vertical = 9.dp, horizontal = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(option.uppercase(), style = FTType.Micro, color = if (on) FT.Emerald else FT.TextSecondary, maxLines = 1)
                    }
                }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
