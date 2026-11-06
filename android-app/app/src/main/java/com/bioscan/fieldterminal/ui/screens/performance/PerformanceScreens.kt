package com.bioscan.fieldterminal.ui.screens.performance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.Sport
import com.bioscan.fieldterminal.domain.SportKind
import com.bioscan.fieldterminal.domain.SportSession
import com.bioscan.fieldterminal.domain.SportSummary
import com.bioscan.fieldterminal.domain.sportOf
import com.bioscan.fieldterminal.domain.summarizeSports
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.screens.detail.DetailScreen
import com.bioscan.fieldterminal.ui.screens.detail.EmptyLine
import com.bioscan.fieldterminal.ui.screens.detail.Fmt
import com.bioscan.fieldterminal.ui.screens.detail.LocalRangeDays
import com.bioscan.fieldterminal.ui.screens.detail.MetricRow
import com.bioscan.fieldterminal.ui.screens.detail.Section
import com.bioscan.fieldterminal.ui.screens.detail.StackedBars
import com.bioscan.fieldterminal.ui.screens.detail.StatTiles
import com.bioscan.fieldterminal.ui.screens.detail.shortDate
import com.bioscan.fieldterminal.ui.components.drawBar
import com.bioscan.fieldterminal.ui.components.drawBarStub
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

private val SPORT_COLORS = listOf(FT.Category.Activity.c500, FT.Emerald, FT.Info, FT.Analysis, FT.Category.Arousal.c300, FT.Warning, FT.TextSecondary)

fun sportColor(index: Int): Color = SPORT_COLORS[index % SPORT_COLORS.size]

private fun hours(h: Double): String = if (h < 1) "${(h * 60).roundToInt()} min" else "%.1f h".format(h)

@Composable
private fun rememberSportSessions(): List<SportSession>? {
    val version = com.bioscan.fieldterminal.ui.components.rememberCacheVersion()
    val state by produceState<List<SportSession>?>(null, version) {
        value = runCatching { AnalysisRepository(SupabaseClientProvider.client).loadSportSessions() }.getOrNull() ?: value ?: emptyList()
    }
    return state
}

/**
 * ME › PERFORMANCE: one tile per sport (the three main ones first, then any other activity you record, newest first),
 * each with its last four weeks and twelve weeks of hours; a stacked chart of all of them on top.
 */
@Composable
fun PerformanceHub(onOpenSport: (Sport) -> Unit) {
    val sessions = rememberSportSessions()
    if (sessions == null) {
        EmptyLine("Loading your sports…")
        return
    }
    val today = LocalDate.now()
    val sports = remember(sessions) { summarizeSports(sessions, today) }
    if (sports.isEmpty()) {
        EmptyLine("No sessions recorded yet. Workouts from your watch and logged sessions show up here, one tile per sport.")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("ALL SPORTS · 12 WEEKS", info = "Hours per week by sport. Tap a week to read it. Walking counts only walks your watch recorded as workouts.") {
            val shown = sports.take(SPORT_COLORS.size)
            val thisMonday = today.minusDays((today.dayOfWeek.value - 1).toLong())
            StackedBars(
                (0 until 12).map { i -> shortDate(thisMonday.minusWeeks((11 - i).toLong())) to shown.map { it.weeklyHours[i] } },
                shown.indices.map(::sportColor), shown.map { it.sport.label }, Fmt.one, "h",
            )
        }
        sports.forEachIndexed { i, s -> SportTile(s, sportColor(i)) { onOpenSport(s.sport) } }
    }
}

@Composable
private fun SportTile(summary: SportSummary, color: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(FT.RadiusModule)
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(FT.Glass, shape)
            .border(FT.BorderWidth, FT.GlassBorder, shape)
            .ftClickable(shape = shape, role = Role.Button, onClickLabel = "Open ${summary.sport.label}") { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(summary.sport.label, style = FTType.RowTitle, color = FT.TextPrimary)
            val four = if (summary.sessions4w == 0) "nothing in the last 4 weeks"
            else listOfNotNull("${summary.sessions4w} in 4 weeks", hours(summary.hours4w), summary.km4w?.let { "%.0f km".format(it) }).joinToString(" · ")
            Text(four, style = FTType.Caption, color = FT.TextSecondary)
            Text("last ${shortDate(summary.lastDate)} · ${summary.totalSessions} in all", style = FTType.Micro, color = FT.TextMuted)
        }
        Canvas(Modifier.width(84.dp).height(28.dp)) {
            val top = summary.weeklyHours.maxOrNull()?.takeIf { it > 0 } ?: 1.0
            val layout = com.bioscan.fieldterminal.ui.components.BarLayout(size.width, summary.weeklyHours.size, 3.dp.toPx())
            summary.weeklyHours.forEachIndexed { i, h ->
                val bh = (h / top).toFloat() * (size.height - 2.dp.toPx())
                if (h > 0) drawBar(color, layout.left(i), size.height - bh, layout.barWidth, cap = i == summary.weeklyHours.lastIndex)
                else drawBarStub(layout.left(i), layout.barWidth)
            }
        }
        Text("›", style = FTType.SectionTitle, color = FT.TextMuted)
    }
}

/** A sport's sessions, newest first, as rows that open the session's own page. */
@Composable
fun RecentSessions(sportKey: String, onOpenSession: (Long) -> Unit, count: Int = 12) {
    val sessions = rememberSportSessions() ?: return
    val mine = remember(sessions, sportKey) { sessions.filter { sportOf(it.type, it.source).key == sportKey }.sortedByDescending { it.date }.take(count) }
    if (mine.isEmpty()) return
    Section("LATEST SESSIONS") {
        for (s in mine) {
            Row(
                Modifier.fillMaxWidth().ftClickable(role = Role.Button, onClickLabel = "Open session") { onOpenSession(s.id) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(shortDate(s.date), style = FTType.Telemetry, color = FT.TextSecondary, modifier = Modifier.width(64.dp))
                Text(
                    listOfNotNull(s.durationMin?.let { "${it.roundToInt()} min" }, s.distanceKm?.takeIf { it > 0 }?.let { "%.1f km".format(it) }, s.avgHr?.let { "${it.roundToInt()} bpm" })
                        .joinToString(" · "),
                    style = FTType.Body, color = FT.TextPrimary, modifier = Modifier.weight(1f),
                )
                Text("›", style = FTType.RowTitle, color = FT.TextMuted)
            }
        }
    }
}

/** The page of a sport without its own (cycling, hiking, walking, yoga...): volume, effort, records and sessions. */
@Composable
fun SportScreen(sportKey: String, onBack: () -> Unit, onOpenSession: (Long) -> Unit) {
    val sessions = rememberSportSessions()
    val label = sportOf(sportKey, null).label
    DetailScreen(label.uppercase(), onBack, eyebrow = "PERFORMANCE", domain = FT.Domain.Load) {
        if (sessions == null) {
            EmptyLine("Loading…")
            return@DetailScreen
        }
        val today = LocalDate.now()
        val days = LocalRangeDays.current
        val mine = remember(sessions, sportKey) { sessions.filter { sportOf(it.type, it.source).key == sportKey } }
        if (mine.isEmpty()) {
            EmptyLine("No sessions recorded.")
            return@DetailScreen
        }
        val inRange = mine.filter { ChronoUnit.DAYS.between(it.date, today) in 0 until days }
        Section("LAST $days DAYS") {
            val km = inRange.mapNotNull { it.distanceKm }.filter { it > 0 }
            StatTiles(
                listOfNotNull(
                    Triple("SESSIONS", "${inRange.size}", null),
                    Triple("TIME", hours(inRange.sumOf { it.durationMin ?: 0.0 } / 60), null),
                    km.takeIf { it.isNotEmpty() }?.let { Triple("DISTANCE", "%.0f km".format(it.sum()), null) },
                    inRange.mapNotNull { it.avgHr }.takeIf { it.isNotEmpty() }?.let { Triple("AVERAGE HR", "${it.average().roundToInt()} bpm", null) },
                ),
            )
        }
        Section("WEEKS", info = "Hours per Monday-to-Sunday week. Tap a week to read it.") {
            val weeks = (days / 7).coerceIn(4, 52)
            val thisMonday = today.minusDays((today.dayOfWeek.value - 1).toLong())
            StackedBars(
                (weeks - 1 downTo 0).map { k ->
                    val start = thisMonday.minusWeeks(k.toLong())
                    shortDate(start) to listOf(mine.filter { !it.date.isBefore(start) && it.date.isBefore(start.plusWeeks(1)) }.sumOf { it.durationMin ?: 0.0 } / 60.0)
                },
                listOf(FT.Category.Activity.c500), listOf("Hours"), Fmt.one, "h",
            )
        }
        Section("EACH SESSION") {
            val byDay = mine.groupBy { it.date }
            MetricRow("Duration", byDay.mapValues { (_, l) -> l.sumOf { it.durationMin ?: 0.0 } }.filterValues { it > 0 }, "min", Fmt.int, minSd = 5.0, color = FT.Metric.Duration,
                info = "Minutes of this sport a day (sessions on the same day added).")
            val dist = byDay.mapValues { (_, l) -> l.mapNotNull { it.distanceKm }.sum() }.filterValues { it > 0 }
            if (dist.isNotEmpty()) MetricRow("Distance", dist, "km", Fmt.one, minSd = 0.5, color = FT.Metric.Distance, info = "Kilometres a day.")
            val hr = byDay.mapValues { (_, l) -> l.mapNotNull { it.avgHr }.takeIf { it.isNotEmpty() }?.average() }.filterValues { it != null }.mapValues { it.value!! }
            if (hr.isNotEmpty()) MetricRow("Average HR", hr, "bpm", Fmt.int, minSd = 3.0, color = FT.Metric.HeartRate, info = "The session's average heart rate. For the same effort, a lower one over months usually means fitter.")
        }
        Section("RECORDS") {
            StatTiles(
                listOfNotNull(
                    mine.maxByOrNull { it.durationMin ?: 0.0 }?.let { s -> Triple("LONGEST", "${(s.durationMin ?: 0.0).roundToInt()} min", shortDate(s.date)) },
                    mine.filter { (it.distanceKm ?: 0.0) > 0 }.maxByOrNull { it.distanceKm!! }?.let { s -> Triple("FURTHEST", "%.1f km".format(s.distanceKm), shortDate(s.date)) },
                    mine.filter { (it.elevationGainM ?: 0.0) > 0 }.maxByOrNull { it.elevationGainM!! }?.let { s -> Triple("MOST CLIMB", "${s.elevationGainM!!.roundToInt()} m", shortDate(s.date)) },
                ),
            )
        }
        RecentSessions(sportKey, onOpenSession)
    }
}

/** Where a sport tile leads. */
fun sportRoute(sport: Sport): String = when (sport.kind) {
    SportKind.RUNNING -> "sport_running"
    SportKind.STRENGTH -> "sport_strength"
    SportKind.CONDITIONING -> "skill_conditioning"
    SportKind.OTHER -> "sport/${sport.key}"
}
