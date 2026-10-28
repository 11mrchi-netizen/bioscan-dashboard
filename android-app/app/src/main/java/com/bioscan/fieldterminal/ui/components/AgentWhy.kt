package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AgentReply
import com.bioscan.fieldterminal.data.AgentRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.functionErrorMessage
import com.bioscan.fieldterminal.domain.WhyContext
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

// Personal health agent, phase 1 (docs/personal-health-agent): WHY? in a card's title bar opens a sheet with the
// assistant's explanation of that card. The reply was checked on the server against the data it read (numbers, words,
// claim strength); the sheet says it is model-written and lets the person rate it, which feeds the evaluation.
@Composable
fun WhyButton(why: WhyContext, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .height(20.dp)
            .clip(shape)
            .background(FT.GlassFill)
            .border(FT.BorderWidth, FT.GlassBorder, shape)
            .ftClickable(shape = shape, role = Role.Button, onClickLabel = "Ask the assistant why") { open = true }
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("WHY?", style = FTType.Label, color = FT.TextSecondary)
    }
    if (open) WhySheet(why) { open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhySheet(why: WhyContext, onDismiss: () -> Unit) {
    val repo = remember { AgentRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    // One id per opening of the sheet: TRY AGAIN after a failure reuses it, so a reply that did arrive is not asked twice.
    val turnId = remember { UUID.randomUUID().toString() }
    var attempt by remember { mutableIntStateOf(0) }
    var reply by remember { mutableStateOf<AgentReply?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var rated by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(attempt) {
        error = null
        try {
            reply = repo.explain(why, turnId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.functionErrorMessage()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = FT.SheetShape,
        containerColor = FT.Surface,
        contentColor = FT.TextPrimary,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("WHY? · ${why.card}", style = FTType.LabelCaps, color = FT.TextMuted)
            val r = reply
            when {
                r != null -> {
                    Text(r.shown, style = FTType.Body, color = FT.TextPrimary)
                    Text(
                        "MODEL WRITTEN · checked against your data before it was shown. It explains the card; it is not advice or a diagnosis.",
                        style = FTType.Caption,
                        color = FT.TextMuted,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (rated == null) "USEFUL?" else "THANKS", style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.padding(end = 4.dp))
                        (1..5).forEach { n ->
                            RatingDot(n, selected = rated == n) {
                                rated = n
                                scope.launch { runCatching { repo.rate(r.turnId, n) } }
                            }
                        }
                    }
                }
                error != null -> {
                    Text(error!!, style = FTType.BodySmall, color = FT.TextSecondary)
                    FTButton("TRY AGAIN", modifier = Modifier.fillMaxWidth()) { attempt++ }
                }
                else -> Text("Reading the data behind this card…", style = FTType.BodySmall, color = FT.TextSecondary)
            }
        }
    }
}

@Composable
private fun RatingDot(n: Int, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(shape)
            .background(if (selected) FT.GlassStrongFill else FT.GlassFill)
            .border(FT.BorderWidth, if (selected) FT.TextSecondary else FT.GlassBorder, shape)
            .ftClickable(shape = shape, role = Role.Button, onClickLabel = "Rate $n of 5", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("$n", style = FTType.Label, color = if (selected) FT.TextPrimary else FT.TextSecondary)
    }
}
