package com.bioscan.fieldterminal.ui.screens.user

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.BodyCheckinRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.BodyCheckinRecord
import com.bioscan.fieldterminal.ui.components.FTMetricRow
import com.bioscan.fieldterminal.ui.components.TrendCard
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Redesign (spec §3.3): grip strength moved here from the Body measurements. It is still taken during the monthly body
// check-in; the strongest hand is the trend (the one Healthspan scores), each hand's latest sits under it.
@Composable
fun GripStrengthCard() {
    var history by remember { mutableStateOf<List<BodyCheckinRecord>>(emptyList()) }
    LaunchedEffect(Unit) { history = runCatching { BodyCheckinRepository(SupabaseClientProvider.client).loadHistory() }.getOrElse { emptyList() } }
    val points = history.mapNotNull { c -> c.grip.values.maxOrNull()?.let { c.date to it } }
    if (points.isEmpty()) return
    val latest = history.filter { it.grip.isNotEmpty() }.maxByOrNull { it.occurredAt }
    TrendCard(
        "GRIP STRENGTH", points, "kg", color = FT.Category.Activity.c500,
        info = "The stronger hand at each monthly body check-in, in kg.",
        footer = {
            latest?.grip?.get("right")?.let { FTMetricRow("Right", "%.1f kg".format(it)) }
            latest?.grip?.get("left")?.let { FTMetricRow("Left", "%.1f kg".format(it)) }
        },
    )
}
