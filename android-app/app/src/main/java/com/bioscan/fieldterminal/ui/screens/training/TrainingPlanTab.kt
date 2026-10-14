package com.bioscan.fieldterminal.ui.screens.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
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
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TrainingProgramRepository
import com.bioscan.fieldterminal.data.model.GeneratedBlockRow
import com.bioscan.fieldterminal.data.model.UpcomingSessionRow
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTConfirmDestructive
import com.bioscan.fieldterminal.ui.components.LoadErrorCard
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.components.parallel
import com.bioscan.fieldterminal.ui.components.rememberLoadGuard
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// DAV-345. The PLAN tab: start a block, see the generated blocks and what is coming up.
// UX review F8: a failed load shows COULDN'T LOAD + RETRY instead of reading as "nothing planned", and DELETE asks first
// (it removes the block and every planned session in it) and says when it failed.
@Composable
fun TrainingPlanTab(onOpenPlanner: () -> Unit) {
    val repo = remember { TrainingProgramRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var blocks by remember { mutableStateOf<List<GeneratedBlockRow>?>(null) }
    var upcoming by remember { mutableStateOf<List<UpcomingSessionRow>>(emptyList()) }
    var confirmingDelete by remember { mutableStateOf<Long?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val guard = rememberLoadGuard()
    LaunchedEffect(guard.attempt) {
        guard.run {
            parallel(
                { blocks = repo.loadGeneratedBlocks() },
                { upcoming = repo.loadUpcoming(LocalDate.now()) },
            )
        }
    }
    Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FTButton("PLAN A NEW BLOCK", onClick = onOpenPlanner, emphasis = FTButtonEmphasis.Primary)
        if (guard.error != null) {
            LoadErrorCard(guard)
            return@Column
        }
        FTCard(title = "UPCOMING") {
            if (upcoming.isEmpty()) Text("No planned sessions yet.", color = FT.TextSecondary, style = FTType.BodySmall)
            upcoming.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Text(
                        LocalDate.parse(s.scheduledDate).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)).uppercase(),
                        color = FT.DomainTraining, style = FTType.MonoCaption, modifier = Modifier.padding(top = 2.dp),
                    )
                    Text(s.title, color = FT.TextPrimary, style = FTType.BodySmall)
                }
            }
        }
        FTCard(title = "PLANNED BLOCKS") {
            val b = blocks.orEmpty()
            if (blocks != null && b.isEmpty()) Text("No generated blocks yet.", color = FT.TextSecondary, style = FTType.BodySmall)
            deleteError?.let { Text("Couldn't delete: $it", color = FT.Critical, style = FTType.BodySmall) }
            b.forEach { blk ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(blk.name, color = FT.TextPrimary, style = FTType.BodySmall)
                            Text("${blk.startDate} to ${blk.endDate} · ${blk.status.uppercase()}", color = FT.TextSecondary, style = FTType.MonoCaption)
                        }
                        if (confirmingDelete != blk.id) {
                            FTButton("DELETE", emphasis = FTButtonEmphasis.Destructive) { confirmingDelete = blk.id; deleteError = null }
                        }
                    }
                    if (confirmingDelete == blk.id) {
                        FTConfirmDestructive(
                            message = "Delete this block and all its planned sessions? This can't be undone.",
                            confirmLabel = "DELETE BLOCK",
                            busy = deleting,
                            onCancel = { confirmingDelete = null },
                            onConfirm = {
                                deleting = true
                                scope.launchSafe(
                                    onError = { e -> deleteError = describeLoadFailure(e) },
                                    onDone = { deleting = false; confirmingDelete = null },
                                ) {
                                    repo.deleteBlock(blk.id)
                                    guard.retry()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
