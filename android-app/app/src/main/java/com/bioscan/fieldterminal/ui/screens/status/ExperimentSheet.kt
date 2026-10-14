package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.ExperimentsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.EXPERIMENT_METRICS
import com.bioscan.fieldterminal.domain.ExperimentProtocol
import com.bioscan.fieldterminal.domain.MdePreviewRow
import com.bioscan.fieldterminal.domain.buildProtocol
import com.bioscan.fieldterminal.domain.experimentProblems
import com.bioscan.fieldterminal.domain.mdeCaveat
import com.bioscan.fieldterminal.domain.mdeText
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.screens.TextChipRow
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate

// Analysis Layer 3 / Phase 5. Pre-registers an N-of-1 experiment for one supplement. What is compared, which outcome is
// primary and the dates are fixed when REGISTER is pressed (the database refuses later changes). The sheet shows, for every
// outcome, the smallest difference this design could detect on the person's own data, so an outcome the design cannot
// resolve is not chosen blindly.
const val SUPPLEMENT_EXPERIMENT_INTRO =
    "Phase A: you do not take it. Phase B: you take it as usual. Day 1 of A is today. You pick the one outcome now and the plan is locked once registered, so the result cannot be re-chosen after seeing the data. It compares phases, adjusted for day of week, and says plainly when it can see no difference."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExperimentSheet(
    subjectName: String,
    intro: String = SUPPLEMENT_EXPERIMENT_INTRO,
    register: suspend (name: String, primary: String, protocol: ExperimentProtocol, preview: List<MdePreviewRow>) -> Unit,
    onDismiss: () -> Unit,
    onRegistered: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val repo = remember { ExperimentsRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var primary by remember { mutableStateOf<String?>(null) }
    var design by remember { mutableStateOf("AB") }
    var days by remember { mutableStateOf(28) }
    var washout by remember { mutableStateOf(0) }
    var preview by remember { mutableStateOf<List<MdePreviewRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(days) {
        loading = true
        error = null
        try {
            preview = repo.previewMde(days, days)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            preview = emptyList()
            error = e.message ?: "Could not compute what this design can detect"
        } finally {
            loading = false
        }
    }

    val problems = experimentProblems(primary, days, design, washout)
    val label = EXPERIMENT_METRICS.associate { it.first to it.second }
    val chosenPreview = preview.firstOrNull { it.metric == primary }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = FT.SheetShape, containerColor = FT.Surface, contentColor = FT.TextPrimary) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("N-OF-1 EXPERIMENT", style = FTType.SectionTitle, color = FT.DomainLog)
            Text(subjectName, style = FTType.RowTitle, color = FT.TextPrimary)
            Text(intro, style = FTType.BodySmall, color = FT.TextSecondary)

            Label("DESIGN")
            TextChipRow(listOf("ONE CYCLE (A, B)", "TWO CYCLES (A, B, A, B)"), if (design == "AB") "ONE CYCLE (A, B)" else "TWO CYCLES (A, B, A, B)", perRow = 1) {
                design = if (it?.startsWith("TWO") == true) "ABAB" else "AB"
            }
            Label("DAYS PER PHASE")
            TextChipRow(listOf("14", "21", "28", "42"), days.toString(), perRow = 4) { it?.toIntOrNull()?.let { d -> days = d } }
            Label("WASHOUT DAYS AT THE START OF B (SET ASIDE)")
            TextChipRow(listOf("0", "3", "7"), washout.toString(), perRow = 3) { it?.toIntOrNull()?.let { d -> washout = d } }

            Label("WHAT COULD THIS DESIGN SEE?")
            when {
                loading -> CircularProgressIndicator(color = FT.DomainLog, modifier = Modifier.size(16.dp))
                preview.isEmpty() -> Text(error ?: "Not enough recent data to estimate this yet.", style = FTType.BodySmall, color = FT.TextSecondary)
                else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    preview.forEach { p ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(label[p.metric] ?: p.label, style = FTType.BodySmall, color = FT.TextPrimary)
                            Text("±" + mdeText(p).substringAfter(": "), style = FTType.Telemetry, color = FT.TextSecondary)
                        }
                    }
                    Text("Differences smaller than this would not show up, however real. Pick an outcome you expect to move by more than that.", style = FTType.Caption, color = FT.TextMuted)
                }
            }

            Label("PRIMARY OUTCOME (ONE)")
            TextChipRow(EXPERIMENT_METRICS.map { it.second }, EXPERIMENT_METRICS.firstOrNull { it.first == primary }?.second, perRow = 2) { picked ->
                primary = EXPERIMENT_METRICS.firstOrNull { it.second == picked }?.first
            }
            chosenPreview?.let { p ->
                Text(mdeText(p), style = FTType.BodySmall, color = FT.TextPrimary)
                mdeCaveat(p)?.let { Text(it, style = FTType.BodySmall, color = FT.TextSecondary) }
            }

            problems.forEach { Text(it, style = FTType.BodySmall, color = FT.TextSecondary) }
            error?.takeIf { preview.isNotEmpty() }?.let { Text(it, style = FTType.BodySmall, color = FT.Critical) }

            FTButton(label = if (saving) "REGISTERING..." else "REGISTER AND START", enabled = problems.isEmpty() && !saving && !loading, emphasis = FTButtonEmphasis.Primary) {
                saving = true
                scope.launch {
                    try {
                        register("$subjectName ${LocalDate.now()}", primary!!, buildProtocol(LocalDate.now(), design, days, washout), preview)
                        onRegistered()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "Could not register"
                    } finally {
                        saving = false
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = FTType.LabelCaps, color = FT.TextSecondary)
}
