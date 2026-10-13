package com.bioscan.fieldterminal.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.CaptureCoverageRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.StreamCoverage
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.CancellationException

// Analysis Layer 3 / R2 (2e): days logged per capture stream over the last 28 days.
@Composable
fun CaptureCoverageCard() {
    val repo = remember { CaptureCoverageRepository(SupabaseClientProvider.client) }
    var rows by remember { mutableStateOf<List<StreamCoverage>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            rows = repo.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Could not load coverage"
        }
    }

    FTCard(
        title = "DATA CAPTURE",
        info = "Days in the last 28 with at least one entry. Relationship and experiment analyses can only use the days you logged. " +
            "A day without a drink has no row, so alcohol shows days with a drink logged, not coverage.",
    ) {
        error?.let { Text(it, style = FTType.BodySmall, color = FT.Critical) }
        if (rows == null && error == null) Text("Loading…", style = FTType.BodySmall, color = FT.TextSecondary)
        rows?.forEach { row ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(row.label, style = FTType.Body, color = FT.TextPrimary)
                    Text(
                        if (row.informational) "${row.daysLogged} days" else "${row.daysLogged} / ${row.windowDays} days",
                        style = FTType.Telemetry,
                        color = FT.TextSecondary,
                    )
                }
                if (!row.informational) {
                    Box(modifier = Modifier.fillMaxWidth().height(4.dp).background(FT.GlassBorder)) {
                        Box(modifier = Modifier.fillMaxWidth(row.fraction.toFloat().coerceIn(0f, 1f)).height(4.dp).background(FT.Emerald))
                    }
                }
            }
        }
    }
}
