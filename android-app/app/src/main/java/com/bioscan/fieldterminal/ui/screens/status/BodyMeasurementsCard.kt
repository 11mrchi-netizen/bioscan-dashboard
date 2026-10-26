package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.BodyCheckinRecord
import com.bioscan.fieldterminal.domain.BodySide
import com.bioscan.fieldterminal.domain.BodySite
import com.bioscan.fieldterminal.domain.BodySlot
import com.bioscan.fieldterminal.domain.BODY_CHECKIN_SLOTS
import com.bioscan.fieldterminal.domain.ChangeVerdict
import com.bioscan.fieldterminal.domain.SiteChange
import com.bioscan.fieldterminal.domain.asymmetries
import com.bioscan.fieldterminal.domain.bodyCheckinDueOn
import com.bioscan.fieldterminal.domain.health.RegionShape
import com.bioscan.fieldterminal.domain.siteChanges
import com.bioscan.fieldterminal.domain.waistToHeight
import com.bioscan.fieldterminal.domain.waistToHip
import com.bioscan.fieldterminal.ui.components.BodyMap
import com.bioscan.fieldterminal.ui.components.BodyMark
import com.bioscan.fieldterminal.ui.components.DateTrendLine
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTMetricRow
import com.bioscan.fieldterminal.ui.components.MetricCardHeader
import com.bioscan.fieldterminal.ui.components.RedesignCard
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.format.DateTimeFormatter

// Redesign (spec §3.3): the monthly body check-in as an interactive body map. Every measured part is tinted by how it moved
// at the newest check-in (UP, DOWN, WITHIN ERROR: beyond the site's own measurement error, never beyond it); a tap opens
// that part's trend. Grip left this card for the Strength page; the waist is taken once, at the navel; body fat is the
// card beside it (BodyFatCard in BodyTabContent.kt).
private val DAY = DateTimeFormatter.ofPattern("d MMM")

private const val INFO =
    "Once a month, in the same conditions: tape girths and three skinfolds you can pinch yourself. Each site is " +
        "read two or three times and the median kept. A part is tinted UP or DOWN only when its change is bigger than that " +
        "site's measurement error, otherwise WITHIN ERROR. Tap a part to see its trend."

private const val INFO_DETAILS =
    "Sites: neck to calf, both sides of each limb; skinfolds on the right side (abdominal, suprailiac, front thigh). " +
        "Each site's typical error comes from the repeats, never taken as smaller than finding the landmark again on " +
        "another day allows (0.5 cm tape, 1 mm skinfold). UP or DOWN means beyond 2.77 typical errors, the smallest " +
        "real difference at 95%. Waist-to-height under 0.5 is the threshold the evidence supports (Ashwell 2012)."

/** The body-map region each girth is drawn on; skinfolds and the flexed arm have none (they feed body fat and the arm). */
private fun regionOf(site: BodySite): String? = when (site) {
    BodySite.NECK -> "neck"
    BodySite.SHOULDERS -> "shoulder"
    BodySite.CHEST -> "chest"
    BodySite.WAIST_NAVEL -> "abdomen"
    BodySite.HIPS -> "glute"
    BodySite.BICEPS_RELAXED -> "upper_arm"
    BodySite.FOREARM -> "forearm"
    BodySite.THIGH -> "quad"
    BodySite.CALF -> "calf"
    else -> null
}

private fun sideKey(side: BodySide): String? = when (side) {
    BodySide.RIGHT -> "right"
    BodySide.LEFT -> "left"
    BodySide.NONE -> null
}

private fun verdictColor(v: ChangeVerdict?): Color = when (v) {
    ChangeVerdict.UP -> FT.DomainFuel
    ChangeVerdict.DOWN -> FT.DomainTraining
    else -> FT.TextSecondary
}

@Composable
fun BodyMeasurementsCard(
    history: List<BodyCheckinRecord>,
    heightCm: Double?,
    onStart: () -> Unit,
) {
    var open by remember { mutableStateOf<List<BodySlot>?>(null) }
    val latest = history.maxByOrNull { it.occurredAt }
    val changes = remember(history) { siteChanges(history) }
    RedesignCard {
        MetricCardHeader(title = "BODY MEASUREMENTS", latest = null, helpBody = INFO, helpDetails = INFO_DETAILS)
        Text(
            latest?.let { "Last check-in ${it.date.format(DAY)} · next due ${bodyCheckinDueOn(it.date)!!.format(DAY)}" }
                ?: "No check-in yet. Tape and skinfolds, once a month before breakfast: about 15 minutes.",
            style = FTType.BodySmall,
            color = FT.TextSecondary,
        )
        FTButton("START BODY CHECK-IN", emphasis = FTButtonEmphasis.Primary, modifier = Modifier.fillMaxWidth(), onClick = onStart)
        if (latest == null) return@RedesignCard

        val marks = changes.mapNotNull { c ->
            regionOf(c.slot.site)?.let { BodyMark(it, sideKey(c.slot.side), verdictColor(c.verdict)) }
        }
        BodyMap(
            marks = marks,
            height = 340.dp,
            onTap = { shape: RegionShape ->
                val slots = BODY_CHECKIN_SLOTS.filter { regionOf(it.site) == shape.code && (it.side == BodySide.NONE || sideKey(it.side) == shape.side) }
                    .filter { slot -> history.any { it.value(slot.site, slot.side) != null } }
                if (slots.isNotEmpty()) open = slots
            },
        )
        Text("Tinted: ▲ UP · ▼ DOWN · ● WITHIN ERROR. Untinted parts are not measured. Tap a part for its trend.", style = FTType.Caption, color = FT.TextMuted)

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val whtr = waistToHeight(latest, heightCm)
            if (whtr != null) FTMetricRow("Waist-to-height", "%.2f · %s".format(whtr, if (whtr < 0.5) "under 0.5" else "0.5 or over"))
            else if (heightCm == null) Text("Waist-to-height needs your height: it is asked once at the next check-in.", style = FTType.Caption, color = FT.TextMuted)
            waistToHip(latest)?.let { FTMetricRow("Waist-to-hip", "%.2f".format(it)) }
            asymmetries(history).filter { it.beyondError }.forEach { a -> FTMetricRow("${a.site.label}, right vs left", "%+.1f cm".format(a.difference)) }
        }
    }
    open?.let { slots -> SiteTrendDialog(slots, history, changes) { open = null } }
}

@Composable
private fun SiteTrendDialog(slots: List<BodySlot>, history: List<BodyCheckinRecord>, changes: List<SiteChange>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FT.Surface,
        title = { Text(slots.first().site.label, style = FTType.SectionTitle, color = FT.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                slots.forEach { slot ->
                    val series = history.mapNotNull { c -> c.value(slot.site, slot.side)?.let { c.date to it } }.sortedBy { it.first }
                    val unit = slot.site.method.unit
                    val change = changes.firstOrNull { it.slot == slot }
                    Text(slot.label.uppercase(), style = FTType.LabelCaps, color = FT.TextSecondary)
                    series.lastOrNull()?.let { Text("%.1f $unit".format(it.second) + (change?.change?.let { c -> "   %+.1f · %s".format(c, verdictLabel(change.verdict)) } ?: ""), style = FTType.Value, color = FT.TextPrimary) }
                    if (series.size >= 2) DateTrendLine(points = series, color = FT.DomainFuel, unit = unit, valueFormat = { "%.1f".format(it) })
                    else Text("One check-in so far: the trend needs a second.", style = FTType.Caption, color = FT.TextMuted)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("CLOSE", color = FT.DomainTraining) } },
    )
}

private fun verdictLabel(v: ChangeVerdict?): String = when (v) {
    ChangeVerdict.UP -> "UP"
    ChangeVerdict.DOWN -> "DOWN"
    ChangeVerdict.WITHIN_ERROR -> "WITHIN ERROR"
    null -> "NOT JUDGED"
}
