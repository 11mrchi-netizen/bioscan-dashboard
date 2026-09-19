package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter
import com.bioscan.fieldterminal.ui.theme.RobotoMono

// design/FIELD_TERMINAL_IA_CONTRACT.md + FUTURISTIC_MATERIAL_DESIGN_CONTRACT.md
// (DAV-94): title/context chrome shared across all top-level and tile screens,
// restyled onto the Futuristic Material tokens -- near-black field, Inter for
// the screen title (contract: "navigation, screen titles" -> Inter), Roboto
// Mono for the context line (contract explicitly lists "timestamps, statuses"
// under telemetry, which is exactly what context lines like "TODAY · THU 17
// SEP" or "3 ENTRIES" are). No border/pill -- flat near-black field reads as
// lightweight telemetry per the issue's own "not a legacy industrial panel"
// instruction.
//
// DAV-94: the sync pill that used to sit here is gone -- `syncState` was
// never passed a non-default value at any call site, so it always rendered a
// hardcoded "SYNCED" regardless of real connection state. Real sync status
// already lives in Setup's HEALTH CONNECT card
// (HealthConnectSyncStatus.lastResult/lastSyncedAt), which is the one place
// that reflects it accurately.
@Composable
fun ScreenHeader(title: String, context: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(FT.Base)
            .padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 14.dp),
    ) {
        Text(text = title, style = headerTitleStyle, color = FT.TextPrimary)
        Text(text = context, style = headerContextStyle, color = FT.TextSecondary, modifier = Modifier.padding(top = 4.dp))
    }
}

private val headerTitleStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
private val headerContextStyle = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.08.em)
