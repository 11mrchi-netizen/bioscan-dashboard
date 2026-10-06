package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.HealthEventsOverview
import com.bioscan.fieldterminal.data.HealthEventsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.HealthEvent
import com.bioscan.fieldterminal.domain.HealthEventKind
import com.bioscan.fieldterminal.domain.daysSince
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter
import com.bioscan.fieldterminal.ui.theme.RobotoMono
import kotlinx.coroutines.launch
import java.time.LocalDate

// Step 10 (Phase C, last Status sub-tab). Real data from `injuries`/
// `illnesses`, merged for display only (genuinely different fields
// underneath) same as index.html's PANELS.history. Open = active/monitoring,
// shown first; resolved shown unfiltered below (matching both
// PANELS.history's "complete, unfiltered" framing and the committed
// mockup's closing line: "cleared events drop out of Status after 7 days
// and stay here"). Does NOT implement the mockup's "AUTO-CLEARS <date>
// UNLESS RE-FLAGGED" copy for open events -- not a real rule anywhere in
// this project (confirmed against the actual handoff spec); an open event
// stays open until manually resolved. See ROADMAP.md P8 Step 10.
@Composable
fun HealthEventsScreen() {
    var overview by remember { mutableStateOf<HealthEventsOverview?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var showAddSheet by remember { mutableStateOf(false) }
    var showAddIllnessSheet by remember { mutableStateOf(false) }

    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reloadKey) {
        isLoading = true
        error = null
        try {
            overview = HealthEventsRepository(SupabaseClientProvider.client).loadOverview()
        } catch (e: Exception) {
            error = e.message ?: "Unknown error"
        }
        isLoading = false
    }

    when {
        isLoading -> Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = FT.DomainHeart)
        }
        error != null -> Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
            Text("Failed to load: $error", style = TextStyle(fontFamily = Inter, fontSize = 14.sp), color = FT.Critical)
        }
        overview == null || (overview!!.open.isEmpty() && overview!!.resolved.isEmpty()) -> Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp)) {
            AddEventButtons(onAddInjuryClick = { showAddSheet = true }, onAddIllnessClick = { showAddIllnessSheet = true })
            Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                Text("No injuries or illnesses logged yet.", style = TextStyle(fontFamily = Inter, fontSize = 15.5.sp), color = FT.TextSecondary)
            }
        }
        else -> HealthEventsContent(overview!!, onAddInjuryClick = { showAddSheet = true }, onAddIllnessClick = { showAddIllnessSheet = true }, onResolved = { reloadKey++ })
    }

    if (showAddSheet) {
        InjuryFormSheet(
            onDismiss = { showAddSheet = false },
            onSaved = { showAddSheet = false; reloadKey++ },
        )
    }
    if (showAddIllnessSheet) {
        IllnessFormSheet(
            onDismiss = { showAddIllnessSheet = false },
            onSaved = { showAddIllnessSheet = false; reloadKey++ },
        )
    }
}

@Composable
private fun AddEventButtons(onAddInjuryClick: () -> Unit, onAddIllnessClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
        Text(
            "+ ADD ILLNESS",
            style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.08f.em),
            color = FT.Info,
            modifier = Modifier
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onAddIllnessClick() }
                .padding(vertical = 8.dp),
        )
        AmberButton(label = "+ ADD INJURY") { onAddInjuryClick() }
    }
}

@Composable
private fun HealthEventsContent(overview: HealthEventsOverview, onAddInjuryClick: () -> Unit, onAddIllnessClick: () -> Unit, onResolved: () -> Unit) {
    val today = LocalDate.now()
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        AddEventButtons(onAddInjuryClick = onAddInjuryClick, onAddIllnessClick = onAddIllnessClick)
        Text(
            text = "${overview.open.size} OPEN · ${overview.resolved.size} RESOLVED",
            style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.08f.em),
            color = if (overview.open.isNotEmpty()) FT.Critical else FT.TextSecondary,
        )

        if (overview.open.isNotEmpty()) {
            SectionLabel("OPEN", FT.Critical)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                overview.open.forEach { OpenEventCard(it, today, onResolved = onResolved) }
            }
        }

        if (overview.resolved.isNotEmpty()) {
            SectionLabel("RESOLVED", FT.TextSecondary)
            val shape = RoundedCornerShape(FT.RadiusCard)
            Column(modifier = Modifier.fillMaxWidth().clip(shape).background(FT.GlassFill, shape).alpha(0.7f)) {
                overview.resolved.forEachIndexed { i, event ->
                    ResolvedRow(event, today, showDivider = i < overview.resolved.lastIndex)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.14f.em), color = color)
        Box(Modifier.weight(1f).height(1.dp).background(FT.GlassBorder))
    }
}

@Composable
private fun KindBadge(kind: HealthEventKind) {
    val color = if (kind == HealthEventKind.Injury) FT.Warning else FT.Info
    Box(modifier = Modifier.background(color).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(
            kind.name.uppercase(),
            style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Bold, fontSize = 10.5.sp),
            color = FT.Base,
        )
    }
}

// DAV-101: injury states use explicit semantic (Critical/red) treatment,
// not a domain accent -- open vs. resolved is a state, not an identity.
@Composable
private fun OpenEventCard(event: HealthEvent, today: LocalDate, onResolved: () -> Unit) {
    val days = daysSince(event.startDate, today) + 1 // "day 1" on the day it was reported, matching the mockup's own inclusive counting
    val repo = remember { HealthEventsRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var resolving by remember { mutableStateOf(false) }
    var resolveError by remember { mutableStateOf<String?>(null) }
    // DAV-217: same rounded-corner glass-panel shape FTCard uses elsewhere,
    // applied directly rather than through FTCard itself -- this card's
    // custom badge/status/day-counter header doesn't fit FTCard's
    // single-title-string slot.
    val shape = RoundedCornerShape(FT.RadiusCard)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(FT.BorderWidth, FT.Critical.copy(alpha = 0.45f), shape)
            .background(FT.Critical.copy(alpha = 0.1f), shape),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                KindBadge(event.kind)
                Text(event.status.uppercase(), style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.14f.em), color = FT.Critical)
            }
            Text("DAY $days", style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.14f.em), color = FT.TextSecondary)
        }
        Column(modifier = Modifier.padding(14.dp)) {
            Text(event.title, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 17.5.sp), color = FT.TextPrimary)
            event.detail?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = TextStyle(fontFamily = Inter, fontSize = 14.sp), color = FT.TextSecondary, modifier = Modifier.padding(top = 5.dp))
            }
            if (event.kind == HealthEventKind.Illness) {
                Text(
                    if (event.doctorSeen) "Doctor seen" else "Doctor not seen",
                    style = TextStyle(fontFamily = Inter, fontSize = 13.sp),
                    color = if (event.doctorSeen) FT.Emerald else FT.TextSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                event.medications.forEach { med ->
                    Text(
                        "${med.name} · ${med.dose} · ${med.frequency}",
                        style = TextStyle(fontFamily = Inter, fontSize = 13.sp),
                        color = FT.TextSecondary,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            Text(
                "Reported ${event.startDate}",
                style = TextStyle(fontFamily = RobotoMono, fontSize = 12.sp),
                color = FT.TextSecondary,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                if (resolving) "RESOLVING..." else "RESOLVE",
                style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.14f.em),
                color = FT.Emerald,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        if (!resolving) {
                            resolving = true
                            resolveError = null
                            scope.launch {
                                try {
                                    if (event.kind == HealthEventKind.Injury) repo.resolveInjury(event.id)
                                    else repo.resolveIllness(event.id)
                                    onResolved()
                                } catch (e: Exception) {
                                    resolveError = e.message ?: "Couldn't resolve"
                                    resolving = false
                                }
                            }
                        }
                    },
            )
            resolveError?.let {
                Text(it, style = TextStyle(fontFamily = Inter, fontSize = 12.sp), color = FT.Critical, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun ResolvedRow(event: HealthEvent, today: LocalDate, showDivider: Boolean) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    KindBadge(event.kind)
                    Text(event.title, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp), color = FT.TextSecondary)
                }
                Text(
                    "Cleared ${event.endDate ?: "—"}",
                    style = TextStyle(fontFamily = Inter, fontSize = 13.5.sp),
                    color = FT.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            event.endDate?.let { end ->
                Text("${daysSince(end, today)} D AGO", style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.14f.em), color = FT.TextSecondary)
            }
        }
        if (showDivider) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(FT.GlassBorder))
        }
    }
}
