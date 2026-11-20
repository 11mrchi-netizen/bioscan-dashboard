package com.bioscan.fieldterminal.ui.screens.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.RiskScreensRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.risk.HealthBackground
import com.bioscan.fieldterminal.ui.components.FTFlow
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.FlowField
import com.bioscan.fieldterminal.ui.components.FlowPage
import com.bioscan.fieldterminal.ui.components.FlowQuestion
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.components.rememberFlowState
import com.bioscan.fieldterminal.ui.screens.TextChipRow
import com.bioscan.fieldterminal.ui.theme.FTType
import kotlinx.coroutines.CancellationException
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Risk profile R3: the one-time health background the risk screens need (PREVENT, Life's Essential 8, FINDRISC, the
// dementia factors) and no sensor or lab gives. One page; every question optional, so a partial answer still saves. Saved
// answers are shown, not asked again; editing changes them in place.

private val YES_NO = listOf("YES", "NO")
private fun yn(b: Boolean?) = b?.let { if (it) "YES" else "NO" }
private fun ynOf(s: String?) = s?.let { it == "YES" }

/** A chip question whose stored codes differ from its words. */
@Composable
private fun CodedChips(label: String, options: List<Pair<String, String>>, value: String?, perRow: Int = 3, onPick: (String?) -> Unit) {
    FlowField(label) {
        TextChipRow(options.map { it.second }, options.firstOrNull { it.first == value }?.second, perRow) { picked ->
            onPick(options.firstOrNull { it.second == picked }?.first)
        }
    }
}

@Composable
private fun YesNo(label: String, value: Boolean?, onPick: (Boolean?) -> Unit) {
    FlowField(label) { TextChipRow(YES_NO, yn(value), perRow = 2) { onPick(ynOf(it)) } }
}

@Composable
fun HealthBackgroundSheet(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val repo = remember { RiskScreensRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var b by remember { mutableStateOf(HealthBackground()) }
    var quit by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            b = repo.loadBackground()
            quit = b.quitYear?.toString() ?: ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = describeLoadFailure(e)
        }
        loaded = true
    }

    FTFlow(
        title = "HEALTH BACKGROUND", pages = listOf(FlowPage("ANSWERS")), state = rememberFlowState(), onClose = onDismiss,
        canSave = loaded && !saving, saving = saving, wash = FT.DomainUser, error = error?.let { "Couldn't save: $it" },
        onSave = {
            saving = true
            error = null
            val toSave = b.copy(quitYear = if (b.smoking == "former") quit.trim().toIntOrNull()?.takeIf { it in 1940..2100 } else null)
            scope.launchSafe(onError = { e -> error = describeLoadFailure(e) }, onDone = { saving = false }) {
                repo.saveBackground(toSave)
                onSaved()
                onDismiss()
            }
        },
    ) {
        FlowQuestion("What the risk screens need and no sensor or lab can tell. Answer what you know; skip the rest.")
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
            CodedChips("SMOKING OR VAPING", listOf("never" to "NEVER", "former" to "STOPPED", "current" to "NOW"), b.smoking) { b = b.copy(smoking = it) }
            if (b.smoking == "former") {
                FlowField("YEAR YOU STOPPED") { FieldTextField(quit, { quit = it }, "e.g. 2018", keyboardType = KeyboardType.Number) }
            }
            YesNo("SOMEONE SMOKES INDOORS WHERE YOU LIVE", b.secondhandSmoke) { b = b.copy(secondhandSmoke = it) }
            YesNo("DIAGNOSED WITH DIABETES", b.diabetesDiagnosed) { b = b.copy(diabetesDiagnosed = it) }
            YesNo("BLOOD PRESSURE MEDICINE", b.bpMeds) { b = b.copy(bpMeds = it) }
            YesNo("CHOLESTEROL MEDICINE (A STATIN OR OTHER)", b.lipidMeds) { b = b.copy(lipidMeds = it) }
            YesNo("EVER TOLD YOUR BLOOD GLUCOSE WAS HIGH", b.highGlucoseHistory) { b = b.copy(highGlucoseHistory = it) }
            CodedChips("FAMILY WITH DIABETES", listOf("none" to "NONE", "second" to "GRANDPARENT, AUNT, UNCLE, COUSIN", "first" to "PARENT, SIBLING, CHILD"), b.familyDiabetes, perRow = 1) { b = b.copy(familyDiabetes = it) }
            YesNo("VEGETABLES OR FRUIT EVERY DAY", b.vegFruitDaily) { b = b.copy(vegFruitDaily = it) }
            CodedChips("HEARING", listOf("good" to "GOOD", "aided" to "WITH A HEARING AID", "difficulty" to "DIFFICULTY, NO AID"), b.hearing) { b = b.copy(hearing = it) }
            CodedChips("VISION", listOf("good" to "GOOD", "corrected" to "WITH GLASSES OR LENSES", "difficulty" to "DIFFICULTY, NOT CORRECTED"), b.vision) { b = b.copy(vision = it) }
            YesNo("EVER A HEAD INJURY WITH CONCUSSION", b.headInjury) { b = b.copy(headInjury = it) }
            CodedChips("EDUCATION, HIGHEST COMPLETED", listOf("primary" to "PRIMARY", "secondary" to "SECONDARY", "tertiary" to "UNIVERSITY OR COLLEGE"), b.education) { b = b.copy(education = it) }
            Text("Used only by the risk screens on this phone and the dashboard; the assistant cannot read it.", style = FTType.Caption, color = FT.TextMuted)
        }
    }
}
