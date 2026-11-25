package com.bioscan.fieldterminal.ui.screens.status

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.ReportsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.catching
import com.bioscan.fieldterminal.domain.notifications.ReportBrief
import com.bioscan.fieldterminal.domain.notifications.reportBody
import com.bioscan.fieldterminal.domain.notifications.reportTitle
import com.bioscan.fieldterminal.domain.notifications.reportWebUrl
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Lab › N=1: the newest weekly and monthly report (docs/reports), so one is a tap away all week and not only from Monday's
// notification. The report is drawn on the web (Lab › Reports); the card opens it there. A failed read leaves the card out.
@Composable
fun ReportsCard() {
    var reports by remember { mutableStateOf<List<ReportBrief>?>(null) }
    LaunchedEffect(Unit) { catching { reports = ReportsRepository(SupabaseClientProvider.client).latest() } }
    val list = reports ?: return
    val context = LocalContext.current
    val open: (String?) -> Unit = { key ->
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(reportWebUrl(key))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    val shown = listOfNotNull(list.firstOrNull { it.grain == "week" }, list.firstOrNull { it.grain == "month" })
    FTCard(
        title = "REPORTS",
        info = "Written each Monday morning for the week before and on the 1st for the month before: key metrics against the period before, regimen adherence, what the Lab found, and a few suggestions. Opens on the web.",
        trailing = "WEB",
        trailingChevron = true,
        onClick = { open(null) },
    ) {
        if (shown.isEmpty()) {
            Text("The first weekly report is written on Monday morning, for the week before.", style = FTType.Caption, color = FT.TextSecondary)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                shown.forEach { r ->
                    Text(reportTitle(r).uppercase(), style = FTType.Label, color = FT.TextPrimary)
                    Text(reportBody(r), style = FTType.Caption, color = FT.TextSecondary)
                }
            }
        }
    }
}
