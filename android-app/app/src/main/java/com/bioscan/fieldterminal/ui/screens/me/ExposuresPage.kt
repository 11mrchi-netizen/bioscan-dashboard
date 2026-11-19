package com.bioscan.fieldterminal.ui.screens.me

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AirDayRow
import com.bioscan.fieldterminal.data.ExposuresData
import com.bioscan.fieldterminal.data.ExposuresRepository
import com.bioscan.fieldterminal.data.InstrumentResponseRow
import com.bioscan.fieldterminal.data.InstrumentsRepository
import com.bioscan.fieldterminal.data.RiskScreens
import com.bioscan.fieldterminal.data.AirImprovement
import com.bioscan.fieldterminal.data.ImprovementsRepository
import com.bioscan.fieldterminal.domain.risk.IMPROVEMENT_MIN_DAYS
import com.bioscan.fieldterminal.domain.risk.StartedImprovement
import com.bioscan.fieldterminal.domain.risk.beforeAfter
import com.bioscan.fieldterminal.domain.risk.metricsFor
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.launchSafe
import androidx.compose.runtime.rememberCoroutineScope
import com.bioscan.fieldterminal.data.PreventionData
import com.bioscan.fieldterminal.data.PreventionRepository
import com.bioscan.fieldterminal.data.preventiveContext
import com.bioscan.fieldterminal.data.toDomain
import com.bioscan.fieldterminal.domain.risk.PreventiveStatus
import com.bioscan.fieldterminal.domain.risk.states
import com.bioscan.fieldterminal.domain.risk.statuses
import com.bioscan.fieldterminal.data.RiskScreensRepository
import com.bioscan.fieldterminal.domain.risk.InstrumentAnswer
import com.bioscan.fieldterminal.domain.risk.InstrumentSummary
import com.bioscan.fieldterminal.domain.risk.summaries
import androidx.compose.ui.platform.LocalContext
import com.bioscan.fieldterminal.data.MeViewPublisher
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.catching
import com.bioscan.fieldterminal.domain.meview.exposuresMeView
import com.bioscan.fieldterminal.domain.meview.AIR_GUIDELINES
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTChevron
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.components.rememberCacheVersion
import com.bioscan.fieldterminal.ui.screens.detail.HistorySpec
import com.bioscan.fieldterminal.ui.screens.detail.MetricHistoryOverlay
import com.bioscan.fieldterminal.ui.theme.FTType
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.util.Locale
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Risk profile R1: Me › EXPOSURES. The air breathed over the last 30 days against the WHO 2021 guidelines, where the dose
// came from, outdoor sessions in poor air, and the ways to improve that fire on these numbers. Every metric opens its
// daily history. The numbers are worked out nightly on the server (analysis-core/airExposure.ts).

private data class AirMetric(
    val key: String,
    val label: String,
    val unit: String,
    val color: Color,
    val pick: (AirDayRow) -> Double?,
    /** The guideline line in words, for the row's sub-line. */
    val guide: String,
    val decimals: Int = 1,
)

private val METRICS = listOf(
    AirMetric("pm25", "PM2.5 breathed", "µg/m³", FT.Metric.Pm25, { it.pm25 }, "WHO 15 a day, 5 a year"),
    AirMetric("pm25_indoor", "PM2.5 indoors at home", "µg/m³", FT.Metric.Pm25, { it.pm25Indoor }, "from the home sensor"),
    AirMetric("pm25_outdoor_home", "PM2.5 outdoors at home", "µg/m³", FT.Metric.Pm25, { it.pm25OutdoorHome }, "modelled (CAMS)"),
    AirMetric("no2", "NO₂ where you were", "µg/m³", FT.Metric.No2, { it.no2 }, "WHO 25 a day, 10 a year"),
    AirMetric("o3", "Ozone, highest 8 h", "µg/m³", FT.Metric.Ozone, { it.o3 }, "WHO 100"),
    AirMetric("co2", "CO₂ at night, home", "ppm", FT.Metric.Co2, { it.co2Night }, "1,000 ventilate · 1,400 poor", decimals = 0),
    AirMetric("uv", "UV index, daytime peak", "", FT.Metric.UvIndex, { it.uvMax }, "6+ high"),
)

private fun fmt(v: Double, decimals: Int) = if (decimals == 0) "%,.0f".format(Locale.ENGLISH, v) else "%.1f".format(Locale.ENGLISH, v)

private fun toneColor(state: String?): Color? = when (state) { "ok" -> FT.Ok; "warn" -> FT.Warn; "bad" -> FT.Bad; else -> null }

@Composable
fun ExposuresPage() {
    var data by remember { mutableStateOf<ExposuresData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var history by remember { mutableStateOf<AirMetric?>(null) }
    // Risk profile R2: the questionnaires, loaded on their own so a failure on one side never hides the other.
    val context = LocalContext.current
    val defs = remember { InstrumentsRepository.definitions(context) }
    var answers by remember { mutableStateOf<List<InstrumentResponseRow>?>(null) }
    var answersError by remember { mutableStateOf<String?>(null) }
    var taking by remember { mutableStateOf<String?>(null) }
    var questionnaireHistory by remember { mutableStateOf<InstrumentSummary?>(null) }
    // Risk profile R3: the heart, sugar and brain screens, also loaded on their own.
    var screens by remember { mutableStateOf<RiskScreens?>(null) }
    var screensError by remember { mutableStateOf<String?>(null) }
    var editingBackground by remember { mutableStateOf(false) }
    // Risk profile R4: substances, sun and heat, home and water, preventive care.
    var prevention by remember { mutableStateOf<PreventionData?>(null) }
    var editingChecklist by remember { mutableStateOf(false) }
    // Risk profile R5: ways to improve started as Lab interventions.
    var improvements by remember { mutableStateOf<List<StartedImprovement>>(emptyList()) }
    val scope = rememberCoroutineScope()
    var recording by remember { mutableStateOf<PreventiveStatus?>(null) }
    val checklistDefs = remember { PreventionRepository.checklistDefs(context) }
    val preventiveDefs = remember { PreventionRepository.preventiveDefs(context) }
    val cacheVersion = rememberCacheVersion()
    LaunchedEffect(reload, cacheVersion) {
        try {
            answers = InstrumentsRepository(SupabaseClientProvider.client).loadResponses()
            answersError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            answersError = describeLoadFailure(e)
        }
        try {
            screens = RiskScreensRepository(SupabaseClientProvider.client).load(context)
            screensError = null
            prevention = catching { PreventionRepository(SupabaseClientProvider.client).load() }.getOrNull()
            improvements = ImprovementsRepository(SupabaseClientProvider.client).load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            screensError = describeLoadFailure(e)
        }
    }
    val today = LocalDate.now()
    val summaries = answers?.let { rows -> defs.summaries(rows.map { InstrumentAnswer(it.instrument, it.date, it.score, it.band, it.flags) }, today) }
    LaunchedEffect(reload, cacheVersion) {
        try {
            val loaded = ExposuresRepository(SupabaseClientProvider.client).load()
            data = loaded
            error = null
            catching {
                val rows = InstrumentsRepository(SupabaseClientProvider.client).loadResponses()
                val risk = catching { RiskScreensRepository(SupabaseClientProvider.client).load(context) }.getOrNull()
                val prev = com.bioscan.fieldterminal.data.MeViewSources.preventionSections(context, SupabaseClientProvider.client, risk)
                MeViewPublisher.publish(SupabaseClientProvider.client, exposuresMeView(loaded, defs.summaries(rows.map { InstrumentAnswer(it.instrument, it.date, it.score, it.band, it.flags) }, LocalDate.now()), risk, prev))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = describeLoadFailure(e)
        }
    }

    Column(Modifier.padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        error?.let {
            Text("Couldn't load: $it", style = FTType.BodySmall, color = FT.Bad)
            FTButton("RETRY") { reload++ }
        }
        when {
            summaries != null -> QuestionnaireCards(summaries, today, onTake = { taking = it }, onOpen = { questionnaireHistory = it })
            answersError != null -> Text("Couldn't load the questionnaires: $answersError", style = FTType.BodySmall, color = FT.Bad)
        }
        val checklistStates = prevention?.let { checklistDefs.states(it.checklist, today) }
        val preventiveStatuses = prevention?.let { p -> preventiveDefs.statuses(p.records.map { it.toDomain() }, preventiveContext(screens, preventiveDefs), today) }
        prevention?.let { p ->
            preventiveStatuses?.let { PreventiveCard(it, onRecord = { s -> recording = s }) }
            SubstancesCard(p, screens?.inputs?.background?.smoking)
            HeatCard(p, highUvSessions = data?.summary?.sessionsHighUv)
            checklistStates?.let { HomeWaterCard(it, onEdit = { editingChecklist = true }) }
        }
        when {
            screens != null -> RiskScreenCards(screens!!, onEditBackground = { editingBackground = true })
            screensError != null -> Text("Couldn't work out the risk screens: $screensError", style = FTType.BodySmall, color = FT.Bad)
        }
        val d = data
        when {
            d == null && error == null -> Text("Loading…", style = FTType.BodySmall, color = FT.TextSecondary)
            d != null -> ExposuresContent(
                d, onOpen = { history = it }, started = improvements,
                onStart = { i -> scope.launchSafe(onError = { e -> error = describeLoadFailure(e) }) { ImprovementsRepository(SupabaseClientProvider.client).start(i); reload++ } },
                onStop = { id -> scope.launchSafe(onError = { e -> error = describeLoadFailure(e) }) { ImprovementsRepository(SupabaseClientProvider.client).stop(id); reload++ } },
            )
        }
    }

    history?.let { m ->
        val series = data?.series(m.pick).orEmpty()
        MetricHistoryOverlay(HistorySpec(m.label, series, m.unit, { fmt(it, m.decimals) }, m.color, betterHigher = false, info = m.guide)) { history = null }
    }
    questionnaireHistory?.let { q ->
        val inst = q.instrument
        MetricHistoryOverlay(
            HistorySpec("${inst.name} (${inst.short})", q.series, inst.unit, { fmtScore(it) }, instrumentInk(inst.id), betterHigher = inst.better == "higher", info = inst.source),
        ) { questionnaireHistory = null }
    }
    taking?.let { id -> QuestionnaireSheet(id, onDismiss = { taking = null }, onSaved = { reload++ }) }
    if (editingBackground) HealthBackgroundSheet(onDismiss = { editingBackground = false }, onSaved = { reload++ })
    if (editingChecklist) prevention?.let { p ->
        HomeChecklistSheet(checklistDefs.states(p.checklist, LocalDate.now()), onDismiss = { editingChecklist = false }, onSaved = { reload++ })
    }
    recording?.let { r -> PreventiveRecordSheet(r, onDismiss = { recording = null }, onSaved = { reload++ }) }
}

@Composable
private fun ExposuresContent(
    d: ExposuresData,
    onOpen: (AirMetric) -> Unit,
    started: List<StartedImprovement> = emptyList(),
    onStart: (AirImprovement) -> Unit = {},
    onStop: (String) -> Unit = {},
) {
    val s = d.summary
    FTCard(title = "AIR · LAST 30 DAYS", info = AIR_GUIDELINES) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s == null || s.daysWithData == 0) {
                Text(
                    "No air days yet. They are worked out each night from your places (Setup › Data capture › Locations), the " +
                        "outdoor air there and, at home, the Home Terminal sensors.",
                    style = FTType.BodySmall, color = FT.TextSecondary,
                )
            } else {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.pm25Mean?.let { fmt(it, 1) } ?: "—", style = FTType.MetricMedium, color = FT.Metric.Pm25)
                    Text("µg/m³ PM2.5, mean breathed", style = FTType.Caption, color = FT.TextSecondary)
                }
                toneColor(s.states["pm25"])?.let { c ->
                    val word = when (s.states["pm25"]) { "ok" -> "Within the WHO annual guideline (5)"; "warn" -> "Above the annual guideline (5), below the daily one (15)"; else -> "Above the WHO daily guideline (15)" }
                    Text(word, style = FTType.CaptionStrong, color = c)
                }
                Text(
                    "${s.pm25DaysOver} of ${s.fullDays} full days above 15 µg/m³ · NO₂ above 25 on ${s.no2DaysOver} days · ozone above 100 on ${s.o3DaysOver} days",
                    style = FTType.Caption, color = FT.TextSecondary,
                )
                val where = s.doseHomeShare?.let { "${(it * 100).toInt()} % of the dose at home" }
                val how = if (s.indoorSensor) "measured indoors" else "home estimated from the outdoor level (no indoor PM2.5 sensor)"
                Text(listOfNotNull(where, how, "${s.daysWithData} days with data").joinToString(" · "), style = FTType.Caption, color = FT.TextSecondary)
            }
        }
    }

    FTCard(title = "BY METRIC", info = "Each line is the mean over the last 30 days with data. Tap one for its daily history.") {
        Column {
            METRICS.forEach { m ->
                val recent = d.days.takeLast(30).mapNotNull(m.pick)
                if (recent.isNotEmpty()) MetricLine(m, recent.average(), recent.size, onOpen)
            }
            if (d.days.isEmpty()) Text("Nothing yet.", style = FTType.Caption, color = FT.TextSecondary)
        }
    }

    s?.improvements?.takeIf { it.isNotEmpty() }?.let { list ->
        FTCard(title = "WAYS TO IMPROVE", info = "Ranked by how much of your dose each could lower. Each appears only when your own numbers call for it.") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                list.forEach { i ->
                    val running = started.firstOrNull { it.improvementId == i.id && it.state != "stopped" }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(i.title, style = FTType.RowTitle, color = FT.TextPrimary)
                        Text(i.why, style = FTType.BodySmall, color = FT.TextSecondary)
                        Text(i.evidence, style = FTType.Caption, color = FT.TextMuted)
                        Text("EFFORT ${i.effort.uppercase()} · LOWERS ${i.lowers.joinToString(", ") { it.uppercase() }}", style = FTType.MonoCaption, color = FT.TextSecondary)
                        // Risk profile R5: started as a Lab intervention, then read back against the air days.
                        if (running?.started != null) {
                            Text("STARTED ${running.started} · READ ON ${running.evaluateOn ?: "-"} · ON LAB WITH YOUR INTERVENTIONS", style = FTType.MonoCaption, color = FT.TextPrimary)
                            beforeAfter(airDayValues(d), running.started, metricsFor(i.lowers), LocalDate.now()).forEach { b ->
                                Text(
                                    if (b.readable) "${b.metric.label}: ${fmt(b.before!!, 1)} before → ${fmt(b.after!!, 1)} since${b.metric.unit.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""} (${b.beforeDays} and ${b.afterDays} days)"
                                    else "${b.metric.label}: ${b.afterDays} of ${IMPROVEMENT_MIN_DAYS} days since the start so far",
                                    style = FTType.Caption, color = if (b.readable && (b.change ?: 0.0) < 0) FT.Ok else FT.TextSecondary,
                                )
                            }
                            Text("Before and after, not a trial: the season and the weather move the air too.", style = FTType.Caption, color = FT.TextMuted)
                            FTButton("STOP", emphasis = FTButtonEmphasis.Tertiary) { onStop(running.interventionId) }
                        } else {
                            FTButton("START", emphasis = FTButtonEmphasis.Primary) { onStart(i) }
                        }
                    }
                }
            }
        }
    }

    val sessions = d.days.flatMap { it.sessions }.takeLast(8).reversed()
    if (sessions.isNotEmpty()) {
        FTCard(title = "OUTDOOR SESSIONS", info = "The outdoor air during each session where it was. Breathing is several times higher while training, so these hours weigh more.") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                sessions.forEach { x ->
                    val poor = (x.pm25 ?: 0.0) > 15 || (x.o3 ?: 0.0) > 100
                    Row(Modifier.fillMaxWidth()) {
                        Text("${x.start.take(10)} ${x.start.drop(11).take(5)} · ${x.type} ${x.minutes} min", style = FTType.Caption, color = FT.TextPrimary, modifier = Modifier.weight(1f))
                        Text(
                            listOfNotNull(x.pm25?.let { "PM2.5 ${fmt(it, 0)}" }, x.o3?.let { "O₃ ${fmt(it, 0)}" }, x.uv?.let { "UV ${fmt(it, 0)}" }).joinToString(" · "),
                            style = FTType.MonoCaption, color = if (poor) FT.Warn else FT.TextSecondary,
                        )
                    }
                }
            }
        }
    }

    s?.missing?.takeIf { it.isNotEmpty() }?.let {
        Text("Not read last night: ${it.joinToString(", ")}.", style = FTType.Caption, color = FT.TextMuted)
    }
    d.summaryAsOf?.let { Text("Worked out for ${LocalDate.parse(it)}.", style = FTType.Caption, color = FT.TextMuted) }
}

@Composable
private fun MetricLine(m: AirMetric, mean: Double, days: Int, onOpen: (AirMetric) -> Unit) {
    Row(
        Modifier.fillMaxWidth().ftClickable(onClickLabel = "Open its history") { onOpen(m) }.padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).background(m.color, RoundedCornerShape(2.dp)))
        Column(Modifier.weight(1f)) {
            Text(m.label.uppercase(), style = FTType.LabelCaps, color = FT.TextPrimary, maxLines = 1)
            Text("${m.guide} · $days DAYS", style = FTType.MonoCaption, color = FT.TextSecondary)
        }
        Text(fmt(mean, m.decimals) + if (m.unit.isNotEmpty()) " ${m.unit}" else "", style = FTType.Telemetry, color = m.color)
        FTChevron(tint = FT.TextSecondary)
    }
}

/** The air days as the before/after reads them (risk profile R5). */
internal fun airDayValues(d: ExposuresData): List<com.bioscan.fieldterminal.domain.risk.AirDayValues> = d.days.mapNotNull { r ->
    val date = runCatching { LocalDate.parse(r.date) }.getOrNull() ?: return@mapNotNull null
    com.bioscan.fieldterminal.domain.risk.AirDayValues(date, buildMap {
        r.pm25?.let { put("pm25", it) }; r.pm25Indoor?.let { put("pm25_indoor", it) }; r.no2?.let { put("no2", it) }
        r.o3?.let { put("o3", it) }; r.co2Night?.let { put("co2", it) }; r.uvMax?.let { put("uv", it) }
    })
}
