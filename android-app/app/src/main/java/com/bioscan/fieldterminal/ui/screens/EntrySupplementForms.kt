package com.bioscan.fieldterminal.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.InjectablesRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.SupplementsRepository
import com.bioscan.fieldterminal.data.catching
import com.bioscan.fieldterminal.data.model.SupplementRow
import com.bioscan.fieldterminal.domain.REGIMEN_SLOTS
import com.bioscan.fieldterminal.domain.amountOn
import com.bioscan.fieldterminal.domain.doseStep
import com.bioscan.fieldterminal.domain.doseTextOn
import com.bioscan.fieldterminal.domain.injectable
import com.bioscan.fieldterminal.domain.isDueOn
import com.bioscan.fieldterminal.domain.nextDueAfter
import com.bioscan.fieldterminal.domain.nextSite
import com.bioscan.fieldterminal.domain.scheduleWords
import com.bioscan.fieldterminal.domain.sitesFor
import com.bioscan.fieldterminal.domain.spec
import com.bioscan.fieldterminal.domain.stateWords
import com.bioscan.fieldterminal.domain.toAppIso
import com.bioscan.fieldterminal.domain.unitsWords
import com.bioscan.fieldterminal.ui.components.DateTimeField
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FlowEssentials
import com.bioscan.fieldterminal.ui.components.FlowMore
import com.bioscan.fieldterminal.ui.components.FlowQuestion
import com.bioscan.fieldterminal.ui.components.FormBackHandler
import com.bioscan.fieldterminal.ui.theme.FTType
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// + LOG: supplements taken (by block, bundle or single item), and the edit of one logged take.

// "Bundled by time of day" (added 2026-09-15 per direct user request):
// morning/afternoon/night supplements are logged as one group -- checking
// the group logs every supplement in it, since you take them together, not
// individually. As-needed supplements get their own checkbox each, since
// which ones you take genuinely varies. Each checked supplement becomes its
// own supplement_log row on save, so afterward it's individually deletable
// from the Log feed like any other entry -- no separate "batch" concept
// needed for "add or remove single items."
@Composable
internal fun SupplementsForm(
    saving: Boolean,
    initialDateTime: LocalDateTime = LocalDateTime.now(),
    // The bundle the Status hub's + SUPPS found still due now; ticked on open, untick to change.
    preselectBundle: String? = null,
    onSave: (takenAt: String, items: List<Triple<Long, String, String>>, source: String, quantities: Map<Long, Double>, sites: Map<Long, String>) -> Unit,
) {
    var dateTime by remember { mutableStateOf(initialDateTime) }
    var supplements by remember { mutableStateOf<List<SupplementRow>?>(null) }
    // Injectables: each injected dose's site, defaulting to the one rested longest (domain/Injectables.kt).
    var siteHistory by remember { mutableStateOf<Map<Long, List<Pair<String, Instant>>>>(emptyMap()) }
    // The history could not be read: no site is suggested or pre-picked, since "rested longest" would be a guess.
    var siteHistoryFailed by remember { mutableStateOf(false) }
    var sites by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var checkedBundles by remember { mutableStateOf(setOfNotNull(preselectBundle)) }
    var checkedAsNeeded by remember { mutableStateOf(setOf<Long>()) }
    // DAV-362: items picked by barcode are selected one by one, never as a time-of-day bundle.
    var scanned by remember { mutableStateOf<List<SupplementRow>>(emptyList()) }
    var scannedChecked by remember { mutableStateOf(setOf<Long>()) }
    var recentLastTaken by remember { mutableStateOf<Map<Long, LocalDate>>(emptyMap()) }
    var takesToday by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var quantities by remember { mutableStateOf<Map<Long, Double>>(emptyMap()) }

    LaunchedEffect(Unit) {
        try {
            val repo = SupplementsRepository(SupabaseClientProvider.client)
            val active = repo.loadOverview().active
            supplements = active
            val takes = repo.loadRecentTakes(active.map { it.id })
            recentLastTaken = takes.lastTaken
            takesToday = takes.takesToday
            val injectableIds = active.filter { it.spec().injectable }.map { it.id }
            if (injectableIds.isNotEmpty()) {
                catching { InjectablesRepository(SupabaseClientProvider.client).siteHistory(injectableIds) }
                    .onSuccess { siteHistory = it }.onFailure { siteHistoryFailed = true }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline: show an empty list instead of crashing the sheet.
            Log.w("AddEntry", "supplement list load failed", e)
            if (supplements == null) supplements = emptyList()
        }
    }

    val today = LocalDate.now()
    val list = supplements ?: emptyList()

    // Supplements with an interval that haven't reached their next due date
    // are removed from the pickable groups so the bundle toggle doesn't log
    // them too early. They appear in a "NOT DUE TODAY" section instead.
    // Regimen model (domain/Regimen.kt): due by each item's own schedule (every N days, weekdays, on-cycle). An item taken
    // twice a day sits in both its bundles; the dose is today's (a planned dose step moves it on).
    val specs = list.associate { it.id to it.spec() }
    val asNeeded = list.filter { specs.getValue(it.id).asNeeded }
    val dueList = list.filter { !specs.getValue(it.id).asNeeded && specs.getValue(it.id).isDueOn(today, recentLastTaken[it.id]) }
    val notDueList = list.filter { !specs.getValue(it.id).asNeeded && it !in dueList }

    val groups = REGIMEN_SLOTS.associateWith { slot -> dueList.filter { slot in specs.getValue(it.id).slots } }.filterValues { it.isNotEmpty() }
    fun doseOf(s: SupplementRow) = specs[s.id]?.doseTextOn(today) ?: s.dose
    // The form decides the step: a whole capsule, half a scored tablet, a quarter of a measured dose (domain/RegimenForms.kt).
    fun stepOf(s: SupplementRow) = doseStep(s.form, s.scored, specs[s.id]?.amountOn(today))
    fun wordsOf(s: SupplementRow, q: Double) = specs[s.id]?.let { unitsWords(q, it.amountOn(today), it.unit) }

    // DAV-156: each selected item carries its roster dose string along so
    // addSupplementsTaken can parse a real per-supplement dose default --
    // Creatine's "5 g" and Boron's "10 mg" are different doses, so this has
    // to travel per-item, not as one shared field for the whole save.
    val selectedItems = buildList<Triple<Long, String, String>> {
        checkedBundles.forEach { tod -> groups[tod]?.forEach { s -> if (none { it.first == s.id }) add(Triple(s.id, s.name, doseOf(s))) } }
        asNeeded.filter { it.id in checkedAsNeeded }.forEach { add(Triple(it.id, it.name, doseOf(it))) }
        scanned.filter { it.id in scannedChecked }.forEach { s -> if (none { it.first == s.id }) add(Triple(s.id, s.name, doseOf(s))) }
    }
    // "scan" only when everything being saved was picked by barcode.
    val source = if (scannedChecked.isNotEmpty() && checkedBundles.isEmpty() && checkedAsNeeded.isEmpty()) "scan" else "manual"

    val injected = selectedItems.filter { (quantities[it.first] ?: 1.0) > 0.0 }.mapNotNull { (id, name, _) ->
        specs[id]?.takeIf { it.injectable }?.let { Triple(id, name, it) }
        }
        val chosenSites = injected.associate { (id, _, spec) ->
            id to (sites[id] ?: nextSite(sitesFor(spec), siteHistory[id].orEmpty())?.key?.takeIf { !siteHistoryFailed })
        }.filterValues { it != null }.mapValues { it.value!! }
    // One page (Logging Flows Redesign Plan, Nov 2026): the time, then each time of day that has anything due with its
    // bundle tick and each item's quantity, what was scanned, and AS NEEDED and NOT DUE TODAY folded at the foot. The
    // INJECTION SITE page follows only when an injection is ticked (a different action); SAVE is offered on the first page
    // as soon as every injected dose has its site (the suggested one, rested longest).
    var sitePage by rememberSaveable { mutableStateOf(false) }
    if (injected.isEmpty() && sitePage) sitePage = false
    FormBackHandler(enabled = sitePage) { sitePage = false }
    val doses = selectedItems.filter { (quantities[it.first] ?: 1.0) > 0.0 }
    val canSave = doses.isNotEmpty() && injected.all { chosenSites[it.first] != null }
    val save = { onSave(dateTime.toAppIso(), doses, source, quantities, chosenSites) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!sitePage) {
            FlowEssentials {
                DateTimeField("WHEN", dateTime, { dateTime = it })
                when {
                    supplements == null -> Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = FT.DomainLog)
                    }
                    list.isEmpty() -> Text("No active supplements to log.", style = FTType.BodySmall, color = FT.TextSecondary)
                    groups.isEmpty() -> Text("Nothing due now. Pick from AS NEEDED below, or scan.", style = FTType.BodySmall, color = FT.TextSecondary)
                }
                REGIMEN_SLOTS.forEach { timeOfDay ->
                    val itemsInGroup = groups[timeOfDay]
                    if (!itemsInGroup.isNullOrEmpty()) {
                        BundleToggleRow(
                            label = timeOfDay.uppercase(),
                            itemNames = itemsInGroup.joinToString(", ") { it.name },
                            checked = timeOfDay in checkedBundles,
                            onToggle = {
                                checkedBundles = if (timeOfDay in checkedBundles) checkedBundles - timeOfDay else checkedBundles + timeOfDay
                            },
                        )
                        if (timeOfDay in checkedBundles) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 16.dp)) {
                                itemsInGroup.forEach { supp ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(supp.name, style = FTType.BodySmall, color = FT.TextSecondary, modifier = Modifier.weight(1f))
                                        QtyRow(quantities[supp.id] ?: 1.0, stepOf(supp), { q -> wordsOf(supp, q) }) { quantities = quantities + (supp.id to it) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (list.isNotEmpty()) {
                BarcodeLogPanel(list) { hits ->
                    scanned = (scanned + hits).distinctBy { it.id }
                    scannedChecked = scannedChecked + hits.map { it.id }
                }
            }
            if (scanned.isNotEmpty()) {
                FormLabel("SCANNED")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    scanned.forEach { supp ->
                        val checked = supp.id in scannedChecked
                        CheckToggleRow(
                            label = supp.name,
                            checked = checked,
                            onToggle = { scannedChecked = if (checked) scannedChecked - supp.id else scannedChecked + supp.id },
                        )
                        if (checked) {
                            QtyRow(quantities[supp.id] ?: 1.0, stepOf(supp), { q -> wordsOf(supp, q) }) { quantities = quantities + (supp.id to it) }
                        }
                    }
                }
            }
            if (asNeeded.isNotEmpty()) {
                FlowMore("supps_as_needed", label = "AS NEEDED", summary = asNeeded.joinToString(", ") { it.name }) {
                    asNeeded.forEach { supp ->
                        val checked = supp.id in checkedAsNeeded
                        CheckToggleRow(
                            label = supp.name,
                            checked = checked,
                            onToggle = { checkedAsNeeded = if (checked) checkedAsNeeded - supp.id else checkedAsNeeded + supp.id },
                        )
                        if (checked) {
                            QtyRow(quantities[supp.id] ?: 1.0, stepOf(supp), { q -> wordsOf(supp, q) }) { quantities = quantities + (supp.id to it) }
                        }
                    }
                }
            }
            if (notDueList.isNotEmpty()) {
                FlowMore("supps_not_due", label = "NOT DUE TODAY", summary = notDueList.joinToString(", ") { it.name }) {
                    notDueList.forEach { supp ->
                        val nextDate = specs.getValue(supp.id).nextDueAfter(today, recentLastTaken[supp.id])
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(supp.name, style = FTType.BodySmall, color = FT.TextMuted, modifier = Modifier.weight(1f))
                            Text(
                                specs.getValue(supp.id).stateWords(today) ?: (if (nextDate != null) "Next: ${if (nextDate == today.plusDays(1)) "tomorrow" else nextDate.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH))}" else specs.getValue(supp.id).scheduleWords()),
                                style = FTType.MonoCaption,
                                color = FT.TextMuted,
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (injected.isNotEmpty()) FTButton("INJECTION SITE ›", modifier = Modifier.weight(1f), enabled = !saving) { sitePage = true }
                Box(Modifier.weight(1f)) { SaveButton(saving, canSave) { save() } }
            }
        } else {
            FlowQuestion("Where did each injection go?", "The suggested site is the one rested longest.")
            injected.forEach { (id, name, spec) ->
                val options = sitesFor(spec)
                val suggested = nextSite(options, siteHistory[id].orEmpty())
                val units = list.firstOrNull { it.id == id }?.syringeUnits?.let { " · ${com.bioscan.fieldterminal.domain.formatAmount(it)} units as prescribed" } ?: ""
                if (siteHistoryFailed) Text("$name$units · your site history couldn't be read: pick the site yourself.", style = FTType.Caption, color = FT.Warn)
                else Text("$name$units · suggested: ${suggested?.label ?: "—"} (rested longest)", style = FTType.Caption, color = FT.TextSecondary)
                TextChipRow(options.map { it.label }, options.firstOrNull { it.key == chosenSites[id] }?.label, perRow = 2) { picked ->
                    options.firstOrNull { it.label == picked }?.let { sites = sites + (id to it.key) }
                }
            }
            SaveButton(saving, canSave) { save() }
        }
    }
}

// DAV-156. The first edit form Supplement has ever had -- previously
// delete-only, since a taken supplement was "add or remove as a whole," but
// dose being a real editable field makes it field-editable now too. Name
// isn't editable here (picking a different supplement is "delete and
// re-log," not "edit"), but WHEN is, matching every other manual log type
// per DAV-157.
@Composable
internal fun SupplementEditForm(
    saving: Boolean,
    supplementName: String,
    initialDateTime: LocalDateTime = LocalDateTime.now(),
    initialDoseValue: Double? = null,
    initialDoseUnit: String = "",
    onSave: (takenAt: String, doseValue: Double?, doseUnit: String?) -> Unit,
) {
    var dateTime by remember { mutableStateOf(initialDateTime) }
    var doseValue by remember { mutableStateOf(initialDoseValue?.let { if (it == Math.floor(it)) it.toInt().toString() else it.toString() } ?: "") }
    var doseUnit by remember { mutableStateOf(initialDoseUnit) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(supplementName, style = FTType.Value, color = FT.TextPrimary)
        DateTimeField("WHEN", dateTime, { dateTime = it })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) { FormLabel("DOSE (OPTIONAL)"); FieldTextField(doseValue, { doseValue = it }, "e.g. 500", keyboardType = KeyboardType.Number) }
            Column(Modifier.weight(1f)) { FormLabel("UNIT (OPTIONAL)"); FieldTextField(doseUnit, { doseUnit = it }, "e.g. mg") }
        }
        SaveButton(saving, true) {
            onSave(dateTime.toAppIso(), doseValue.toDoubleOrNull(), doseUnit.trim().ifBlank { null })
        }
    }
}
