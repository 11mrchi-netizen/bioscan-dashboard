package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.ExperimentsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.DAILY_WEEK_CHOICES
import com.bioscan.fieldterminal.domain.DailyChoice
import com.bioscan.fieldterminal.domain.EXPERIMENT_METRICS
import com.bioscan.fieldterminal.domain.MdePreviewRow
import com.bioscan.fieldterminal.domain.dailyPowerLine
import com.bioscan.fieldterminal.domain.dailyProtocol
import com.bioscan.fieldterminal.domain.mdeCaveat
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

// Analysis review phase F. Turns a same-day question from the evidence board (caffeine after 14:00 and sleep, a late meal
// and next-morning HRV...) into a randomised test: each week the phone draws 3 or 4 days for the rule, the outcome is read
// the next morning, and the answer is judged against the zone chosen here, in four words. The rule, the outcome, the zone
// and the drawn schedule are fixed at registration.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyExperimentSheet(choice: DailyChoice, onDismiss: () -> Unit, onRegistered: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val repo = remember { ExperimentsRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    val spec = zoneSpec(choice.outcome)
    var zone by remember { mutableStateOf<Double?>(spec.zone) }
    var weeks by remember { mutableIntStateOf(8) }
    var preview by remember { mutableStateOf<List<MdePreviewRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            preview = repo.previewMde(28, 28)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Could not compute what this design can detect"
        } finally {
            loading = false
        }
    }

    val first = LocalDate.now().plusDays(1)
    val last = first.plusDays((weeks * 7 - 1).toLong())
    val outcomeLabel = EXPERIMENT_METRICS.firstOrNull { it.first == choice.outcome }?.second ?: choice.outcome
    val chosenPreview = preview.firstOrNull { it.metric == choice.outcome }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = FT.SheetShape, containerColor = FT.Surface, contentColor = FT.TextPrimary) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("TEST IT · DAYS IN RANDOM ORDER", style = FTType.SectionTitle, color = FT.DomainLog)
            Text(choice.title, style = FTType.RowTitle, color = FT.TextPrimary)
            Text(
                "Each week the app draws 3 or 4 days for the rule: ${choice.behaviour.label}. On the other days there is no rule; do as you usually would. " +
                    "$outcomeLabel is read the next morning. Days you don't follow the rule still count as drawn, which keeps the comparison fair. " +
                    "Day 1 is tomorrow; the rule, the outcome and the zone are locked once registered.",
                style = FTType.BodySmall,
                color = FT.TextSecondary,
            )

            SheetLabel("SMALLEST CHANGE THAT WOULD MATTER")
            TextChipRow(spec.choices.map { zoneText(it, spec.unit) }, zone?.let { zoneText(it, spec.unit) }, perRow = 4) { picked ->
                zone = spec.choices.firstOrNull { zoneText(it, spec.unit) == picked }
            }
            SheetLabel("WEEKS")
            TextChipRow(DAILY_WEEK_CHOICES.map { it.toString() }, weeks.toString(), perRow = 3) { it?.toIntOrNull()?.let { w -> weeks = w } }
            Text("$first to $last; you'll see each day's draw on the Today page.", style = FTType.Caption, color = FT.TextMuted)

            SheetLabel("WHAT COULD THIS DESIGN SEE?")
            when {
                loading -> CircularProgressIndicator(color = FT.DomainLog, modifier = Modifier.size(16.dp))
                chosenPreview == null -> Text(error ?: "Not enough recent data on this outcome to estimate it yet.", style = FTType.BodySmall, color = FT.TextSecondary)
                else -> {
                    Text(dailyPowerLine(chosenPreview, weeks, zone ?: spec.zone), style = FTType.BodySmall, color = FT.TextPrimary)
                    mdeCaveat(chosenPreview)?.let { Text(it, style = FTType.Caption, color = FT.TextMuted) }
                }
            }
            if (zone == null) Text("Choose the smallest change that would matter.", style = FTType.BodySmall, color = FT.TextSecondary)
            error?.takeIf { preview.isNotEmpty() }?.let { Text(it, style = FTType.BodySmall, color = FT.Critical) }

            FTButton(label = if (saving) "REGISTERING..." else "REGISTER AND DRAW THE DAYS", enabled = zone != null && !saving && !loading, emphasis = FTButtonEmphasis.Primary) {
                saving = true
                scope.launch {
                    try {
                        val protocol = dailyProtocol(SecureRandom(), first, weeks, zone!!, choice.lag, choice.behaviour)
                        repo.register(name = "${choice.title} ${LocalDate.now()}", primary = choice.outcome, secondary = emptyList(), protocol = protocol, preview = preview)
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
private fun SheetLabel(text: String) {
    Text(text, style = FTType.LabelCaps, color = FT.TextSecondary)
}
