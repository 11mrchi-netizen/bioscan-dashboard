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
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TrainingProgramRepository
import com.bioscan.fieldterminal.data.model.GeneratedBlockRow
import com.bioscan.fieldterminal.data.model.UpcomingSessionRow
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter
import com.bioscan.fieldterminal.ui.theme.RobotoMono
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// DAV-345. The PLAN tab: start a block, see the generated blocks and what is coming up.
@Composable
fun TrainingPlanTab(onOpenPlanner: () -> Unit) {
    val repo = remember { TrainingProgramRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var blocks by remember { mutableStateOf<List<GeneratedBlockRow>?>(null) }
    var upcoming by remember { mutableStateOf<List<UpcomingSessionRow>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) {
        blocks = runCatching { repo.loadGeneratedBlocks() }.getOrDefault(emptyList())
        upcoming = runCatching { repo.loadUpcoming(LocalDate.now()) }.getOrDefault(emptyList())
    }
    Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AmberButton("PLAN A NEW BLOCK", onClick = onOpenPlanner)
        FTCard(title = "UPCOMING") {
            if (upcoming.isEmpty()) Text("No planned sessions yet.", color = FT.TextSecondary, fontFamily = Inter, fontSize = 13.sp)
            upcoming.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Text(
                        LocalDate.parse(s.scheduledDate).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)).uppercase(),
                        color = FT.DomainTraining, fontFamily = RobotoMono, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp),
                    )
                    Text(s.title, color = FT.TextPrimary, fontFamily = Inter, fontSize = 13.5.sp)
                }
            }
        }
        FTCard(title = "PLANNED BLOCKS") {
            val b = blocks.orEmpty()
            if (b.isEmpty()) Text("No generated blocks yet.", color = FT.TextSecondary, fontFamily = Inter, fontSize = 13.sp)
            b.forEach { blk ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(blk.name, color = FT.TextPrimary, fontFamily = Inter, fontSize = 14.sp)
                        Text("${blk.startDate} to ${blk.endDate} · ${blk.status.uppercase()}", color = FT.TextSecondary, fontFamily = RobotoMono, fontSize = 11.sp)
                    }
                    AmberButton("DELETE") { scope.launch { runCatching { repo.deleteBlock(blk.id) }; reload++ } }
                }
            }
        }
    }
}
