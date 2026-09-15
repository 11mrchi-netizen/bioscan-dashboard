package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.HealthEventsOverview
import com.bioscan.fieldterminal.data.HealthEventsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.HealthEvent
import com.bioscan.fieldterminal.domain.HealthEventKind
import com.bioscan.fieldterminal.domain.daysSince
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles
import com.bioscan.fieldterminal.ui.theme.JetBrainsMono
import com.bioscan.fieldterminal.ui.theme.Saira
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

    LaunchedEffect(Unit) {
        overview = HealthEventsRepository(SupabaseClientProvider.client).loadOverview()
        isLoading = false
    }

    when {
        isLoading -> Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = FieldColors.Amber)
        }
        overview!!.open.isEmpty() && overview!!.resolved.isEmpty() -> Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
            Text("No injuries or illnesses logged yet.", style = FieldTextStyles.placeholderBody, color = FieldColors.InkMuted)
        }
        else -> HealthEventsContent(overview!!)
    }
}

@Composable
private fun HealthEventsContent(overview: HealthEventsOverview) {
    val today = LocalDate.now()
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = "${overview.open.size} OPEN · ${overview.resolved.size} RESOLVED",
            style = FieldTextStyles.headerContext,
            color = if (overview.open.isNotEmpty()) FieldColors.Alert else FieldColors.InkMuted,
        )

        if (overview.open.isNotEmpty()) {
            SectionLabel("OPEN", FieldColors.Alert)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                overview.open.forEach { OpenEventCard(it, today) }
            }
        }

        if (overview.resolved.isNotEmpty()) {
            SectionLabel("RESOLVED", FieldColors.InkMuted)
            Column(modifier = Modifier.fillMaxWidth().background(FieldColors.RaisedSurface)) {
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
        Text(text, style = FieldTextStyles.subTabLabel, color = color)
        Box(Modifier.weight(1f).height(1.dp).background(FieldColors.Hairline))
    }
}

@Composable
private fun KindBadge(kind: HealthEventKind) {
    val color = if (kind == HealthEventKind.Injury) FieldColors.Amber else FieldColors.Cyan
    Box(modifier = Modifier.background(color).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(
            kind.name.uppercase(),
            style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 10.5.sp),
            color = FieldColors.Ground,
        )
    }
}

@Composable
private fun OpenEventCard(event: HealthEvent, today: LocalDate) {
    val days = daysSince(event.startDate, today) + 1 // "day 1" on the day it was reported, matching the mockup's own inclusive counting
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, FieldColors.Alert.copy(alpha = 0.45f))
            .background(FieldColors.Alert.copy(alpha = 0.1f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                KindBadge(event.kind)
                Text(event.status.uppercase(), style = FieldTextStyles.tabBarLabel, color = FieldColors.Alert)
            }
            Text("DAY $days", style = FieldTextStyles.tabBarLabel, color = FieldColors.InkMuted)
        }
        Column(modifier = Modifier.padding(14.dp)) {
            Text(event.title, style = TextStyle(fontFamily = Saira, fontWeight = FontWeight.SemiBold, fontSize = 17.5.sp), color = FieldColors.Ink)
            event.detail?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = TextStyle(fontFamily = Saira, fontSize = 14.sp), color = FieldColors.InkMuted, modifier = Modifier.padding(top = 5.dp))
            }
            Text(
                "Reported ${event.startDate}",
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp),
                color = FieldColors.InkMuted,
                modifier = Modifier.padding(top = 10.dp),
            )
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
                    Text(event.title, style = TextStyle(fontFamily = Saira, fontWeight = FontWeight.SemiBold, fontSize = 16.sp), color = FieldColors.InkMuted)
                }
                Text(
                    "Cleared ${event.endDate ?: "—"}",
                    style = TextStyle(fontFamily = Saira, fontSize = 13.5.sp),
                    color = FieldColors.InkMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            event.endDate?.let { end ->
                Text("${daysSince(end, today)} D AGO", style = FieldTextStyles.tabBarLabel, color = FieldColors.InkMuted)
            }
        }
        if (showDivider) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(FieldColors.Hairline))
        }
    }
}
