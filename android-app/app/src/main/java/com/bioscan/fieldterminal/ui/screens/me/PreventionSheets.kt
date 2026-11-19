package com.bioscan.fieldterminal.ui.screens.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.PreventionRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.risk.ChecklistState
import com.bioscan.fieldterminal.domain.risk.PreventiveStatus
import com.bioscan.fieldterminal.ui.components.DateField
import com.bioscan.fieldterminal.ui.components.FTFlow
import com.bioscan.fieldterminal.ui.components.FlowField
import com.bioscan.fieldterminal.ui.components.FlowPage
import com.bioscan.fieldterminal.ui.components.FlowQuestion
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.components.rememberFlowState
import com.bioscan.fieldterminal.ui.screens.TextChipRow
import com.bioscan.fieldterminal.ui.theme.FTType
import java.time.LocalDate
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Risk profile R4: the home and water checklist (one page; the answers already given are shown, a changed or new answer is
// saved as a new row) and recording a preventive item (when it was done and how, or that it does not apply).

@Composable
fun HomeChecklistSheet(states: List<ChecklistState>, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val defs = remember { PreventionRepository.checklistDefs(context) }
    val repo = remember { PreventionRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    val picked = remember { mutableStateMapOf<String, String>().apply { states.forEach { s -> s.answer?.let { put(s.item.id, it.value) } } } }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Only new or changed answers become rows (an unchanged answer older than a year is re-confirmed by saving it).
    val changed = picked.filter { (k, v) -> val s = states.firstOrNull { it.item.id == k }; s?.answer?.value != v || s.stale }

    FTFlow(
        title = "HOME AND WATER", pages = listOf(FlowPage("ANSWERS")), state = rememberFlowState(), onClose = onDismiss,
        canSave = changed.isNotEmpty() && !saving, saving = saving, wash = FT.DomainUser, error = error?.let { "Couldn't save: $it" },
        onSave = {
            saving = true
            scope.launchSafe(onError = { e -> error = describeLoadFailure(e) }, onDone = { saving = false }) {
                repo.saveChecklist(defs, changed)
                onSaved()
                onDismiss()
            }
        },
    ) {
        FlowQuestion("A few things about your home that change what you breathe and drink. Skip any you don't know.")
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
            defs.items.forEach { item ->
                FlowField(item.question.uppercase()) {
                    TextChipRow(item.choices.map { it.second.uppercase() }, item.words(picked[item.id])?.uppercase(), perRow = if (item.choices.size > 3) 1 else 2) { p ->
                        val code = item.choices.firstOrNull { it.second.uppercase() == p }?.first
                        if (code == null) picked.remove(item.id) else picked[item.id] = code
                    }
                }
            }
        }
    }
}

@Composable
fun PreventiveRecordSheet(status: PreventiveStatus, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val repo = remember { PreventionRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    val item = status.item
    var date by remember { mutableStateOf(LocalDate.now()) }
    var method by remember { mutableStateOf(status.method ?: item.methodList.firstOrNull()?.code) }
    var notApplicable by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    FTFlow(
        title = item.name.uppercase(), pages = listOf(FlowPage("RECORD")), state = rememberFlowState(), onClose = onDismiss,
        canSave = !saving, saving = saving, wash = FT.DomainUser, error = error?.let { "Couldn't save: $it" },
        onSave = {
            saving = true
            scope.launchSafe(onError = { e -> error = describeLoadFailure(e) }, onDone = { saving = false }) {
                repo.addRecord(item.id, if (notApplicable) null else date, if (notApplicable) null else method, notApplicable)
                onSaved()
                onDismiss()
            }
        },
    ) {
        FlowQuestion(if (notApplicable) "It will be hidden from the list until you record it again." else "When was it done?")
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
            FlowField("RECORD") {
                TextChipRow(listOf("DONE", "DOES NOT APPLY TO ME"), if (notApplicable) "DOES NOT APPLY TO ME" else "DONE", perRow = 2) { p -> notApplicable = p == "DOES NOT APPLY TO ME" }
            }
            if (!notApplicable) {
                DateField("DATE", date, { date = it })
                if (item.methodList.isNotEmpty()) {
                    FlowField("HOW") {
                        TextChipRow(item.methodList.map { it.words.uppercase() }, item.methodList.firstOrNull { it.code == method }?.words?.uppercase(), perRow = 1) { p ->
                            method = item.methodList.firstOrNull { it.words.uppercase() == p }?.code ?: method
                        }
                    }
                }
            }
            Text(item.source, style = FTType.Caption, color = FT.TextMuted)
        }
    }
}
