package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.BodyCheckinRecord
import com.bioscan.fieldterminal.domain.BodySite
import com.bioscan.fieldterminal.domain.ChangeVerdict
import com.bioscan.fieldterminal.domain.SKINFOLD_SUM_SITES
import com.bioscan.fieldterminal.domain.asymmetries
import com.bioscan.fieldterminal.domain.bodyCheckinDueOn
import com.bioscan.fieldterminal.domain.jacksonPollockBodyFat
import com.bioscan.fieldterminal.domain.navyBodyFat
import com.bioscan.fieldterminal.domain.ratioWaist
import com.bioscan.fieldterminal.domain.siteChanges
import com.bioscan.fieldterminal.domain.skinfoldSum
import com.bioscan.fieldterminal.domain.skinfoldSumChange
import com.bioscan.fieldterminal.domain.waistToHeight
import com.bioscan.fieldterminal.domain.waistToHip
import com.bioscan.fieldterminal.ui.components.DateTrendLine
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTMetricRow
import com.bioscan.fieldterminal.ui.components.TrendSeries
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.format.DateTimeFormatter

// The monthly body check-in's results (domain/BodyCheckin.kt). Every change is shown with the smallest change this
// site can tell apart from measurement error, and called UP or DOWN only beyond it. The sum of skinfolds is the
// skinfold number to follow; body-fat percentages from the tape and the skinfolds are population equations, shown as
// labelled estimates next to the scale's, not as a better truth.
private val DAY = DateTimeFormatter.ofPattern("d MMM")

private const val INFO =
    "Once a month, in the same conditions: grip, tape girths and three skinfolds you can pinch yourself. Each site is " +
        "read two or three times and the median kept. A change shows as UP or DOWN only when it is bigger than that " +
        "site's measurement error, otherwise WITHIN ERROR. Body-fat percentages are rough estimates: follow one " +
        "method's trend, not the gap between methods."

private const val INFO_DETAILS =
    "Sites: neck to calf, both sides of each limb; skinfolds on the right side (abdominal, suprailiac, front thigh). " +
        "Each site's typical error comes from the repeats, never taken as smaller than finding the landmark again on " +
        "another day allows (0.5 cm tape, 1 mm skinfold). UP or DOWN means beyond 2.77 typical errors, the smallest " +
        "real difference at 95%. Waist-to-height under 0.5 is the threshold the evidence supports (Ashwell 2012). Body " +
        "fat from the tape: US Navy equation (Hodgdon and Beckett 1984); from skinfolds: Jackson and Pollock 1978, for " +
        "men who took the chest fold. Each is a few percentage points off for one person."

@Composable
fun BodyMeasurementsCard(
    history: List<BodyCheckinRecord>,
    heightCm: Double?,
    sex: String?,
    ageYears: Int?,
    scaleBodyFat: Double?,
    onStart: () -> Unit,
) {
    FTCard(title = "BODY MEASUREMENTS", info = INFO, infoDetails = INFO_DETAILS) {
        val latest = history.maxByOrNull { it.occurredAt }
        Text(
            latest?.let { "Last check-in ${it.date.format(DAY)} · next due ${bodyCheckinDueOn(it.date)!!.format(DAY)}" }
                ?: "No check-in yet. Grip, tape and skinfolds, once a month before breakfast: about 15 minutes.",
            style = FTType.BodySmall,
            color = FT.TextSecondary,
        )
        FTButton("START BODY CHECK-IN", emphasis = FTButtonEmphasis.Primary, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), onClick = onStart)
        if (latest == null) return@FTCard

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val whtr = waistToHeight(latest, heightCm)
            if (whtr != null) {
                FTMetricRow("Waist-to-height", "%.2f · %s".format(whtr, if (whtr < 0.5) "under 0.5" else "0.5 or over"))
            } else if (ratioWaist(latest) != null && heightCm == null) {
                Text("Waist-to-height needs your height: it is asked once at the next check-in.", style = FTType.Caption, color = FT.TextMuted)
            }
            waistToHip(latest)?.let { FTMetricRow("Waist-to-hip", "%.2f".format(it)) }

            skinfoldSumChange(history)?.let { sc ->
                FTMetricRow("Skinfold sum (3 sites)", "%.1f mm".format(sc.latest))
                Text(
                    when {
                        sc.caliperChanged -> "Taken with a different caliper than last time: not compared."
                        sc.change == null -> "First sum; the next check-in shows the change."
                        else -> "%+.1f mm since last · %s (real change beyond ±%.1f mm)".format(sc.change, verdictWord(sc.verdict), sc.smallestReal)
                    },
                    style = FTType.Caption,
                    color = FT.TextMuted,
                )
            }

            val navy = navyBodyFat(latest, heightCm, sex)
            val jp = jacksonPollockBodyFat(latest, ageYears, sex)
            if (navy != null || jp != null || scaleBodyFat != null) {
                Text("BODY FAT, ESTIMATES", style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.padding(top = 8.dp))
                scaleBodyFat?.let { FTMetricRow("Scale (impedance)", "%.1f%%".format(it)) }
                navy?.let { FTMetricRow("Tape (US Navy equation)", "%.1f%%".format(it)) }
                jp?.let { FTMetricRow("Skinfolds (Jackson–Pollock)", "%.1f%%".format(it)) }
                Text("Three methods, three estimates, each a few points off for one person: follow one method's trend.", style = FTType.Caption, color = FT.TextMuted)
            }
            if (sex == null && (latest.value(BodySite.NECK) != null)) {
                Text("Set your sex in Setup › Profile for the tape estimate of body fat.", style = FTType.Caption, color = FT.TextMuted)
            }

            val changes = siteChanges(history)
            if (changes.isNotEmpty()) {
                Text("GIRTHS AND FOLDS", style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.padding(top = 8.dp))
                changes.forEach { c ->
                    val unit = c.slot.site.method.unit
                    FTMetricRow(
                        c.slot.label,
                        "%.1f $unit".format(c.latest) + (c.change?.let { "  %+.1f · %s".format(it, verdictWord(c.verdict)) } ?: ""),
                    )
                }
                if (changes.any { it.change != null }) {
                    Text("A change is called UP or DOWN only beyond what the site's own measurement error explains.", style = FTType.Caption, color = FT.TextMuted)
                }
            }

            val uneven = asymmetries(history).filter { it.beyondError }
            if (uneven.isNotEmpty()) {
                Text("LEFT AND RIGHT", style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.padding(top = 8.dp))
                uneven.forEach { a -> FTMetricRow(a.site.label, "right %+.1f cm".format(a.difference)) }
                Text("Differences beyond measurement error; a dominant side is usually a little larger.", style = FTType.Caption, color = FT.TextMuted)
            }

            latest.grip.takeIf { it.isNotEmpty() }?.let { g ->
                FTMetricRow("Grip", listOfNotNull(g["right"]?.let { "right %.1f".format(it) }, g["left"]?.let { "left %.1f".format(it) }).joinToString(" · ") + " kg")
            }

            // Trends once there are two check-ins: the ratio waist and the skinfold sum, each on its own axis.
            val waistSeries = history.mapNotNull { c -> ratioWaist(c)?.let { c.date to it } }
            if (waistSeries.size >= 2) {
                Text("WAIST", style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.padding(top = 8.dp))
                DateTrendLine(series = listOf(TrendSeries(waistSeries, FT.DomainFuel, label = "Waist", highlightLast = FT.DomainFuel)), unit = "cm")
            }
            val sumSeries = history.mapNotNull { c -> skinfoldSum(c)?.let { c.date to it } }
            if (sumSeries.size >= 2) {
                Text("SKINFOLD SUM (${SKINFOLD_SUM_SITES.size} SITES)", style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.padding(top = 8.dp))
                DateTrendLine(series = listOf(TrendSeries(sumSeries, FT.DomainFuel, label = "Sum", highlightLast = FT.DomainFuel)), unit = "mm")
            }
            latest.caliper?.let { Text("Caliper: $it", style = FTType.Caption, color = FT.TextMuted) }
            val conditions = listOfNotNull(
                latest.fasted?.let { if (it) "fasted" else "not fasted" },
                latest.beforeTraining?.let { if (it) "before training" else "after training" },
            )
            if (conditions.isNotEmpty()) Text("Conditions: ${conditions.joinToString(", ")}", style = FTType.Caption, color = FT.TextMuted)
        }
    }
}

private fun verdictWord(v: ChangeVerdict?): String = when (v) {
    ChangeVerdict.UP -> "UP"
    ChangeVerdict.DOWN -> "DOWN"
    ChangeVerdict.WITHIN_ERROR -> "WITHIN ERROR"
    null -> "NOT JUDGED"
}
