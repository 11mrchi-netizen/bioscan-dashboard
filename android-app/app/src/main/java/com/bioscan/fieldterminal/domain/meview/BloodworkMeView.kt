package com.bioscan.fieldterminal.domain.meview

import com.bioscan.fieldterminal.data.LabsOverview
import com.bioscan.fieldterminal.domain.BloodworkMarkerEvaluation
import com.bioscan.fieldterminal.domain.BloodworkTrendState
import com.bioscan.fieldterminal.domain.LabMarkerTheme
import com.bioscan.fieldterminal.domain.LabWatch
import com.bioscan.fieldterminal.domain.MarkerDirection
import com.bioscan.fieldterminal.domain.formatAmount
import com.bioscan.fieldterminal.domain.labMarkerTheme
import com.bioscan.fieldterminal.domain.labReadingWords
import java.util.Locale

// Me › BLOODWORK as the web draws it: the same content as LabsScreen.kt and LabWatchSection.kt (what to watch for the
// regimen, then each marker by theme with its flag, the change since the draw before and that change against its RCV).

private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
private fun shortDate(iso: String): String {
    val p = iso.take(10).split("-")
    if (p.size != 3) return iso
    return "${p[2]} ${p[1].toIntOrNull()?.let { MONTHS.getOrNull(it - 1) } ?: p[1]}"
}

private fun trimZero(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

private fun formatRef(unit: String?, low: Double?, high: Double?): String {
    val range = when {
        low != null && high != null -> "${trimZero(low)}–${trimZero(high)}"
        high != null -> "<${trimZero(high)}"
        low != null -> ">${trimZero(low)}"
        else -> null
    }
    return listOfNotNull(unit, range).joinToString(" · ")
}

fun bloodworkMeView(overview: LabsOverview, rcv: Map<String, BloodworkMarkerEvaluation>, watches: List<LabWatch>): MeViewDoc {
    val sections = mutableListOf<MeSection>()
    if (watches.isNotEmpty()) {
        sections += MeSection("WATCH FOR WHAT YOU TAKE", watches.map { w ->
            val daily = w.dailyAmount?.let { " · ${formatAmount(Math.round(it * 10) / 10.0)} ${w.rule.amountUnit} a day" } ?: ""
            val tag = when {
                w.rule.disputedNote != null -> "Disputed by you"
                w.rule.reviewed -> "Reviewed"
                else -> "Unreviewed"
            }
            MeRow(
                "${w.itemName}$daily → ${w.rule.markerLabel}",
                value = if (w.latest.isEmpty()) "Never measured" else null,
                tone = if (w.latest.isEmpty()) Tone.WARN else null,
                state = tag.uppercase(),
                sub = (w.latest.take(3).map(::labReadingWords) + w.rule.advice + "Source: ${w.rule.source}").joinToString(" · "),
            )
        }, info = "Each line is a curated rule with its source: a lab marker worth watching while taking something in your regimen.")
    }
    val latest = overview.latestDraw
    if (latest == null) {
        sections += MeSection("BLOODWORK", listOf(MeNote("No lab draws logged yet. Add a result or upload a report on the phone (Me › Bloodwork).")))
        return MeViewDoc("BLOODWORK", sections)
    }
    val draws = if (overview.earlierDraw != null) 2 else 1
    val span = overview.earlierDraw?.let { "${shortDate(it.drawDate)} → ${shortDate(latest.drawDate)}" } ?: shortDate(latest.drawDate)
    sections += MeSection("BLOODWORK · $draws DRAW${if (draws == 1) "" else "S"} · ${overview.markers.size} MARKERS", listOf(MeNote(span, muted = false)))
    val grouped = overview.markers.groupBy { labMarkerTheme(it.name) }
    for (theme in LabMarkerTheme.entries) {
        val markers = grouped[theme] ?: continue
        sections += MeSection(theme.label, markers.map { m ->
            val r = rcv[m.name]
            val flag = when (m.latestFlag) { "high" -> "ABOVE REFERENCE"; "low" -> "BELOW REFERENCE"; "watch" -> "WATCH"; else -> null }
            val arrow = when (m.direction) { MarkerDirection.UP -> " ▲"; MarkerDirection.DOWN -> " ▼"; else -> "" }
            val change = r?.let { e ->
                if (e.previousValue != null && e.deltaPercent != null && e.rcv != null) {
                    val beyond = e.state == BloodworkTrendState.ShiftUp || e.state == BloodworkTrendState.ShiftDown
                    "Δ%+.1f%% · RCV ±%.1f%%%s".format(Locale.ENGLISH, e.deltaPercent, e.rcv, if (beyond) " · beyond it" else " · within it")
                } else null
            }
            MeRow(
                m.name, (m.latestDisplay ?: "—") + arrow,
                tone = when (m.latestFlag) { "high", "low" -> Tone.BAD; "watch" -> Tone.WARN; else -> null },
                state = flag,
                sub = listOfNotNull(
                    m.earlierDisplay?.let { "before ${it}" },
                    formatRef(m.unit, m.refLow, m.refHigh).takeIf { it.isNotEmpty() }?.let { "reference $it" },
                    change,
                ).joinToString(" · ").ifEmpty { null },
            )
        })
    }
    return MeViewDoc("BLOODWORK", sections)
}
