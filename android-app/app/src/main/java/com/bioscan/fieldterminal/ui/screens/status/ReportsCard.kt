package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.ReportsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.catching
import com.bioscan.fieldterminal.domain.notifications.ReportBrief
import com.bioscan.fieldterminal.domain.notifications.reportBody
import com.bioscan.fieldterminal.domain.notifications.reportTitle
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Lab › N=1: the newest weekly and monthly report (docs/reports), so one is a tap away all week and not only from Monday's
// notification. Each opens its page in the app (ReportOverlay); [openKey] is the report a notification asked for.
@Composable
fun ReportsCard(openKey: String? = null, onOpenHandled: () -> Unit = {}) {
    var reports by remember { mutableStateOf<List<ReportBrief>?>(null) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { catching { reports = ReportsRepository(SupabaseClientProvider.client).latest() } }
    LaunchedEffect(openKey) {
        if (openKey != null) {
            open = openKey
            onOpenHandled()
        }
    }
    open?.let { key -> ReportOverlay(key, onClose = { open = null }) }
    val list = reports ?: return
    val shown = listOfNotNull(list.firstOrNull { it.grain == "week" }, list.firstOrNull { it.grain == "month" })
    FTCard(
        title = "REPORTS",
        info = "Written each Monday morning for the week before and on the 1st for the month before: key metrics against the period before, regimen adherence, what the Lab found, nutrients from food and supplements, and a few suggestions.",
        trailingChevron = shown.isNotEmpty(),
        onClick = shown.firstOrNull()?.let { first -> { open = first.key } },
    ) {
        if (shown.isEmpty()) {
            Text("The first weekly report is written on Monday morning, for the week before.", style = FTType.Caption, color = FT.TextSecondary)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                shown.forEach { r ->
                    Column(Modifier.fillMaxWidth().ftClickable(onClickLabel = "Open the report") { open = r.key }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(reportTitle(r).uppercase(), style = FTType.Label, color = FT.TextPrimary)
                        Text(reportBody(r), style = FTType.Caption, color = FT.TextSecondary)
                    }
                }
            }
        }
    }
}
