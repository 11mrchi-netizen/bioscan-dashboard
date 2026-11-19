package com.bioscan.fieldterminal.domain.meview

import com.bioscan.fieldterminal.data.ExposuresData
import com.bioscan.fieldterminal.data.RiskScreens
import com.bioscan.fieldterminal.domain.risk.FactorState
import com.bioscan.fieldterminal.domain.risk.PreventOutcome
import com.bioscan.fieldterminal.domain.risk.PreventOutcomeOrWhy
import com.bioscan.fieldterminal.domain.risk.preventCategory
import com.bioscan.fieldterminal.domain.risk.INSTRUMENT_DOMAINS
import com.bioscan.fieldterminal.domain.risk.InstrumentSummary
import java.time.LocalDate
import java.util.Locale

// Me › EXPOSURES as the web draws it: the same content as ui/screens/me/ExposuresPage.kt (risk profile R1).

const val AIR_GUIDELINES =
    "Worked out each night from where you were (places and location fixes), the modelled outdoor air there (Open-Meteo, " +
        "CAMS) and, at home, the Home Terminal sensors. Without an indoor PM2.5 sensor, home hours use the outdoor level at " +
        "home, a stand-in. Guidelines: WHO 2021, PM2.5 15 µg/m³ a day and 5 a year, NO₂ 25 a day and 10 a year, ozone 100 " +
        "as the highest 8-hour mean. CO₂ 1,000 / 1,400 ppm are ventilation conventions, not health limits. A daily mean is " +
        "compared with the annual guideline only as a 30-day average."

private fun f1(v: Double) = "%.1f".format(Locale.ENGLISH, v)
private fun tone(s: String?): Tone? = when (s) { "ok" -> Tone.OK; "warn" -> Tone.WARN; "bad" -> Tone.BAD; else -> null }

/** Each questionnaire's hue token (design/tokens --m-*), as ui/screens/me/QuestionnaireCards.kt's instrumentInk. */
fun instrumentHue(id: String): String = when (id) {
    "phq9" -> "m-phq9"; "gad7" -> "m-gad7"; "who5" -> "m-who5"; "ucla3" -> "m-loneliness"; "cbi_work" -> "m-burnout"; "work_hours" -> "m-work-hours"
    else -> "ft-fg"
}

private fun qTone(state: String?): Tone? = when (state) { "ok" -> Tone.OK; "warn" -> Tone.WARN; "bad" -> Tone.BAD; else -> null }
private fun qScore(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(Locale.ENGLISH, v)

/** Risk profile R2: the questionnaires by domain, each its latest score, band, date and when it is due. */
private fun questionnaireSections(summaries: List<InstrumentSummary>): List<MeSection> = INSTRUMENT_DOMAINS.mapNotNull { (domain, title) ->
    val rows = summaries.filter { it.instrument.domain == domain }
    if (rows.isEmpty()) return@mapNotNull null
    MeSection(title, rows.map { s ->
        val l = s.latest
        MeTile(
            "${s.instrument.name} (${s.instrument.short})", l?.let { qScore(it.score) } ?: "—", l?.let { s.instrument.unit },
            sub = listOfNotNull(l?.band, l?.let { "taken ${it.date}" } ?: "not taken yet", if (s.due) "due now" else s.dueOn?.let { "next $it" }).joinToString(" · "),
            tone = qTone(s.state), hue = instrumentHue(s.instrument.id), series = s.series.toList(),
        )
    }, info = "Validated questionnaires, answered on the phone or in the console. Screens, not diagnoses.")
}

private fun pctW(x: Double) = String.format(Locale.ENGLISH, if (x < 0.1) "%.1f" else "%.0f", x * 100)

/** Risk profile R3: the heart, sugar and brain screens, as ui/screens/me/RiskScreenCards.kt shows them. */
fun screenSections(s: RiskScreens): List<MeSection> {
    val out = mutableListOf<MeSection>()
    val prevent = when (val p = s.prevent) {
        is PreventOutcomeOrWhy.Ok -> {
            val total = p.result.tenYear.getValue(PreventOutcome.TOTAL_CVD)
            val cat = preventCategory(total)
            listOf(MeTile("Heart attack, stroke or heart failure in 10 years", pctW(total), "%", sub = cat.label, tone = qTone(cat.state))) +
                PreventOutcome.entries.drop(1).map { o -> MeRow(o.label, value = "${pctW(p.result.tenYear.getValue(o))} %") } +
                listOfNotNull(p.result.thirtyYear?.let { MeRow("Any of these in 30 years", value = "${pctW(it.getValue(PreventOutcome.TOTAL_CVD))} %") })
        }
        is PreventOutcomeOrWhy.NotApplicable -> listOf(MeNote("Not worked out: ${p.reasons.joinToString("; ")}."))
        null -> listOf(MeNote("Not worked out."))
    }
    out += MeSection("HEART · PREVENT", prevent, info = "AHA PREVENT equations (Khan et al. 2024), base model; valid from 30 to 79. Low under 5 %, borderline to 7.5 %, intermediate to 20 %, high from 20 %.")
    val le8 = s.le8
    out += MeSection("HEART · LIFE'S ESSENTIAL 8", listOfNotNull(
        le8.score?.let { MeTile("Life's Essential 8", "$it", "/ 100", sub = "${le8.band} · ${le8.assessed.size} of 8 measures", tone = qTone(le8.state)) },
    ) + le8.metrics.map { m -> MeRow(m.metric.label, value = m.points?.toString(), sub = m.reading ?: m.missing?.let { "needs $it" }, tone = m.points?.let { qTone(if (it >= 80) "ok" else if (it >= 50) "warn" else "bad") }) },
        info = "AHA Life's Essential 8 (Lloyd-Jones et al. 2022). Diet needs a questionnaire that comes later.")
    val fr = s.findrisc
    out += MeSection("SUGAR · FINDRISC", listOf(
        MeTile("FINDRISC", "${fr.score}", "/ 26", sub = if (fr.complete) "${fr.band} · ${fr.tenYearRisk} in 10 years" else "so far; up to ${fr.maxPossible}", tone = if (fr.complete) qTone(fr.state) else null),
    ) + fr.items.map { MeRow(it.label, value = it.points?.toString(), sub = it.reading ?: "not answered") }, info = "Finnish Diabetes Risk Score (Lindström & Tuomilehto 2003).")
    out += MeSection("BRAIN · DEMENTIA FACTORS", s.brain.map { f ->
        MeRow(f.label, value = f.state.word, sub = if (f.state == FactorState.UNKNOWN) "needs ${f.needs}" else f.reading,
            tone = when (f.state) { FactorState.PRESENT -> Tone.WARN; FactorState.ABSENT -> Tone.OK; else -> null })
    }, info = "The 14 modifiable factors of the 2024 Lancet Commission; no combined number.")
    return out
}

fun exposuresMeView(d: ExposuresData, questionnaires: List<InstrumentSummary> = emptyList(), screens: RiskScreens? = null): MeViewDoc {
    val s = d.summary
    val sections = mutableListOf<MeSection>()
    sections += questionnaireSections(questionnaires)
    screens?.let { sections += screenSections(it) }
    sections += MeSection("AIR · LAST 30 DAYS", if (s == null || s.daysWithData == 0) listOf(MeNote("No air days yet.")) else listOfNotNull(
        MeTile("PM2.5 breathed", s.pm25Mean?.let(::f1) ?: "—", "µg/m³", tone = tone(s.states["pm25"]), hue = "m-pm25",
            sub = "${s.pm25DaysOver} of ${s.fullDays} full days above 15", series = d.series { it.pm25 }.toList()),
        MeTile("NO₂ where you were", s.no2Mean?.let(::f1) ?: "—", "µg/m³", tone = tone(s.states["no2"]), hue = "m-no2",
            sub = "${s.no2DaysOver} days above 25", series = d.series { it.no2 }.toList()),
        s.co2NightMean?.let { MeTile("CO₂ at night", "%.0f".format(Locale.ENGLISH, it), "ppm", tone = tone(s.states["co2"]), hue = "m-co2", sub = "${s.co2PoorNights} poor nights", series = d.series { r -> r.co2Night }.toList()) },
        MeNote(listOfNotNull(
            s.doseHomeShare?.let { "${(it * 100).toInt()} % of the PM2.5 dose at home" },
            if (s.indoorSensor) "measured indoors" else "home estimated from the outdoor level",
            "ozone above 100 on ${s.o3DaysOver} days",
        ).joinToString(" · ")),
    ), info = AIR_GUIDELINES)
    s?.improvements?.takeIf { it.isNotEmpty() }?.let { list ->
        sections += MeSection("WAYS TO IMPROVE", list.map { i -> MeRow(i.title, sub = "${i.why} ${i.evidence}", state = "EFFORT ${i.effort.uppercase()}") })
    }
    val sessions = d.days.flatMap { it.sessions }.takeLast(8).reversed()
    if (sessions.isNotEmpty()) {
        sections += MeSection("OUTDOOR SESSIONS", sessions.map { x ->
            MeRow("${x.start.take(10)} · ${x.type} ${x.minutes} min",
                value = x.pm25?.let { "PM2.5 ${"%.0f".format(Locale.ENGLISH, it)}" },
                sub = listOfNotNull(x.o3?.let { "O₃ ${"%.0f".format(Locale.ENGLISH, it)}" }, x.uv?.let { "UV ${"%.0f".format(Locale.ENGLISH, it)}" }).joinToString(" · ").ifEmpty { null },
                tone = if ((x.pm25 ?: 0.0) > 15 || (x.o3 ?: 0.0) > 100) Tone.WARN else null)
        })
    }
    return MeViewDoc("EXPOSURES", sections, range = d.summaryAsOf?.let { "30 DAYS TO ${LocalDate.parse(it)}" })
}
