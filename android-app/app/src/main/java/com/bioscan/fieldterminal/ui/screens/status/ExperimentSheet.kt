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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.mutableIntStateOf
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
import com.bioscan.fieldterminal.domain.PAIR_CHOICES
import com.bioscan.fieldterminal.domain.PERIOD_DAY_CHOICES
import com.bioscan.fieldterminal.domain.WASHOUT_CHOICES
import com.bioscan.fieldterminal.domain.mdeCaveat
import com.bioscan.fieldterminal.domain.pairsPowerLine
import com.bioscan.fieldterminal.domain.randomisedPairsProtocol
import com.bioscan.fieldterminal.domain.randomisedProblems
import com.bioscan.fieldterminal.domain.zoneSpec
import com.bioscan.fieldterminal.domain.zoneText
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.screens.TextChipRow
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.time.LocalDate

// Analysis review phase F (was Analysis Layer 3 / Phase 5's fixed A-then-B design). Registers a randomised self-experiment
// for one supplement or fueling product: pairs of equal periods, with and without, the order inside each pair drawn on the
// phone when REGISTER is pressed, every period starting with a washout whose days do not count. The outcome, the no-effect
// zone and the drawn schedule are fixed at registration (the database refuses later changes and a schedule starting in
// the past). Before registering, the sheet says what the design could detect on the person's own data.
const val SUPPLEMENT_EXPERIMENT_INTRO =
    "Pairs of equal periods: in each pair one period with it and one without, in an order the app draws at random when you register. Every period starts with a washout whose days don't count, so what lingers from the period before doesn't leak in. Day 1 is tomorrow. You choose the outcome and the smallest change that would matter now; both are locked once registered, so the answer can't be re-chosen after seeing the data."

const val BLINDING_HELP =
    "Optional: someone else fills a weekly pill organiser with it or a look-alike placebo, following the schedule. You take each day's slot, so expectation can't colour the result. The app then keeps the schedule out of sight."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExperimentSheet(
    subjectName: String,
    intro: String = SUPPLEMENT_EXPERIMENT_INTRO,
    // The outcome to start on (a repeatable intervention's main expected outcome); still changeable before registering.
    initialPrimary: String? = null,
    register: suspend (name: String, primary: String, protocol: ExperimentProtocol, preview: List<MdePreviewRow>) -> Unit,
    onDismiss: () -> Unit,
    onRegistered: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val repo = remember { ExperimentsRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var primary by remember { mutableStateOf(initialPrimary) }
    var zone by remember { mutableStateOf(initialPrimary?.let { zoneSpec(it).zone }) }
    var periodDays by remember { mutableIntStateOf(14) }
    var pairs by remember { mutableIntStateOf(4) }
    var washout by remember { mutableIntStateOf(3) }
    var blinded by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<List<MdePreviewRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // The preview's day-to-day noise does not depend on the planned lengths; the power line is computed from it here.
    LaunchedEffect(Unit) {
        try {
            preview = repo.previewMde(periodDays, periodDays)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Could not compute what this design can detect"
        } finally {
            loading = false
        }
    }

    val first = LocalDate.now().plusDays(1)
    val last = first.plusDays((pairs * 2 * periodDays - 1).toLong())
    val problems = randomisedProblems(primary, zone, periodDays, washout)
    val chosenPreview = preview.firstOrNull { it.metric == primary }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = FT.SheetShape, containerColor = FT.Surface, contentColor = FT.TextPrimary) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("RANDOMISED EXPERIMENT", style = FTType.SectionTitle, color = FT.DomainLog)
            Text(subjectName, style = FTType.RowTitle, color = FT.TextPrimary)
            Text(intro, style = FTType.BodySmall, color = FT.TextSecondary)

            Label("PRIMARY OUTCOME (ONE)")
            TextChipRow(EXPERIMENT_METRICS.map { it.second }, EXPERIMENT_METRICS.firstOrNull { it.first == primary }?.second, perRow = 2) { picked ->
                primary = EXPERIMENT_METRICS.firstOrNull { it.second == picked }?.first
                zone = primary?.let { zoneSpec(it).zone }
            }
            primary?.let { metric ->
                val spec = zoneSpec(metric)
                Label("SMALLEST CHANGE THAT WOULD MATTER")
                TextChipRow(spec.choices.map { zoneText(it, spec.unit) }, zone?.let { zoneText(it, spec.unit) }, perRow = 4) { picked ->
                    zone = spec.choices.firstOrNull { zoneText(it, spec.unit) == picked }
                }
                Text("Inside this zone the answer is NO MEANINGFUL EFFECT; beyond it, CLEAR BENEFIT or CLEAR HARM.", style = FTType.Caption, color = FT.TextMuted)
            }

            Label("DAYS PER PERIOD")
            TextChipRow(PERIOD_DAY_CHOICES.map { it.toString() }, periodDays.toString(), perRow = 3) { it?.toIntOrNull()?.let { d -> periodDays = d } }
            Label("PAIRS OF PERIODS")
            TextChipRow(PAIR_CHOICES.map { it.toString() }, pairs.toString(), perRow = 3) { it?.toIntOrNull()?.let { p -> pairs = p } }
            Label("WASHOUT DAYS AT THE START OF EVERY PERIOD")
            TextChipRow(WASHOUT_CHOICES.map { it.toString() }, washout.toString(), perRow = 3) { it?.toIntOrNull()?.let { d -> washout = d } }
            Text("${pairs * 2} periods, ${pairs * 2 * periodDays} days: $first to $last.", style = FTType.Caption, color = FT.TextMuted)

            Label("WHAT COULD THIS DESIGN SEE?")
            when {
                loading -> CircularProgressIndicator(color = FT.DomainLog, modifier = Modifier.size(16.dp))
                primary == null -> Text("Choose the outcome first.", style = FTType.BodySmall, color = FT.TextSecondary)
                chosenPreview == null -> Text(error ?: "Not enough recent data on this outcome to estimate it yet.", style = FTType.BodySmall, color = FT.TextSecondary)
                else -> {
                    Text(pairsPowerLine(chosenPreview, periodDays, washout, pairs, zone ?: zoneSpec(chosenPreview.metric).zone), style = FTType.BodySmall, color = FT.TextPrimary)
                    mdeCaveat(chosenPreview)?.let { Text(it, style = FTType.Caption, color = FT.TextMuted) }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Label("BLINDED (A PARTNER FILLS AN ORGANISER)")
                Switch(
                    checked = blinded,
                    onCheckedChange = { blinded = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = FT.DomainLog,
                        checkedTrackColor = FT.DomainLog.copy(alpha = 0.3f),
                        uncheckedThumbColor = FT.TextSecondary,
                        uncheckedTrackColor = FT.GlassFill,
                    ),
                )
            }
            Text(BLINDING_HELP, style = FTType.Caption, color = FT.TextMuted)

            problems.forEach { Text(it, style = FTType.BodySmall, color = FT.TextSecondary) }
            error?.takeIf { preview.isNotEmpty() }?.let { Text(it, style = FTType.BodySmall, color = FT.Critical) }

            FTButton(label = if (saving) "REGISTERING..." else "REGISTER AND DRAW THE ORDER", enabled = problems.isEmpty() && !saving && !loading, emphasis = FTButtonEmphasis.Primary) {
                saving = true
                scope.launch {
                    try {
                        val protocol = randomisedPairsProtocol(SecureRandom(), first, periodDays, pairs, washout, zone!!, blinded)
                        register("$subjectName ${LocalDate.now()}", primary!!, protocol, preview)
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
