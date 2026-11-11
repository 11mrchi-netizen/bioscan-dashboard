package com.bioscan.fieldterminal.ui.screens.performance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.PersonalRecordsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.RecordHistory
import com.bioscan.fieldterminal.domain.RecordKey
import com.bioscan.fieldterminal.domain.RecordKind
import com.bioscan.fieldterminal.domain.RecordSet
import com.bioscan.fieldterminal.domain.formatRecordValue
import com.bioscan.fieldterminal.domain.gainWords
import com.bioscan.fieldterminal.domain.recordHistories
import com.bioscan.fieldterminal.domain.recordPace
import com.bioscan.fieldterminal.ui.components.DateTrendLine
import com.bioscan.fieldterminal.ui.components.StatTileSpec
import com.bioscan.fieldterminal.ui.components.TileGrid
import com.bioscan.fieldterminal.ui.components.TrendSeries
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.screens.detail.DetailScreen
import com.bioscan.fieldterminal.ui.screens.detail.EmptyLine
import com.bioscan.fieldterminal.ui.screens.detail.Section
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// ME › PERFORMANCE › RECORDS (docs/performance/records.md): the fastest 1K to 100K inside any run, the longest run, the
// longest trail run and the biggest climb. Each record opens its progress: a step line of the record over time, then
// every improvement with what it gained and how long it took. Every run is read once in the database; a new run is
// compared with what stood before.

private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy")

private fun ink(key: RecordKey) = when (key.kind) {
    RecordKind.TIME -> FT.Metric.Pace
    RecordKind.KM -> FT.Metric.Distance
    RecordKind.METRES -> FT.Metric.Elevation
}

@Composable
private fun rememberRecords(): List<RecordHistory>? {
    val version = com.bioscan.fieldterminal.ui.components.rememberCacheVersion()
    val state by produceState<List<RecordHistory>?>(null, version) {
        value = runCatching { recordHistories(PersonalRecordsRepository(SupabaseClientProvider.client).loadSets()) }.getOrNull() ?: value ?: emptyList()
    }
    return state
}

/** Performance hub card: the headline records, each a tile; the whole card opens the Records page. */
@Composable
fun RecordsCard(onOpen: () -> Unit) {
    val records = rememberRecords() ?: return
    val shown = listOf(RecordKey.K5, RecordKey.K10, RecordKey.HALF, RecordKey.LONGEST, RecordKey.LONGEST_TRAIL, RecordKey.CLIMB)
        .mapNotNull { k -> records.firstOrNull { it.key == k }?.current?.let { k to it } }
    if (shown.isEmpty()) return
    Section("RECORDS", trailing = "ALL ›") {
        Column(Modifier.fillMaxWidth().ftClickable(role = Role.Button, onClickLabel = "Open records") { onOpen() }) {
            TileGrid(shown.map { (k, s) -> StatTileSpec(k.label.uppercase(), formatRecordValue(k, s.value), secondary = s.date.format(DAY)) })
        }
    }
}

@Composable
fun RecordsScreen(onBack: () -> Unit, onOpenSession: (Long) -> Unit) {
    val records = rememberRecords()
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val today = remember { LocalDate.now() }
    DetailScreen("RECORDS", onBack, eyebrow = "PERFORMANCE", ranged = false, domain = FT.Domain.Load) {
        if (records == null) {
            EmptyLine("Loading your records…")
            return@DetailScreen
        }
        Section(
            "FASTEST",
            info = "The fastest stretch of each distance inside any run with a GPS track (elapsed time, so a stop inside it counts). " +
                "Every run is read once; each new one is compared with the best before it. Treadmill runs have no track.",
        ) {
            records.filter { it.key.kind == RecordKind.TIME }.forEach { r -> RecordRow(r, open == r.key.key, today, { open = if (open == r.key.key) null else r.key.key }, onOpenSession) }
        }
        Section(
            "DISTANCE AND CLIMB",
            info = "The longest run, the longest trail run, and the most climbing in one run (the watch's smoothed ascent where it has one).",
        ) {
            records.filter { it.key.kind != RecordKind.TIME }.forEach { r -> RecordRow(r, open == r.key.key, today, { open = if (open == r.key.key) null else r.key.key }, onOpenSession) }
        }
    }
}

@Composable
internal fun RecordRow(r: RecordHistory, expanded: Boolean, today: LocalDate, onToggle: () -> Unit, onOpenSession: (Long) -> Unit) {
    val cur = r.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().ftClickable(role = Role.Button, onClickLabel = "Open ${r.key.label}", enabled = cur != null) { onToggle() }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(r.key.label, style = FTType.RowTitle, color = FT.TextPrimary)
                Text(
                    cur?.let { c -> listOfNotNull(recordPace(r.key, c.value), c.date.format(DAY)).joinToString(" · ") } ?: "Not yet run",
                    style = FTType.Caption, color = FT.TextSecondary,
                )
            }
            Text(cur?.let { formatRecordValue(r.key, it.value) } ?: "—", style = FTType.MetricMedium, color = if (cur != null) ink(r.key) else FT.TextMuted)
            if (cur != null) Text(if (expanded) "⌄" else "›", style = FTType.SectionTitle, color = FT.TextSecondary)
        }
        if (expanded && cur != null) RecordProgress(r, today, onOpenSession)
    }
}

/** The record as a step line over time, then each improvement: what it gained and after how long. */
@Composable
private fun RecordProgress(r: RecordHistory, today: LocalDate, onOpenSession: (Long) -> Unit) {
    val first = r.first ?: return
    val cur = r.current ?: return
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val since = r.daysSinceLast(today) ?: 0
        val total = if (r.sets.size > 1) " · from ${formatRecordValue(r.key, first.value)} on ${first.date.format(DAY)}, ${gainWords(r.key, kotlin.math.abs(cur.value - first.value))} in all" else ""
        Text(
            "Set ${r.sets.size} time${if (r.sets.size == 1) "" else "s"}" + total + " · last moved ${if (since == 0L) "today" else "$since days ago"}",
            style = FTType.Caption, color = FT.TextSecondary,
        )
        if (r.sets.size >= 2) {
            DateTrendLine(
                series = listOf(
                    TrendSeries(stepPoints(r.sets, today), ink(r.key), label = r.key.label.uppercase(), smooth = false, straight = true),
                    // the runs that set it, as dots on the steps
                    TrendSeries(r.sets.map { it.date to it.value }, ink(r.key), dotsOnly = true),
                ),
                valueFormat = { formatRecordValue(r.key, it) },
                axisFormat = { formatRecordValue(r.key, it).removeSuffix(" km").removeSuffix(" m") },
                unit = when (r.key.kind) {
                    RecordKind.TIME -> "time · lower is faster"
                    RecordKind.KM -> "km"
                    RecordKind.METRES -> "m"
                },
                chartHeight = 120.dp,
            )
        }
        Text("EACH TIME IT MOVED", style = FTType.Micro, color = FT.TextMuted)
        r.sets.asReversed().forEach { s ->
            Row(
                Modifier.fillMaxWidth().ftClickable(role = Role.Button, onClickLabel = "Open the run", enabled = s.sessionId != null) { s.sessionId?.let(onOpenSession) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(s.date.format(DAY), style = FTType.Telemetry, color = FT.TextSecondary, modifier = Modifier.weight(1f))
                Text(improvementWords(r.key, s), style = FTType.Caption, color = FT.TextMuted)
                Text(formatRecordValue(r.key, s.value), style = FTType.Body, color = FT.TextPrimary)
                if (s.sessionId != null) Text("›", style = FTType.RowTitle, color = FT.TextMuted)
            }
        }
    }
}

private fun improvementWords(key: RecordKey, s: RecordSet): String {
    val p = s.previousValue ?: return "first"
    val gain = if (key.higherBetter) s.value - p else p - s.value
    val days = s.previousDate?.let { java.time.temporal.ChronoUnit.DAYS.between(it, s.date) }
    return gainWords(key, gain) + (days?.let { " after $it d" } ?: "")
}

/** A record holds until it is beaten: each set drawn as a step, the last held to [today]. */
internal fun stepPoints(sets: List<RecordSet>, today: LocalDate): List<Pair<LocalDate, Double>> {
    val out = mutableListOf<Pair<LocalDate, Double>>()
    sets.forEachIndexed { i, s ->
        if (i > 0) out += s.date.minusDays(1) to sets[i - 1].value
        out += s.date to s.value
    }
    if (sets.isNotEmpty() && sets.last().date.isBefore(today)) out += today to sets.last().value
    return out.distinctBy { it.first }
}
