package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.ScaleCompositionPoint
import com.bioscan.fieldterminal.domain.CompositionSplit
import com.bioscan.fieldterminal.domain.healthyWeightRangeKg
import com.bioscan.fieldterminal.domain.weightChangeSplit
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTMetricRow
import com.bioscan.fieldterminal.ui.components.FTSegment
import com.bioscan.fieldterminal.ui.components.FTSegmentBar
import com.bioscan.fieldterminal.ui.components.TrendCard
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate

// Health › BODY: what the scale's impedance says beyond weight (domain/BodyComposition.kt). Estimates, so every card says
// so, and the trend cards are the point: a single reading carries the scale's bias, a change over weeks mostly does not.

private const val ESTIMATE_NOTE =
    "Estimated from the scale's impedance with the Sun 2003 equations (fat-free mass, water) and Janssen 2000 (muscle). " +
        "Foot-to-foot scales read differently from the lab devices these were fitted on, so the level can be off by a few points: " +
        "follow the trend. Hydration, a recent meal or training and time of day all move impedance."

@Composable
fun BodyCompositionCards(series: List<ScaleCompositionPoint>, heightCm: Double?, athleteMode: Boolean) {
    if (series.isEmpty()) return
    val usable = series.filter { it.composition?.qualityOk == true && it.composition?.split != null }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        val latest = usable.maxByOrNull { it.at }
        if (latest == null) {
            FTCard(title = "BODY COMPOSITION", info = ESTIMATE_NOTE) {
                Text(
                    "Add height, date of birth and sex in Setup › Profile, then weigh in on the scale to see body fat, " +
                        "fat-free mass and water. Weigh-ins saved before that are used too.",
                    style = FTType.BodySmall, color = FT.TextSecondary,
                )
            }
            return@Column
        }
        val c = latest.composition!!
        val split = c.split!!
        FTCard(title = "BODY COMPOSITION", info = ESTIMATE_NOTE) {
            FTSegmentBar(
                listOf(
                    FTSegment("Fat-free %.1f kg".format(split.fatFreeKg), split.fatFreeKg, FT.Emerald),
                    FTSegment("Fat %.1f kg".format(split.fatKg), split.fatKg, FT.DomainFuel),
                ),
            )
            FTMetricRow("Body fat", "%.1f%%".format(split.bodyFatPct))
            c.skeletalMuscleKg?.let { FTMetricRow("Skeletal muscle", "~%.1f kg".format(it)) }
            c.totalBodyWaterL?.let { FTMetricRow("Body water", "%.1f L (%.0f%% of weight)".format(it, it / latest.weightKg * 100)) }
            c.bmi?.let { FTMetricRow("BMI", "%.1f".format(it)) }
            heightCm?.let { h -> healthyWeightRangeKg(h)?.let { FTMetricRow("BMI 18.5–25 range", "%.0f–%.0f kg".format(it.start, it.endInclusive)) } }
            FTMetricRow("Fat-free / fat index", "%.1f / %.1f kg/m²".format(split.ffmi, split.fmi))
            c.bmrKcal?.let { FTMetricRow("Resting energy (Katch-McArdle)", "%.0f kcal".format(it)) }
            Text("Latest weigh-in ${latest.at.toLocalDate()}", style = FTType.Caption, color = FT.TextMuted)
            if (athleteMode) {
                Text(
                    "Athlete mode is on: these equations were fitted on the general adult population and are less accurate in athletes.",
                    style = FTType.Caption, color = FT.TextSecondary,
                )
            }
        }

        fun daily(pick: (ScaleCompositionPoint) -> Double?): List<Pair<LocalDate, Double>> =
            series.groupBy { it.at.toLocalDate() }.mapNotNull { (d, v) -> v.maxByOrNull { it.at }?.let(pick)?.let { d to it } }.sortedBy { it.first }

        fun dailyUsable(pick: (CompositionSplit) -> Double): List<Pair<LocalDate, Double>> =
            daily { p -> p.composition?.takeIf { it.qualityOk }?.split?.let(pick) }

        TrendCard("BODY FAT (SCALE)", dailyUsable { it.bodyFatPct }, "%", color = FT.DomainFuel, info = ESTIMATE_NOTE)
        TrendCard("FAT-FREE MASS", dailyUsable { it.fatFreeKg }, "kg", color = FT.Emerald, info = ESTIMATE_NOTE)
        TrendCard(
            "IMPEDANCE INDEX", daily { p -> p.composition?.impedanceIndex }, "cm²/Ω", color = FT.DomainFuel,
            info = "Height² ÷ impedance. No population equation involved, so it is the cleanest trend: it rises with more " +
                "conductive (water-rich, lean) tissue and falls when you are dehydrated. Compare like with like (same time of day).",
        )
        TrendCard(
            "BODY WATER", daily { p -> p.composition?.let { c2 -> c2.totalBodyWaterL?.let { it / p.weightKg * 100 } } }, "%",
            color = FT.DomainFuel,
            info = "Total body water as a share of weight (Sun 2003). Mostly tracks hydration and leanness; a day-to-day swing is hydration, not tissue.",
        )

        // Fat vs lean behind the weight change; readings that failed the quality gate are left out.
        val readings = usable.map { Triple(it.at.toLocalDate(), it.weightKg, it.composition!!.split!!) }
        FTCard(
            title = "WEIGHT CHANGE: FAT OR LEAN",
            info = "Weight change split into fat and fat-free mass, comparing the first two and last two scale readings in each window. " +
                "A few tenths of a kilo is within the scale's noise.",
        ) {
            val today = LocalDate.now()
            val rows = listOf(30L, 90L).map { it to weightChangeSplit(readings, it, today) }
            if (rows.all { it.second == null }) {
                Text("Needs 4+ good weigh-ins spread across at least half of a 30 or 90 day window.", style = FTType.Caption, color = FT.TextMuted)
            }
            rows.forEach { (days, s) ->
                if (s != null) {
                    FTMetricRow("Last $days days", "%+.1f kg  (fat %+.1f, fat-free %+.1f)".format(s.deltaWeightKg, s.deltaFatKg, s.deltaFatFreeKg))
                }
            }
        }
    }
}
