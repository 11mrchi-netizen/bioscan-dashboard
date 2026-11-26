package com.bioscan.fieldterminal.ui.screens.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.SubstanceDay
import com.bioscan.fieldterminal.domain.SubstanceCurves
import com.bioscan.fieldterminal.domain.SubstanceCurves.Dose
import com.bioscan.fieldterminal.domain.SubstanceCurves.Point
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import androidx.compose.material3.Text
import kotlinx.coroutines.delay
import java.time.LocalTime
import kotlin.math.roundToInt

// TODAY's IN THE BODY card: caffeine, alcohol and the fast supplements through the day, estimated from what was logged and
// when (domain/SubstanceCurves.kt holds the model and its assumptions). Drinks not logged cannot be counted. Shown only when
// something was logged today or is left over from the evening before.

@Composable
fun SubstancesCard(data: SubstanceDay?, modifier: Modifier = Modifier) {
    if (data == null) return
    val sc = SubstanceCurves
    var nowMin by remember { mutableStateOf(LocalTime.now().let { it.hour * 60.0 + it.minute }) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); nowMin = LocalTime.now().let { it.hour * 60.0 + it.minute } } }

    val carry = sc.caffeineAt(data.caffeine, 0.0)
    val carryAlc = sc.alcoholSeries(data.alcohol, 0.0, 0.0).firstOrNull()?.v ?: 0.0
    val todayCaf = data.caffeine.filter { it.atMin >= 0 }
    val todayAlc = data.alcohol.filter { it.atMin >= 0 }
    val compounds = sc.COMPOUNDS.filter { c -> data.supplements[c.key].orEmpty().any { it.atMin >= 0 } }
    val meds = data.medications.filter { m ->
        m.doses.any { it.atMin >= 0 } || sc.oneCompartmentAt(m.doses, 0.0, m.halfLifeH, sc.MEDICATION_KA_PER_H) >= 0.05
    }
    if (todayCaf.isEmpty() && todayAlc.isEmpty() && carry < 1 && carryAlc < 0.1 && compounds.isEmpty() && meds.isEmpty()) return

    val bed = data.bedtimeMin ?: (24 * 60.0)
    val end = maxOf(24 * 60.0, bed + 60)
    Column(
        modifier = modifier.fillMaxWidth()
            .background(FT.Glass, RoundedCornerShape(FT.RadiusModule))
            .border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusModule))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("IN THE BODY", style = FTType.LabelCaps, color = FT.TextMuted)
        if (todayCaf.isNotEmpty() || carry >= 1) {
            val series = sc.caffeineSeries(data.caffeine, 0.0, end)
            val atBed = sc.caffeineAt(data.caffeine, bed)
            val settle = sc.caffeineBelowAt(data.caffeine, sc.CAFFEINE_BEDTIME_MG, nowMin)
            Header("CAFFEINE", FT.Metric.Caffeine, if (todayCaf.isNotEmpty()) "${todayCaf.sumOf { it.amount }.roundToInt()} mg logged" else "carried over")
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("${sc.caffeineAt(data.caffeine, nowMin).roundToInt()} mg now", style = FTType.Value, color = FT.TextPrimary)
                Text(
                    "${atBed.roundToInt()} mg at ${if (data.bedtimeMin != null) "bedtime ${sc.clockLabel(bed)}" else "midnight"}",
                    style = FTType.Value, color = if (atBed > sc.CAFFEINE_BEDTIME_MG) FT.Warn else FT.TextPrimary,
                )
            }
            if (settle != null && atBed > sc.CAFFEINE_BEDTIME_MG) {
                Text("Under ${sc.CAFFEINE_BEDTIME_MG.roundToInt()} mg by ${sc.clockLabel(settle)}", style = FTType.Caption, color = FT.TextSecondary)
            }
            Curve(series, FT.Metric.Caffeine, sc.niceMax(maxOf(series.maxOf { it.v }, sc.CAFFEINE_BEDTIME_MG * 2)), nowMin, data.bedtimeMin, sc.CAFFEINE_BEDTIME_MG, "Caffeine in the body, mg, through the day")
        }
        if (todayAlc.isNotEmpty() || carryAlc >= 0.1) {
            val series = sc.alcoholSeries(data.alcohol, 0.0, end)
            Header("ALCOHOL", FT.Metric.Alcohol, if (todayAlc.isNotEmpty()) "${todayAlc.sumOf { it.amount }.roundToInt()} g logged" else "carried over")
            Curve(series, FT.Metric.Alcohol, sc.niceMax(maxOf(series.maxOfOrNull { it.v } ?: 0.0, 10.0)), nowMin, data.bedtimeMin, null, "Alcohol in the body, g, through the day")
        }
        for (c in compounds) {
            val doses = data.supplements[c.key].orEmpty()
            val series = sc.compoundSeries(c, doses, 0.0, end)
            val fmt = { v: Double -> if (c.unit == "g") "%.1f".format(v) else v.roundToInt().toString() }
            Header(c.label, FT.Metric.Supplement, doses.filter { it.atMin >= 0 }.joinToString(" · ") { "${fmt(it.amount)} ${c.unit} at ${sc.clockLabel(it.atMin)}" })
            Text("${fmt(sc.oneCompartmentAt(doses, nowMin, c.halfLifeH, c.kaPerH))} ${c.unit} now", style = FTType.Value, color = FT.TextPrimary)
            Curve(series, FT.Metric.Supplement, sc.niceMax(series.maxOf { it.v }), nowMin, data.bedtimeMin, null, "${c.label.lowercase()} in the body, ${c.unit}, through the day")
        }
        for (m in meds) {
            val series = sc.series(0.0, end) { sc.oneCompartmentAt(m.doses, it, m.halfLifeH, sc.MEDICATION_KA_PER_H) }
            val fmt = { v: Double -> if (v < 10) "%.1f".format(v) else v.roundToInt().toString() }
            val taken = m.doses.filter { it.atMin >= 0 }
            Header(m.name.uppercase(), FT.Metric.Supplement, if (taken.isEmpty()) "carried over" else taken.joinToString(" · ") { "${fmt(it.amount)} mg at ${sc.clockLabel(it.atMin)}" })
            Text("${fmt(sc.oneCompartmentAt(m.doses, nowMin, m.halfLifeH, sc.MEDICATION_KA_PER_H))} mg now", style = FTType.Value, color = FT.TextPrimary)
            Curve(series, FT.Metric.Supplement, sc.niceMax(series.maxOf { it.v }), nowMin, data.bedtimeMin, null, "${m.name} in the body, mg, through the day")
        }
        Text(
            "Estimated from logged drinks and supplements; unlogged ones are missing. Caffeine: half-life ${sc.CAFFEINE_HALF_LIFE_H.roundToInt()} h " +
                "(people range 3 to 7 h), peak about 50 min after a dose; the dashed line is ${sc.CAFFEINE_BEDTIME_MG.roundToInt()} mg, a working line for sleep. " +
                (if (meds.isEmpty()) "" else "Medication: the label's half-life (${meds.joinToString(", ") { "${it.name} ${it.halfLifeH} h" }}), peak at about 2 h, earlier days' doses carried over. ") +
                "Alcohol: cleared at about 7 g an hour. Dashed curve: the projection from what is logged so far. " +
                "Only melatonin, glycine, theanine and tyrosine have a curve; magnesium, zinc, vitamin D, omega-3 and creatine do not move within a day.",
            style = FTType.Caption, color = FT.TextMuted,
        )
    }
}

@Composable
private fun Header(label: String, color: Color, note: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = FTType.LabelCaps, color = color)
        Text(note, style = FTType.Micro, color = FT.TextMuted)
    }
}

/** One filled curve over the day's clock: solid up to now, dashed after; a dashed bedtime line and an optional level line. */
@Composable
private fun Curve(points: List<Point>, color: Color, yMax: Double, nowMin: Double, bedMin: Double?, line: Double?, description: String) {
    Canvas(Modifier.fillMaxWidth().height(96.dp).semantics { contentDescription = description }) {
        if (points.size < 2) return@Canvas
        val t1 = points.last().t
        fun x(t: Double) = (t / t1 * size.width).toFloat()
        fun y(v: Double) = (size.height * (1 - minOf(v, yMax) / yMax)).toFloat()
        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
        drawLine(FT.GlassBorder, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        line?.let { drawLine(FT.TextMuted, Offset(0f, y(it)), Offset(size.width, y(it)), strokeWidth = 1.dp.toPx(), pathEffect = dash) }
        val past = points.filter { it.t <= nowMin }
        val ahead = points.filter { it.t >= nowMin }
        if (past.size > 1) {
            val area = Path().apply {
                moveTo(x(past.first().t), y(0.0))
                past.forEach { lineTo(x(it.t), y(it.v)) }
                lineTo(x(past.last().t), y(0.0)); close()
            }
            drawPath(area, color.copy(alpha = 0.22f), style = Fill)
            val stroke = Path().apply { past.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.t), y(p.v)) else lineTo(x(p.t), y(p.v)) } }
            drawPath(stroke, color, style = Stroke(width = 2.dp.toPx()))
        }
        if (ahead.size > 1) {
            val stroke = Path().apply { ahead.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.t), y(p.v)) else lineTo(x(p.t), y(p.v)) } }
            drawPath(stroke, color.copy(alpha = 0.7f), style = Stroke(width = 2.dp.toPx(), pathEffect = dash))
        }
        bedMin?.takeIf { it <= t1 }?.let { drawLine(FT.TextMuted, Offset(x(it), 0f), Offset(x(it), size.height), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))) }
        if (nowMin in 0.0..t1) drawLine(FT.TextPrimary, Offset(x(nowMin), 0f), Offset(x(nowMin), size.height), strokeWidth = 1.5.dp.toPx())
    }
}
