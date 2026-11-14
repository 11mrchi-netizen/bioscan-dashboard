package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.SupplementsRepository
import com.bioscan.fieldterminal.domain.LoggedDose
import com.bioscan.fieldterminal.domain.WallClock
import com.bioscan.fieldterminal.domain.activeIngredient
import com.bioscan.fieldterminal.domain.amountCurve
import com.bioscan.fieldterminal.domain.curveWindowDays
import com.bioscan.fieldterminal.domain.formatAmount
import com.bioscan.fieldterminal.domain.halfLifeWords
import com.bioscan.fieldterminal.domain.spec
import com.bioscan.fieldterminal.ui.components.LoadErrorLine
import com.bioscan.fieldterminal.ui.components.rememberLoadGuard
import com.bioscan.fieldterminal.ui.theme.FTType
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// The estimated amount in the body from the logged doses and the label's half-life (domain/AmountInBody.kt), over about
// five half-lives, with the amount now and the range over the last week. Labelled as an outline, never as a blood level.

@Composable
fun AmountInBodySection(supplementId: Long, halfLifeHours: Double, ingredientName: String?) {
    var curve by remember(supplementId, halfLifeHours) { mutableStateOf<List<Pair<Instant, Double>>?>(null) }
    var unit by remember { mutableStateOf<String?>(null) }
    val days = curveWindowDays(halfLifeHours)
    val guard = rememberLoadGuard(refreshOnUpdates = false)
    LaunchedEffect(supplementId, halfLifeHours, ingredientName, guard.attempt) {
        guard.run { curve = run {
            // The active amount per dose: an injection's dose is its active amount; anything else reads it from the dose's
            // ingredients (the one the reference names, else the only or first one).
            val repo = SupplementsRepository(SupabaseClientProvider.client)
            val overview = repo.loadOverview()
            val row = (overview.active + overview.ended).firstOrNull { it.id == supplementId } ?: return@run emptyList()
            val spec = row.spec()
            val active = if (spec.form == "injection") spec.amount?.let { a -> spec.unit?.let { u -> a to u } }
            else activeIngredient(repo.loadDoseIngredients(listOf(row))[row.id].orEmpty(), ingredientName, row.name)?.let { it.amount to it.unit }
            val (perDose, activeUnit) = active ?: return@run emptyList()
            unit = activeUnit
            val now = Instant.now()
            val from = now.minus(Duration.ofDays(days))
            // Doses up to ten half-lives before the window still count at its start.
            val since = from.minus(Duration.ofMinutes((halfLifeHours * 600).toLong()))
            val doses = repo.loadDoses(supplementId, since).mapNotNull { r ->
                // taken_at is a logged wall-clock time (domain/WallClock.kt): its real instant is the digits in this zone.
                val at = WallClock.instant(r.takenAt, ZoneId.systemDefault()) ?: return@mapNotNull null
                // How many doses this log was: from the servings it used, else from its count in the dose's unit.
                val doseCount = spec.amount
                val qty = r.servingCount?.let { it / row.servingsPerDose }
                    ?: r.doseValue?.takeIf { doseCount != null && doseCount > 0 && r.doseUnit.equals(spec.unit, ignoreCase = true) }?.let { it / doseCount!! }
                    ?: 1.0
                LoggedDose(at, perDose * qty)
            }
            if (doses.isEmpty()) emptyList() else amountCurve(doses, halfLifeHours, from, now)
        } }
    }
    LoadErrorLine(guard, "the amount in the body")
    val points = curve ?: return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("IN YOUR BODY · ESTIMATE", style = FTType.Micro, color = FT.TextMuted)
        if (points.isEmpty() || points.all { it.second <= 0.0 }) {
            Text("No logged doses in the last $days days to estimate from.", style = FTType.Caption, color = FT.TextMuted)
            return@Column
        }
        val u = unit.orEmpty()
        val now = points.last().second
        val weekAgo = Instant.now().minus(Duration.ofDays(7))
        val week = points.filter { !it.first.isBefore(weekAgo) }.map { it.second }
        Text("About ${formatAmount(round1(now))} $u now".trim(), style = FTType.Telemetry, color = FT.TextPrimary)
        if (week.isNotEmpty()) {
            Text("Last 7 days between ${formatAmount(round1(week.min()))} and ${formatAmount(round1(week.max()))} $u".trim(),
                style = FTType.Caption, color = FT.TextSecondary)
        }
        val hi = points.maxOf { it.second }.coerceAtLeast(1e-9)
        val t0 = points.first().first.epochSecond.toDouble()
        val t1 = points.last().first.epochSecond.toDouble().coerceAtLeast(t0 + 1)
        val line = FT.DomainLog
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            fun x(t: Instant) = ((t.epochSecond - t0) / (t1 - t0) * size.width).toFloat()
            fun y(v: Double) = (size.height * (1 - v / hi)).toFloat().coerceIn(2f, size.height - 2f)
            val p = Path()
            points.forEachIndexed { i, (t, v) -> if (i == 0) p.moveTo(x(t), y(v)) else p.lineTo(x(t), y(v)) }
            drawPath(p, line, style = Stroke(width = 2.5f))
            drawCircle(FT.EmeraldHighlight, radius = 5f, center = Offset(x(points.last().first), y(now)))
        }
        Row(Modifier.fillMaxWidth()) {
            Text("$days days ago", style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f))
            Text("now", style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
        Text("From your logged doses and a half-life of ${halfLifeWords(halfLifeHours)}: each dose halves every half-life and " +
            "the doses add up. It ignores absorption and slow-release injections, so it is an outline of how doses stack and " +
            "wash out, not a blood level.", style = FTType.Caption, color = FT.TextMuted)
    }
}

private fun round1(v: Double) = Math.round(v * 10) / 10.0
