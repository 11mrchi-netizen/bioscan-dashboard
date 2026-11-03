package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.BodyCheckinRepository
import com.bioscan.fieldterminal.data.ScaleCompositionPoint
import com.bioscan.fieldterminal.data.ScaleReadingRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.UserProfileRepository
import com.bioscan.fieldterminal.data.model.BodyMetricsAnalysisRow
import com.bioscan.fieldterminal.data.model.UserProfileRow
import com.bioscan.fieldterminal.domain.BodyCheckinRecord
import com.bioscan.fieldterminal.domain.BodyComposition
import com.bioscan.fieldterminal.domain.MusclePotential
import com.bioscan.fieldterminal.domain.PotentialMethod
import com.bioscan.fieldterminal.domain.latestFrame
import com.bioscan.fieldterminal.domain.evaluateWeightTrend
import com.bioscan.fieldterminal.domain.jacksonPollockBodyFat
import com.bioscan.fieldterminal.domain.musclePotential
import com.bioscan.fieldterminal.domain.navyBodyFat
import com.bioscan.fieldterminal.domain.rollingBodyFat
import com.bioscan.fieldterminal.domain.score.Series
import com.bioscan.fieldterminal.domain.trendStateWord
import com.bioscan.fieldterminal.domain.weightChangeSplit
import com.bioscan.fieldterminal.ui.components.rememberLoadGuard
import com.bioscan.fieldterminal.ui.screens.detail.ChartRange
import com.bioscan.fieldterminal.ui.screens.detail.Chip
import com.bioscan.fieldterminal.ui.screens.detail.EmptyLine
import com.bioscan.fieldterminal.ui.screens.detail.Fmt
import com.bioscan.fieldterminal.ui.screens.detail.Loading
import com.bioscan.fieldterminal.ui.screens.detail.LocalRangeDays
import com.bioscan.fieldterminal.ui.screens.detail.MetricPanel
import com.bioscan.fieldterminal.ui.screens.detail.MetricTiles
import com.bioscan.fieldterminal.ui.screens.detail.Section
import com.bioscan.fieldterminal.ui.screens.detail.StatTiles
import com.bioscan.fieldterminal.ui.screens.detail.TileSpec
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

// ME › BODY, in the detail pages' look (map request): weight trend, body fat trend, the monthly measurements on the body
// map, then what the scale's impedance gives beyond weight as tiles that open their trend, and muscle potential as a ring.
// Energy balance moved to FUEL › Nutrition and digestion lives on FUEL › Digestion. The check-in starts from LAB or the +
// button; a saved check-in invalidates the cache, so this tab reloads on its own.

private const val WEIGHT_INFO =
    "Every weigh-in (scale or Health Connect), the bold line its 7-day mean. The shaded band is your normal over the last " +
        "two months. The rate is the smoothed trend's change per week; a few tenths of a kilo is day-to-day water."

private const val BODY_FAT_INFO =
    "Scale (the weigh-in's impedance estimate, or Health Connect on days without one), tape (US Navy equation) and skinfold " +
        "(Jackson-Pollock) estimates, averaged over a rolling 28 days. Each method is a few points off for one person: follow " +
        "the trend, not the gap between methods."

private const val SCALE_INFO =
    "Estimated from the scale's impedance with the Sun 2003 equations (fat-free mass, water), Janssen 2000 (muscle) and " +
        "Katch-McArdle (resting energy). Foot-to-foot scales read differently from the lab devices these were fitted on, so the " +
        "level can be off by a few points: follow the trend. Hydration, a recent meal or training and time of day all move " +
        "impedance. Weigh-ins that fail the quality check are left out. Tap a tile for its trend."

private const val POTENTIAL_INFO =
    "Your fat-free mass against the most a drug-free man of your build usually reaches.\n\n" +
        "With your wrist and ankle girths (asked at the body check-in) the ceiling comes from your frame: Casey Butt's " +
        "formula (2009), fitted on drug-free bodybuilding champions from before steroids. Maximum lean mass (lb) = height^1.5 × " +
        "(√wrist ÷ 22.667 + √ankle ÷ 17.0104) × (body fat % ÷ 224 + 1), in inches. It is worked out at your current body fat, " +
        "held between 5 and 25 %. It is one author's formula, not peer-reviewed, and for men only.\n\n" +
        "Without them it uses a normalised fat-free mass index of 25 (Kouri 1995, 157 men: almost none of the drug-free " +
        "lifters were above it), the same ceiling for everyone your height. For women the app uses 22, its own adjustment: " +
        "the study was of men.\n\n" +
        "Either way it is a population ceiling, not a prediction: some people pass it, most never reach it. Fat-free mass " +
        "includes water, organs and bone, not only muscle, and comes from the scale's estimate (or your body-fat trend), so " +
        "read the ring as a direction, not a precise score."

@Composable
fun BodyTabContent() {
    var bodyMetrics by remember { mutableStateOf<List<BodyMetricsAnalysisRow>?>(null) }
    var checkins by remember { mutableStateOf<List<BodyCheckinRecord>>(emptyList()) }
    var heightCm by remember { mutableStateOf<Double?>(null) }
    var profile by remember { mutableStateOf<UserProfileRow?>(null) }
    var scaleSeries by remember { mutableStateOf<List<ScaleCompositionPoint>>(emptyList()) }
    val guard = rememberLoadGuard()

    // Body metrics carry the tab; a failed read of the rest leaves its section empty rather than failing the tab.
    LaunchedEffect(guard.attempt) {
        guard.run { bodyMetrics = AnalysisRepository(SupabaseClientProvider.client).loadBodyMetrics() }
        val repo = BodyCheckinRepository(SupabaseClientProvider.client)
        checkins = runCatching { repo.loadHistory() }.getOrElse { checkins }
        heightCm = runCatching { repo.latestHeightCm() }.getOrNull() ?: heightCm
        profile = runCatching { UserProfileRepository(SupabaseClientProvider.client).loadProfile() }.getOrNull() ?: profile
        scaleSeries = runCatching { ScaleReadingRepository(SupabaseClientProvider.client).loadCompositionSeries() }.getOrElse { scaleSeries }
    }

    var range by rememberSaveable { mutableStateOf(ChartRange.M3) }
    var showRegimen by rememberSaveable { mutableStateOf(true) }
    var markers by remember { mutableStateOf(emptyList<com.bioscan.fieldterminal.domain.RegimenMarker>()) }
    LaunchedEffect(Unit) { markers = runCatching { com.bioscan.fieldterminal.ui.screens.detail.loadRegimenMarkers() }.getOrDefault(emptyList()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (r in ChartRange.entries) Chip(r.label, r == range) { range = r }
            if (markers.isNotEmpty()) Chip("REGIMEN", showRegimen) { showRegimen = !showRegimen }
        }
        val b = bodyMetrics
        if (b == null) {
            Loading(guard)
            return@Column
        }
        CompositionLocalProvider(LocalRangeDays provides range.days,
            com.bioscan.fieldterminal.ui.screens.detail.LocalRegimenMarkers provides if (showRegimen) markers else emptyList()) {
            BodyPage(b, checkins, profile?.heightCm ?: heightCm, profile, scaleSeries)
        }
    }
}

@Composable
private fun BodyPage(
    b: List<BodyMetricsAnalysisRow>,
    checkins: List<BodyCheckinRecord>,
    heightCm: Double?,
    profile: UserProfileRow?,
    scaleSeries: List<ScaleCompositionPoint>,
) {
    val today = LocalDate.now()

    // 1. Weight trend.
    val weightPoints = remember(b) { b.mapNotNull { row -> row.weightKg?.let { LocalDate.parse(row.date) to it } }.sortedBy { it.first } }
    val weightEval = remember(weightPoints) { evaluateWeightTrend(weightPoints) }
    MetricPanel("WEIGHT", weightPoints.toMap(), "kg", Fmt.one, info = WEIGHT_INFO, minSd = 0.3, color = FT.DomainFuel, today = today) {
        val rate = weightEval.rateKgPerWeek
        Text(
            listOfNotNull(trendStateWord(weightEval.state), rate?.let { "%+.2f kg / week".format(it) }).joinToString(" · "),
            style = FTType.Caption, color = FT.TextSecondary,
        )
    }

    // 2. Body fat trend: scale, tape and skinfolds blended.
    val sex = profile?.sex?.takeIf { it == "male" || it == "female" }
    val age = profile?.dateOfBirth?.let { runCatching { ChronoUnit.YEARS.between(LocalDate.parse(it), today).toInt() }.getOrNull() }
    val scaleFat = remember(b) { b.mapNotNull { row -> row.bodyFatPct?.let { LocalDate.parse(row.date) to it } }.sortedBy { it.first } }
    val tape = remember(checkins, heightCm, sex) { checkins.mapNotNull { c -> navyBodyFat(c, heightCm, sex)?.let { c.date to it } }.sortedBy { it.first } }
    val folds = remember(checkins, age, sex) { checkins.mapNotNull { c -> jacksonPollockBodyFat(c, age, sex)?.let { c.date to it } }.sortedBy { it.first } }
    val blended = remember(scaleFat, tape, folds) { rollingBodyFat(scaleFat + tape + folds).toMap() }
    MetricPanel("BODY FAT", blended, "%", Fmt.one, info = BODY_FAT_INFO, minSd = 0.5, color = FT.DomainFuel, today = today) {
        val methods = listOfNotNull(
            scaleFat.lastOrNull()?.let { Triple("SCALE", "%.1f %%".format(it.second), shortDay(it.first)) },
            tape.lastOrNull()?.let { Triple("TAPE", "%.1f %%".format(it.second), shortDay(it.first)) },
            folds.lastOrNull()?.let { Triple("SKINFOLDS", "%.1f %%".format(it.second), shortDay(it.first)) },
        )
        if (methods.isNotEmpty()) StatTiles(methods)
    }

    // 3. Measurements on the body map.
    BodyMeasurementsCard(history = checkins, heightCm = heightCm)

    // 4. The rest of what the scale gives, as tiles.
    val lastPerDay = remember(scaleSeries) {
        scaleSeries.filter { it.composition?.qualityOk == true }
            .groupBy { it.at.toLocalDate() }
            .mapValues { (_, v) -> v.maxBy { it.at } }
    }
    ScaleTiles(lastPerDay, profile?.athleteMode ?: false, today)

    // 5. Muscle potential.
    val latestSplit = lastPerDay.maxByOrNull { it.key }?.value?.composition?.split
    val latestWeight = weightPoints.lastOrNull()?.second
    val fatFree = latestSplit?.fatFreeKg
        ?: latestWeight?.let { w -> blended.maxByOrNull { it.key }?.value?.let { bf -> w * (1 - bf / 100.0) } }
    val bodyFatNow = latestSplit?.bodyFatPct ?: blended.maxByOrNull { it.key }?.value
    val frame = remember(checkins) { latestFrame(checkins) }
    val potential = if (fatFree != null && heightCm != null && sex != null) musclePotential(fatFree, heightCm, sex == "male", frame, bodyFatNow) else null
    MusclePotentialSection(potential, askFrame = sex == "male" && frame == null, missing = when {
        heightCm == null -> "Needs your height: add it in Setup › Profile or at the next body check-in."
        sex == null -> "Needs your sex in Setup › Profile: the ceiling differs for men and women."
        fatFree == null -> "Needs a body-fat reading: a weigh-in on the scale, or a body check-in."
        else -> null
    })
}

@Composable
private fun ScaleTiles(lastPerDay: Map<LocalDate, ScaleCompositionPoint>, athleteMode: Boolean, today: LocalDate) {
    Section("FROM THE SCALE", info = SCALE_INFO) {
        if (lastPerDay.isEmpty()) {
            EmptyLine("Add height, date of birth and sex in Setup › Profile, then weigh in on the scale to see fat-free mass, muscle and water. Weigh-ins saved before that are used too.")
            return@Section
        }
        fun series(pick: (ScaleCompositionPoint, BodyComposition) -> Double?): Series =
            lastPerDay.mapNotNull { (d, p) -> p.composition?.let { pick(p, it) }?.let { d to it } }.toMap()
        val tiles = listOf(
            TileSpec("Fat-free mass", series { _, c -> c.split?.fatFreeKg }, "kg", Fmt.one, "Everything that is not fat: muscle, water, organs and bone (Sun 2003).", 0.3, FT.Emerald, betterHigher = true),
            TileSpec("Fat mass", series { _, c -> c.split?.fatKg }, "kg", Fmt.one, "Weight minus fat-free mass.", 0.3, FT.DomainFuel, betterHigher = false),
            TileSpec("Skeletal muscle", series { _, c -> c.skeletalMuscleKg }, "kg", Fmt.one, "Muscle attached to the skeleton (Janssen 2000), a part of fat-free mass.", 0.3, FT.Emerald, betterHigher = true),
            TileSpec("Body water", series { p, c -> c.totalBodyWaterL?.let { it / p.weightKg * 100 } }, "%", Fmt.one, "Total body water as a share of weight (Sun 2003). Mostly hydration and leanness; a day-to-day swing is hydration, not tissue.", 0.5, FT.DomainFuel),
            TileSpec("Resting energy", series { _, c -> c.bmrKcal }, "kcal", Fmt.int, "Energy burned at complete rest from fat-free mass (Katch-McArdle).", 15.0, FT.DomainFuel),
            TileSpec("Impedance index", series { _, c -> c.impedanceIndex }, "cm²/Ω", Fmt.one, "Height² ÷ impedance. No population equation involved, so it is the cleanest trend: it rises with more water-rich, lean tissue and falls when you are dehydrated. Compare like with like (same time of day).", 0.5, FT.DomainFuel),
            TileSpec("BMI", series { _, c -> c.bmi }, "kg/m²", Fmt.one, "Weight ÷ height². Says nothing about what the weight is made of; 18.5–25 is the general adult range.", 0.1, FT.DomainFuel),
            TileSpec("FFMI", series { _, c -> c.split?.ffmi }, "kg/m²", Fmt.one, "Fat-free mass ÷ height²: how much lean mass you carry for your height. Feeds muscle potential below.", 0.1, FT.Emerald, betterHigher = true),
        ).filter { it.series.isNotEmpty() }
        MetricTiles(tiles, today)

        val readings = lastPerDay.values.mapNotNull { p -> p.composition?.split?.let { Triple(p.at.toLocalDate(), p.weightKg, it) } }.sortedBy { it.first }
        val changes = listOf(30L, 90L).mapNotNull { days -> weightChangeSplit(readings, days, today)?.let { days to it } }
        if (changes.isNotEmpty()) {
            Text("WEIGHT CHANGE: FAT OR LEAN", style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.padding(top = 4.dp))
            StatTiles(changes.map { (days, s) ->
                Triple("LAST $days DAYS", "%+.1f kg".format(s.deltaWeightKg), "fat %+.1f · fat-free %+.1f".format(s.deltaFatKg, s.deltaFatFreeKg))
            })
        }
        if (athleteMode) {
            Text("Athlete mode is on: these equations were fitted on the general adult population and are less accurate in athletes.",
                style = FTType.Caption, color = FT.TextMuted)
        }
    }
}

@Composable
private fun MusclePotentialSection(p: MusclePotential?, askFrame: Boolean, missing: String?) {
    Section("MUSCLE POTENTIAL", info = POTENTIAL_INFO) {
        if (p == null) {
            EmptyLine(missing ?: "Not enough data yet.")
            return@Section
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                val track = FT.GlassBorder
                val fill = FT.Emerald
                Canvas(Modifier.size(112.dp)) {
                    val stroke = 10.dp.toPx()
                    val inset = stroke / 2
                    val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                    val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                    drawArc(track, -90f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                    drawArc(fill, -90f, (360 * p.fraction).toFloat(), false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Text("${(p.fraction * 100).roundToInt()} %", style = FTType.MetricMedium, color = FT.TextPrimary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("%.1f of ~%.0f kg".format(p.currentFatFreeKg, p.ceilingFatFreeKg), style = FTType.Telemetry, color = FT.TextPrimary)
                if (p.method == PotentialMethod.FRAME) {
                    Text("fat-free mass, now against the ceiling for your frame", style = FTType.Caption, color = FT.TextMuted)
                    Text("Wrist %.1f · ankle %.1f cm · at %.0f %% body fat".format(p.wristCm, p.ankleCm, p.atBodyFatPct), style = FTType.Caption, color = FT.TextSecondary)
                } else {
                    Text("fat-free mass, now against the usual ceiling for your height", style = FTType.Caption, color = FT.TextMuted)
                    Text("Normalised FFMI %.1f of %.0f".format(p.normalizedFfmi, p.ceilingFfmi), style = FTType.Caption, color = FT.TextSecondary)
                }
                val room = p.ceilingFatFreeKg - p.currentFatFreeKg
                if (room > 0) Text("About %.0f kg of room left".format(room), style = FTType.Caption, color = FT.TextSecondary)
            }
        }
        Text(
            when {
                p.method == PotentialMethod.FRAME -> "Casey Butt's frame formula. An estimate, not a prediction for you."
                askFrame -> "Ceiling from height alone (FFMI 25). Measure wrist and ankle at the next body check-in for one from your frame."
                else -> "Ceiling from height alone. An estimate from population data, not a prediction for you."
            },
            style = FTType.Caption, color = FT.TextMuted,
        )
    }
}

private fun shortDay(d: LocalDate): String = com.bioscan.fieldterminal.ui.screens.detail.shortDate(d)
