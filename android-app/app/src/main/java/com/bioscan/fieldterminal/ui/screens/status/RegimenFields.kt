package com.bioscan.fieldterminal.ui.screens.status

import com.bioscan.fieldterminal.data.catching
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
import androidx.compose.ui.graphics.Color
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
import com.bioscan.fieldterminal.domain.FORM_RULES
import com.bioscan.fieldterminal.domain.Splitting
import com.bioscan.fieldterminal.domain.formRule
import com.bioscan.fieldterminal.domain.routeFor
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
    var form by mutableStateOf(spec?.form ?: "capsule")
    var route by mutableStateOf(routeFor(spec?.form ?: "capsule", spec?.route) ?: "oral")
    var amount by mutableStateOf(spec?.amount?.let(::formatAmount) ?: "1")
    var unit by mutableStateOf(spec?.unit?.takeIf { u -> formRule(spec.form)?.units?.contains(u) != false } ?: formRule(spec?.form ?: "capsule")!!.units.first())
    /** A tablet that can be split in half. */
    var scored by mutableStateOf(existing?.scored ?: false)
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
    var syringeUnits by mutableStateOf(existing?.syringeUnits?.let(::formatAmount) ?: "")

    /** Injected (under the skin, into muscle, or an injection form): sites, vial and syringe units apply. */
    val injectable: Boolean get() = route == "subcutaneous" || route == "intramuscular" || form == "injection"

    /** The form sets the dose's unit and the route (domain/RegimenForms.kt); a unit or route it does not allow is replaced. */
    fun chooseForm(key: String) {
        val rule = formRule(key) ?: return
        form = key
        if (unit !in rule.units) unit = rule.units.first()
        route = routeFor(key, route) ?: route
        if (key != "tablet") scored = false
    }

    /** Kind-led defaults: a new peptide is an injection under the skin, in mcg. */
    fun chooseKind(k: RegimenKind) {
        kind = k
        if (k == RegimenKind.PEPTIDE && form == "capsule") chooseForm("injection")
    }

    /** True when [a] splits a form that is taken whole (or in halves, a scored tablet). */
    fun splitsWrong(a: Double): Boolean {
        val rule = formRule(form) ?: return false
        if (rule.splitting == Splitting.MEASURED) return false
        val step = if (form == "tablet" && scored) 0.5 else 1.0
        return Math.abs(a / step - Math.round(a / step)) > 1e-9
    }

    /** The draft to save, or the first thing that is missing. */
    fun draft(name: String): Pair<RegimenDraft?, String?> {
        if (name.isBlank()) return null to "Add a name."
        val a = amount.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 } ?: return null to "Add how much one dose is."
        if (splitsWrong(a)) {
            return null to (if (form == "tablet") "A tablet is taken whole${if (scored) " or in halves" else " (switch on Scored to allow halves)"}." else "One dose is a whole number of ${unit}s.")
        }
        if (!asNeeded && slots.isEmpty()) return null to "Pick at least one time of day, or As needed."
        val units = syringeUnits.replace(',', '.').trim().takeIf { injectable && it.isNotEmpty() }?.let {
            it.toDoubleOrNull()?.takeIf { u -> u > 0 && u <= 100 } ?: return null to "Syringe units are a number from 1 to 100 (U-100 syringe)."
        }
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
            name = name.trim(), kind = kind.key, form = form, route = routeFor(form, route), amount = a, unit = unit, scored = form == "tablet" && scored,
            slots = if (asNeeded) emptyList() else REGIMEN_SLOTS.filter { it in slots }, schedule = schedule, steps = stepJson,
            syringeUnits = units,
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
            Label("FORM")
            Chips(FORM_RULES.map { it.label }, listOfNotNull(formRule(state.form)?.label), perRow = 4) { picked ->
                FORM_RULES.firstOrNull { it.label == picked }?.let { state.chooseForm(it.key) }
            }
            if (state.form == "tablet") {
                Switch("Scored: can be split in half", state.scored) { state.scored = it }
            }
            val rule = formRule(state.form)
            if (rule != null && rule.fixedRoute == null) {
                Box(Modifier.padding(top = 8.dp)) {
                    Column {
                        Label("HOW IT IS TAKEN")
                        val options = REGIMEN_ROUTES.filter { it.first in rule.routes }
                        Chips(options.map { it.second }, listOfNotNull(options.firstOrNull { it.first == state.route }?.second), perRow = 3) { picked ->
                            state.route = options.first { it.second == picked }.first
                        }
                    }
                }
            }
        }

        if (state.injectable) {
            Column {
                Label("SYRINGE UNITS YOUR PRESCRIBER GAVE (U-100, OPTIONAL)")
                FieldTextField(state.syringeUnits, { state.syringeUnits = it }, "e.g. 10", keyboardType = KeyboardType.Decimal)
                Hint("Shown on each dose as given. The app never works out a dose; it only checks these units against the vial's mix below.")
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
                            com.bioscan.fieldterminal.ui.components.FTIconButton(com.bioscan.fieldterminal.ui.components.FTIcons.Close, "Remove this step", { state.steps.removeAt(i) }, size = com.bioscan.fieldterminal.ui.components.FTIconSize.Row)
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
        rows = catching { SupplementsRepository(SupabaseClientProvider.client).loadChanges(supplementId) }.getOrElse { emptyList() }
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
        Text(text, style = FTType.BodySmall, color = FT.TextPrimary, modifier = Modifier.weight(1f))
        com.bioscan.fieldterminal.ui.components.FTSwitch(checked = on, onCheckedChange = null)
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
                            .border(FT.BorderWidth, if (on) Color.Transparent else FT.GlassBorder, shape)
                            .background(if (on) FT.PlatinumSolid else FT.GlassFill, shape)
                            .ftClickable(shape = shape) { onTap(option) }
                            .padding(vertical = 9.dp, horizontal = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        // A chip shows the name only ("Injection (vial / pen)" reads INJECTION): the note in brackets did not fit.
                        Text(option.substringBefore(" (").uppercase(), style = FTType.Micro, color = if (on) FT.PlatinumInk else FT.TextSecondary, maxLines = 1)
                    }
                }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** One dose as a count of the form's unit (an injection: its active amount), shown above the dose's ingredients. */
@Composable
fun DoseCountField(state: RegimenEditorState) {
    val rule = formRule(state.form)
    val units = rule?.units ?: REGIMEN_UNITS
    Column {
        Label(if (state.form == "injection") "ONE DOSE (THE ACTIVE AMOUNT)" else "ONE DOSE")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldTextField(state.amount, { state.amount = it }, "e.g. 2", keyboardType = KeyboardType.Decimal, modifier = Modifier.width(90.dp))
            if (units.size == 1) Text(units.first() + "s".takeIf { (state.amount.replace(',', '.').toDoubleOrNull() ?: 1.0) > 1 }.orEmpty(),
                style = FTType.BodySmall, color = FT.TextSecondary)
        }
        if (units.size > 1) {
            Box(Modifier.padding(top = 8.dp)) { Chips(units, listOf(state.unit), perRow = 5) { state.unit = it } }
        }
        val hint = when {
            rule?.splitting == Splitting.WHOLE && state.form == "tablet" && state.scored -> "Whole or half tablets."
            rule?.splitting == Splitting.WHOLE -> "Whole ${rule.units.first()}s only."
            state.form == "injection" -> "The vial below turns it into a volume and syringe units."
            else -> null
        }
        // Said as it is typed, not only on SAVE.
        val wrong = state.amount.replace(',', '.').toDoubleOrNull()?.let(state::splitsWrong) == true
        hint?.let { if (wrong) Text(it, style = FTType.Caption, color = FT.Warning) else Hint(it) }
    }
}
