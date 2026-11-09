package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.bioscan.fieldterminal.ui.components.InfoHelpButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.SupplementsRepository
import com.bioscan.fieldterminal.domain.ChangeReading
import com.bioscan.fieldterminal.domain.EFFECT_MIN_READINGS
import com.bioscan.fieldterminal.domain.EFFECT_SETTLE_DAYS
import com.bioscan.fieldterminal.domain.RegimenChange
import com.bioscan.fieldterminal.domain.changeReadingWords
import com.bioscan.fieldterminal.domain.changeReadings
import com.bioscan.fieldterminal.domain.nearbyChanges
import com.bioscan.fieldterminal.domain.shortItemName
import com.bioscan.fieldterminal.ui.screens.today.TodayStore
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// "Since you changed it" (domain/RegimenEffects.kt): this item's most recent start, restart or dose change that is old
// enough to compare, the metrics that moved clearly in the weeks after it, the other changes made around the same time,
// and the plain caveat. Uses the series the TODAY page keeps, so it costs no extra load; hidden when there is none.

private data class EffectView(val change: RegimenChange, val readings: List<ChangeReading>, val nearby: List<RegimenChange>)

@Composable
fun RegimenEffectsSection(itemId: Long) {
    val context = LocalContext.current
    var view by remember(itemId) { mutableStateOf<EffectView?>(null) }
    LaunchedEffect(itemId) {
        view = runCatching {
            val today = LocalDate.now()
            val series = (TodayStore.forDay(today)?.view ?: TodayStore.readDisk(context, today)?.view)?.series ?: return@runCatching null
            val all = SupplementsRepository(SupabaseClientProvider.client).loadRegimenChanges()
            val change = all.filter { it.itemId == itemId && ("started" in it.changes || "restarted" in it.changes || "dose" in it.changes) }
                .filter { ChronoUnit.DAYS.between(it.date, today) in (EFFECT_MIN_READINGS + EFFECT_SETTLE_DAYS).toLong()..180L }
                .maxByOrNull { it.date } ?: return@runCatching null
            EffectView(change, changeReadings(change.date, series, today), nearbyChanges(all, change.date, change.itemName))
        }.getOrNull()
    }
    val v = view ?: return
    val what = when {
        "dose" in v.change.changes && v.change.doseBefore != null -> "you changed the dose (${v.change.doseBefore} → ${v.change.doseAfter})"
        "restarted" in v.change.changes -> "you restarted it"
        else -> "you started it"
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The "not proof of cause" caveat moves behind the "?" so the card stays to what changed and what moved.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SINCE ${what.substringBefore(" (").uppercase()}", style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.weight(1f))
            InfoHelpButton(title = "SINCE THE CHANGE", body = EFFECT_CAUSE_INFO)
        }
        Text("${v.change.date.dayOfMonth} ${v.change.date.month.name.take(3).lowercase()}: $what. Three weeks before against the weeks after.",
            style = FTType.Caption, color = FT.TextMuted)
        if (v.readings.isEmpty()) {
            Text("Nothing moved beyond its usual day-to-day spread.", style = FTType.BodySmall, color = FT.TextSecondary)
        } else {
            v.readings.forEach { r ->
                Text(changeReadingWords(r), style = FTType.BodySmall,
                    color = when (r.better) { true -> FT.Emerald; false -> FT.Warning; null -> FT.TextPrimary })
            }
        }
        if (v.nearby.isNotEmpty()) {
            Text("Also changed within two weeks: " + v.nearby.map { shortItemName(it.itemName) }.distinct().joinToString(", ") + ".",
                style = FTType.Caption, color = FT.Warning)
        }
    }
}

private const val EFFECT_CAUSE_INFO =
    "This is what happened alongside the change, not proof it caused it: sleep, training, illness and the season move " +
        "these too. TEST IT on the supplements list runs a proper experiment."
