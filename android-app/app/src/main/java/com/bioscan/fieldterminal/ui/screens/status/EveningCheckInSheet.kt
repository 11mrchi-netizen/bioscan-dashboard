package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.EveningCheckinRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.EveningCheckin
import com.bioscan.fieldterminal.domain.withNothingElse
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.screens.TextChipRow
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// Analysis review phase B: the evening check-in (reminder at 22:25; also opened from Today's LAST EVENING card the next
// morning). One tap per exposure the relationship engine asks about. NOTHING ELSE turns every unanswered exposure into an
// explicit none, which is what lets a quiet evening count as a real zero (domain/EveningCheckin.kt).
private val DRINK_OPTIONS = listOf("0", "1", "2", "3", "4+")
private val YES_NO = listOf("NO", "YES")
private val STRESS_OPTIONS = listOf("1", "2", "3", "4", "5")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EveningCheckInSheet(date: LocalDate, onDismiss: () -> Unit, onSaved: (EveningCheckin) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val repo = remember { EveningCheckinRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var draft by remember(date) { mutableStateOf(EveningCheckin(date)) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Answers already saved for this evening (the reminder, then the morning card) are shown, not asked again.
    LaunchedEffect(date) {
        runCatching { repo.load(date) }.getOrNull()?.let { draft = it }
    }

    fun save(checkin: EveningCheckin) {
        saving = true
        error = null
        scope.launchSafe(onError = { e -> error = describeLoadFailure(e) }, onDone = { saving = false }) {
            repo.save(checkin)
            onSaved(checkin)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = FT.SheetShape, containerColor = FT.Surface, contentColor = FT.TextPrimary) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("EVENING CHECK-IN", style = FTType.SectionTitle, color = FT.DomainLog)
            Text(date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)), style = FTType.RowTitle, color = FT.TextPrimary)
            Text(
                "One tap each. Mark what happened, then NOTHING ELSE marks the rest as none, so a quiet evening counts as one.",
                style = FTType.BodySmall,
                color = FT.TextSecondary,
            )

            CheckInLabel("DRINKS WITH ALCOHOL")
            TextChipRow(DRINK_OPTIONS, draft.drinks?.let { if (it >= 4) "4+" else it.toString() }, perRow = 5) { picked ->
                draft = draft.copy(drinks = picked?.let { if (it == "4+") 4 else it.toInt() })
            }
            CheckInLabel("CAFFEINE AFTER 14:00")
            TextChipRow(YES_NO, draft.caffeineAfter14?.let { if (it) "YES" else "NO" }, perRow = 2) { picked ->
                draft = draft.copy(caffeineAfter14 = picked?.let { it == "YES" })
            }
            CheckInLabel("ATE AFTER 21:00")
            TextChipRow(YES_NO, draft.lateMeal?.let { if (it) "YES" else "NO" }, perRow = 2) { picked ->
                draft = draft.copy(lateMeal = picked?.let { it == "YES" })
            }
            CheckInLabel("STRESS TODAY, 1 TO 5 (OPTIONAL)")
            TextChipRow(STRESS_OPTIONS, draft.stress?.toString(), perRow = 5) { picked ->
                draft = draft.copy(stress = picked?.toInt())
            }

            error?.let { Text("Couldn't save: $it", style = FTType.BodySmall, color = FT.Critical) }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FTButton(
                    label = "NOTHING ELSE",
                    emphasis = FTButtonEmphasis.Secondary,
                    enabled = !saving,
                    modifier = Modifier.weight(1f),
                ) { save(draft.withNothingElse()) }
                FTButton(
                    label = if (saving) "SAVING..." else "SAVE",
                    emphasis = FTButtonEmphasis.Primary,
                    enabled = !saving,
                    modifier = Modifier.weight(1f),
                ) { save(draft) }
            }
        }
    }
}

@Composable
private fun CheckInLabel(text: String) {
    Text(text, style = FTType.LabelCaps, color = FT.TextSecondary)
}
