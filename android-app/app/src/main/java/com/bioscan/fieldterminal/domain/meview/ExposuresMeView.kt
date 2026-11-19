package com.bioscan.fieldterminal.domain.meview

import com.bioscan.fieldterminal.data.ExposuresData
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

fun exposuresMeView(d: ExposuresData): MeViewDoc {
    val s = d.summary
    val sections = mutableListOf<MeSection>()
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
