package com.bioscan.fieldterminal.domain.meview

import com.bioscan.fieldterminal.data.ScaleCompositionPoint
import com.bioscan.fieldterminal.data.model.BodyMetricsAnalysisRow
import com.bioscan.fieldterminal.data.model.UserProfileRow
import com.bioscan.fieldterminal.domain.BodyCheckinRecord
import com.bioscan.fieldterminal.domain.BodyComposition
import com.bioscan.fieldterminal.domain.BodySide
import com.bioscan.fieldterminal.domain.BodySite
import com.bioscan.fieldterminal.domain.ChangeVerdict
import com.bioscan.fieldterminal.domain.MeasureMethod
import com.bioscan.fieldterminal.domain.PotentialMethod
import com.bioscan.fieldterminal.domain.asymmetries
import com.bioscan.fieldterminal.domain.evaluateWeightTrend
import com.bioscan.fieldterminal.domain.jacksonPollockBodyFat
import com.bioscan.fieldterminal.domain.latestFrame
import com.bioscan.fieldterminal.domain.musclePotential
import com.bioscan.fieldterminal.domain.navyBodyFat
import com.bioscan.fieldterminal.domain.rollingBodyFat
import com.bioscan.fieldterminal.domain.siteChanges
import com.bioscan.fieldterminal.domain.trendStateWord
import com.bioscan.fieldterminal.domain.waistToHeight
import com.bioscan.fieldterminal.domain.waistToHip
import com.bioscan.fieldterminal.domain.weightChangeSplit
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

// Me › BODY as the web draws it: the same numbers as BodyTabContent.kt (weight trend, body fat by method, measurements
// judged against their error, the scale's estimates, muscle potential), worked out from the same rows.

private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private fun one(v: Double) = "%.1f".format(Locale.ENGLISH, v)

/** Where a measurement sits on the body (the health vocabulary's region codes, as the phone's body map uses them). */
fun bodyRegionOf(site: BodySite): String? = when (site) {
    BodySite.NECK -> "neck"
    BodySite.SHOULDERS -> "shoulder"
    BodySite.CHEST, BodySite.SF_CHEST -> "chest"
    BodySite.WAIST_NAVEL, BodySite.WAIST_MIDPOINT, BodySite.SF_ABDOMINAL, BodySite.SF_SUPRAILIAC -> "abdomen"
    BodySite.HIPS -> "glute"
    BodySite.BICEPS_RELAXED, BodySite.BICEPS_FLEXED -> "upper_arm"
    BodySite.FOREARM -> "forearm"
    BodySite.WRIST -> "wrist"
    BodySite.THIGH, BodySite.SF_THIGH -> "quad"
    BodySite.CALF -> "calf"
    BodySite.ANKLE -> "ankle"
}

fun bodyMeView(
    b: List<BodyMetricsAnalysisRow>,
    checkins: List<BodyCheckinRecord>,
    heightCm: Double?,
    profile: UserProfileRow?,
    scaleSeries: List<ScaleCompositionPoint>,
    today: LocalDate,
): MeViewDoc {
    val sections = mutableListOf<MeSection>()

    // 1. Weight.
    val weight = b.mapNotNull { r -> r.weightKg?.let { LocalDate.parse(r.date) to it } }.sortedBy { it.first }
    val eval = evaluateWeightTrend(weight, today)
    sections += MeSection("WEIGHT", if (weight.isEmpty()) listOf(MeNote("No weigh-ins yet.")) else listOf(
        MeTile("Weight", one(weight.last().second), "kg", sub = "on ${weight.last().first.format(DAY)}", hue = "m-weight"),
        MeChart("WEIGHT", "kg", 1, listOf(MeSeries("Weight", "m-weight", weight)),
            caption = listOfNotNull(trendStateWord(eval.state).word, eval.rateKgPerWeek?.let { "%+.2f kg / week".format(Locale.ENGLISH, it) }).joinToString(" · ").ifEmpty { null }),
    ))

    // 2. Body fat: each method its own line, the blend as the trend.
    val sex = profile?.sex?.takeIf { it == "male" || it == "female" }
    val age = profile?.dateOfBirth?.let { runCatching { ChronoUnit.YEARS.between(LocalDate.parse(it), today).toInt() }.getOrNull() }
    val scaleFat = b.mapNotNull { r -> r.bodyFatPct?.let { LocalDate.parse(r.date) to it } }.sortedBy { it.first }
    val tape = checkins.mapNotNull { c -> navyBodyFat(c, heightCm, sex)?.let { c.date to it } }.sortedBy { it.first }
    val folds = checkins.mapNotNull { c -> jacksonPollockBodyFat(c, age, sex)?.let { c.date to it } }.sortedBy { it.first }
    val blended = rollingBodyFat(scaleFat + tape + folds)
    val methods = listOfNotNull(
        scaleFat.lastOrNull()?.let { MeTile("Scale", one(it.second), "%", sub = it.first.format(DAY), hue = "m-body-fat") },
        tape.lastOrNull()?.let { MeTile("Tape", one(it.second), "%", sub = it.first.format(DAY), hue = "m-body-fat-tape") },
        folds.lastOrNull()?.let { MeTile("Skinfolds", one(it.second), "%", sub = it.first.format(DAY), hue = "m-body-fat-caliper") },
    )
    val lines = listOf(MeSeries("Scale", "m-body-fat", scaleFat), MeSeries("Tape", "m-body-fat-tape", tape), MeSeries("Caliper", "m-body-fat-caliper", folds)).filter { it.points.isNotEmpty() }
    sections += MeSection("BODY FAT", if (lines.isEmpty()) listOf(MeNote("No body-fat readings yet: a weigh-in on the scale, or a body check-in with tape or calipers.")) else
        listOf(MeChart("BODY FAT", "%", 1, lines, caption = blended.lastOrNull()?.let { "Blended trend ${one(it.second)} %" })) + methods)

    // 3. Measurements, judged against each site's own error; each placed on the body.
    val changes = siteChanges(checkins)
    val last = checkins.maxByOrNull { it.occurredAt }
    val measures = changes.map { c ->
        val unit = c.slot.site.method.unit
        val verdict = when (c.verdict) { ChangeVerdict.UP -> "UP"; ChangeVerdict.DOWN -> "DOWN"; ChangeVerdict.WITHIN_ERROR -> "WITHIN ERROR"; null -> null }
        MeRow(
            c.slot.label, "${one(c.latest)} $unit",
            sub = c.change?.let { "%+.1f $unit since the check-in before".format(Locale.ENGLISH, it) } ?: "first measurement",
            state = verdict, region = bodyRegionOf(c.slot.site),
            side = when (c.slot.side) { BodySide.RIGHT -> if (c.slot.site.method == MeasureMethod.CALIPER) null else "right"; BodySide.LEFT -> "left"; BodySide.NONE -> null },
        )
    }
    val ratios = listOfNotNull(
        last?.let { waistToHeight(it, heightCm) }?.let { MeTile("Waist to height", "%.2f".format(Locale.ENGLISH, it), tone = if (it < 0.5) Tone.OK else Tone.WARN, sub = "under 0.50 is the threshold the evidence supports") },
        last?.let { waistToHip(it) }?.let { MeTile("Waist to hip", "%.2f".format(Locale.ENGLISH, it)) },
    )
    val asym = asymmetries(checkins).map { a ->
        MeRow("${a.site.label}: right − left", "%+.1f cm".format(Locale.ENGLISH, a.difference), state = if (a.beyondError) "BEYOND ERROR" else "WITHIN ERROR", region = bodyRegionOf(a.site))
    }
    sections += MeSection("BODY MEASUREMENTS", if (measures.isEmpty()) listOf(MeNote("No body check-in yet: it starts from LAB or + on the phone.")) else
        listOfNotNull(last?.let { MeNote("Last check-in ${it.date.format(DAY)}. A change is real when it is beyond the site's own measuring error.") }) + ratios + measures + asym)

    // 4. The scale's other estimates.
    val lastPerDay = scaleSeries.filter { it.composition?.qualityOk == true }.groupBy { it.at.toLocalDate() }.mapValues { (_, v) -> v.maxBy { it.at } }
    fun series(pick: (ScaleCompositionPoint, BodyComposition) -> Double?) =
        lastPerDay.mapNotNull { (d, p) -> p.composition?.let { pick(p, it) }?.let { d to it } }.sortedBy { it.first }
    fun tile(label: String, unit: String, decimals: Int, hue: String, s: List<Pair<LocalDate, Double>>) =
        s.lastOrNull()?.let { MeTile(label, if (decimals == 0) "${it.second.roundToInt()}" else one(it.second), unit, sub = it.first.format(DAY), hue = hue, series = s) }
    val scaleTiles = listOfNotNull(
        tile("Fat-free mass", "kg", 1, "ok", series { _, c -> c.split?.fatFreeKg }),
        tile("Fat mass", "kg", 1, "ft-domain-fuel", series { _, c -> c.split?.fatKg }),
        tile("Skeletal muscle", "kg", 1, "ok", series { _, c -> c.skeletalMuscleKg }),
        tile("Body water", "%", 1, "ft-domain-fuel", series { p, c -> c.totalBodyWaterL?.let { it / p.weightKg * 100 } }),
        tile("Resting energy", "kcal", 0, "ft-domain-fuel", series { _, c -> c.bmrKcal }),
        tile("Impedance index", "cm²/Ω", 1, "ft-domain-fuel", series { _, c -> c.impedanceIndex }),
        tile("BMI", "kg/m²", 1, "ft-domain-fuel", series { _, c -> c.bmi }),
        tile("FFMI", "kg/m²", 1, "ok", series { _, c -> c.split?.ffmi }),
    )
    val readings = lastPerDay.values.mapNotNull { p -> p.composition?.split?.let { Triple(p.at.toLocalDate(), p.weightKg, it) } }.sortedBy { it.first }
    val split = listOf(30L, 90L).mapNotNull { days -> weightChangeSplit(readings, days, today)?.let { s ->
        MeTile("Weight change, last $days days", "%+.1f".format(Locale.ENGLISH, s.deltaWeightKg), "kg", sub = "fat %+.1f · fat-free %+.1f".format(Locale.ENGLISH, s.deltaFatKg, s.deltaFatFreeKg))
    } }
    sections += MeSection("FROM THE SCALE", if (scaleTiles.isEmpty()) listOf(MeNote("Add height, date of birth and sex in Setup › Profile, then weigh in on the scale to see fat-free mass, muscle and water.")) else
        scaleTiles + split + listOfNotNull(if (profile?.athleteMode == true) MeNote("Athlete mode is on: these equations were fitted on the general adult population and are less accurate in athletes.") else null))

    // 5. Muscle potential.
    val latestSplit = lastPerDay.maxByOrNull { it.key }?.value?.composition?.split
    val fatFree = latestSplit?.fatFreeKg ?: weight.lastOrNull()?.second?.let { w -> blended.lastOrNull()?.second?.let { bf -> w * (1 - bf / 100.0) } }
    val bodyFatNow = latestSplit?.bodyFatPct ?: blended.lastOrNull()?.second
    val frame = latestFrame(checkins)
    val p = if (fatFree != null && heightCm != null && sex != null) musclePotential(fatFree, heightCm, sex == "male", frame, bodyFatNow) else null
    sections += MeSection("MUSCLE POTENTIAL", if (p == null) listOf(MeNote(when {
        heightCm == null -> "Needs your height: add it in Setup › Profile or at the next body check-in."
        sex == null -> "Needs your sex in Setup › Profile: the ceiling differs for men and women."
        fatFree == null -> "Needs a body-fat reading: a weigh-in on the scale, or a body check-in."
        else -> "Not enough data yet."
    })) else listOf(MeRing("MUSCLE POTENTIAL", p.fraction, "${(p.fraction * 100).roundToInt()} %", listOfNotNull(
        "%.1f of ~%.0f kg fat-free mass".format(Locale.ENGLISH, p.currentFatFreeKg, p.ceilingFatFreeKg),
        if (p.method == PotentialMethod.FRAME) "Wrist %.1f · ankle %.1f cm · at %.0f %% body fat".format(Locale.ENGLISH, p.wristCm, p.ankleCm, p.atBodyFatPct)
        else "Normalised FFMI %.1f of %.0f".format(Locale.ENGLISH, p.normalizedFfmi, p.ceilingFfmi),
        (p.ceilingFatFreeKg - p.currentFatFreeKg).takeIf { it > 0 }?.let { "About %.0f kg of room left".format(Locale.ENGLISH, it) },
        if (p.method == PotentialMethod.FRAME) "Casey Butt's frame formula. An estimate, not a prediction for you." else "Ceiling from height alone. An estimate from population data, not a prediction for you.",
    ), "ok")))

    return MeViewDoc("BODY", sections)
}

