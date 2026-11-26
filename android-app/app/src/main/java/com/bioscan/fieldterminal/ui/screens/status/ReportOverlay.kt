package com.bioscan.fieldterminal.ui.screens.status

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.ReportsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.notifications.REPORT_SECTIONS
import com.bioscan.fieldterminal.domain.notifications.ReportDetail
import com.bioscan.fieldterminal.domain.notifications.ReportTone
import com.bioscan.fieldterminal.domain.notifications.reportHeadline
import com.bioscan.fieldterminal.domain.notifications.reportLabVerdict
import com.bioscan.fieldterminal.domain.notifications.reportMetricStatus
import com.bioscan.fieldterminal.domain.notifications.reportMetricSub
import com.bioscan.fieldterminal.domain.notifications.reportMicroOrder
import com.bioscan.fieldterminal.domain.notifications.reportMicroStatus
import com.bioscan.fieldterminal.domain.notifications.reportMicroSub
import com.bioscan.fieldterminal.domain.notifications.reportNumber
import com.bioscan.fieldterminal.domain.notifications.reportRegimenStatus
import com.bioscan.fieldterminal.domain.notifications.reportRegimenSub
import com.bioscan.fieldterminal.domain.notifications.reportWebUrl
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.LoadingOrError
import com.bioscan.fieldterminal.ui.components.rememberGuarded
import com.bioscan.fieldterminal.ui.screens.today.FullScreenOverlay
import com.bioscan.fieldterminal.ui.screens.today.OverlaySection
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// The report page (docs/reports), opened from the report notification and from Lab › N=1's REPORTS card: the same report
// the web's Lab › Reports draws, in the app's own design. Metric names in their metric hue, how each line is in status
// colours (Ok / Warn / Bad) and words, nothing tinted. The web page keeps the extras (food and stool, meal quality).

private class Loaded(val report: ReportDetail?)

@Composable
fun ReportOverlay(key: String, onClose: () -> Unit) {
    val (loaded, guard) = rememberGuarded(key) { Loaded(ReportsRepository(SupabaseClientProvider.client).report(key)) }
    val context = LocalContext.current
    val r = loaded?.report
    FullScreenOverlay(title = (r?.label?.ifBlank { null } ?: "REPORT").uppercase(), onClose = onClose, wash = FT.DomainLabs) {
        when {
            loaded == null -> LoadingOrError(guard, "Loading the report…")
            r == null -> Text("This report is not written yet.", style = FTType.BodySmall, color = FT.TextMuted)
            else -> ReportBody(r)
        }
        FTButton("OPEN ON THE WEB", modifier = Modifier.fillMaxWidth()) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(reportWebUrl(key))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}

private fun toneInk(t: ReportTone): Color = when (t) {
    ReportTone.OK -> FT.Ok
    ReportTone.WARN -> FT.Warn
    ReportTone.BAD -> FT.Bad
    ReportTone.NONE -> FT.TextSecondary
}

/** Each report series in its fixed metric hue (design/tokens); a series with none of its own is plain ink. */
private fun seriesInk(id: String): Color = when (id) {
    "hrv" -> FT.Metric.Hrv
    "rhr" -> FT.Metric.RestingHr
    "vo2max" -> FT.Metric.Vo2max
    "sleep_hours" -> FT.Metric.SleepDuration
    "sleep_score" -> FT.Metric.SleepScore
    "sleep_deep_min" -> FT.Metric.StageDeep
    "sleep_rem_min" -> FT.Metric.StageRem
    "sleep_light_min" -> FT.Metric.StageLight
    "steps" -> FT.Metric.Steps
    "run_distance" -> FT.Metric.Distance
    "strength_duration" -> FT.Metric.Duration
    "session_rpe" -> FT.Metric.Rpe
    "body_weight" -> FT.Metric.Weight
    "body_fat_pct" -> FT.Metric.BodyFat
    "energy" -> FT.Metric.Energy
    "mood" -> FT.Metric.Mood
    "stress" -> FT.Metric.Stress
    "soreness" -> FT.Metric.Soreness
    "meal_energy" -> FT.Metric.Calories
    "meal_protein" -> FT.Metric.Protein
    "meal_carbs" -> FT.Metric.Carbs
    "meal_fat" -> FT.Metric.Fat
    "meal_fiber" -> FT.Metric.Fiber
    "water_intake" -> FT.Metric.Hydration
    "stool_normal_pct", "gut_day_score" -> FT.Metric.StoolHealthy
    "stool_loose_pct" -> FT.Metric.StoolLoose
    "stool_discomfort" -> FT.Metric.GutComfort
    else -> FT.TextPrimary
}

/** One line: a name, its status word on the right, and the numbers under it. */
@Composable
private fun ReportRow(name: String, nameInk: Color, value: String?, word: String, tone: ReportTone, sub: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, style = FTType.RowTitle, color = nameInk, modifier = Modifier.weight(1f))
            if (value != null) Text(value, style = FTType.Value, color = FT.TextPrimary)
            Text(word.uppercase(), style = FTType.Label, color = toneInk(tone))
        }
        Text(sub, style = FTType.Caption, color = FT.TextMuted)
    }
}

@Composable
private fun Lines(content: @Composable () -> Unit) =
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }

@Composable
private fun ReportBody(r: ReportDetail) {
    Text(reportHeadline(r), style = FTType.Body, color = FT.TextPrimary, modifier = Modifier.padding(horizontal = 4.dp))

    if (r.suggestions.isNotEmpty()) OverlaySection("SUGGESTIONS", info = "From fixed rules on this period's numbers, the most pressing first.") {
        Lines {
            r.suggestions.forEach { s ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(s.text, style = FTType.BodySmall, color = FT.TextPrimary)
                    if (s.because.isNotBlank()) Text(s.because, style = FTType.Caption, color = FT.TextMuted)
                }
            }
        }
    }

    val glance = listOf(
        "Better" to r.better, "Worse" to r.worse, "Working" to r.working, "Not worth it so far" to r.wasted,
        "Not followed" to r.notFollowed, "Nutrients over the upper limit" to r.excess, "Nutrients possibly short" to r.short,
    ).filter { it.second.isNotEmpty() }
    if (glance.isNotEmpty()) OverlaySection("AT A GLANCE") {
        Lines {
            glance.forEach { (title, names) ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title.uppercase(), style = FTType.Label, color = FT.TextSecondary)
                    Text(names.joinToString(", "), style = FTType.BodySmall, color = FT.TextPrimary)
                }
            }
        }
    }

    // Metrics by section, the lines with data only.
    val shown = r.metrics.filter { it.status != "no_data" }
    REPORT_SECTIONS.forEach { (section, title) ->
        val lines = shown.filter { it.section == section }
        if (lines.isNotEmpty()) OverlaySection(title.uppercase()) {
            Lines {
                lines.forEach { m ->
                    val (word, tone) = reportMetricStatus(m)
                    ReportRow(m.label, seriesInk(m.id), m.value?.let { "${reportNumber(it)} ${m.unit}".trim() }, word, tone, reportMetricSub(m, r.grain))
                }
            }
        }
    }

    if (r.regimen.isNotEmpty()) OverlaySection("REGIMEN", info = "Doses logged against those planned, each day by the schedule in force that day. 85% or more is followed, under 70% not followed.") {
        Lines {
            r.regimen.sortedBy { when (it.status) { "not_followed" -> 0; "partly" -> 1; "followed" -> 2; else -> 3 } }.forEach { a ->
                val (word, tone) = reportRegimenStatus(a)
                ReportRow(a.name, FT.TextPrimary, null, word, tone, reportRegimenSub(a))
            }
        }
    }

    if (r.lab.isNotEmpty()) OverlaySection("LAB", info = "What the interventions and experiments running in the period found.") {
        Lines {
            r.lab.forEach { l ->
                val (word, tone) = reportLabVerdict(l)
                ReportRow(l.name, FT.TextPrimary, null, word, tone, l.detail)
            }
        }
    }

    val micro = reportMicroOrder(r.micro)
    if (micro.isNotEmpty()) OverlaySection("NUTRIENTS · FOOD AND SUPPLEMENTS", info = "Daily means: food over the days with food logged, supplements over every day. Against adult daily needs and safe upper limits.") {
        Lines {
            micro.forEach { m ->
                val (word, tone) = reportMicroStatus(m)
                ReportRow(m.label, FT.TextPrimary, m.value?.let { "${reportNumber(it)} ${m.unit}".trim() }, word, tone, reportMicroSub(m))
            }
        }
    }
}
