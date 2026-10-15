package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.EveningCheckinRepository
import com.bioscan.fieldterminal.data.EvidenceRows
import com.bioscan.fieldterminal.data.NutritionOverview
import com.bioscan.fieldterminal.data.NutritionRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.ZeppStressRepository
import com.bioscan.fieldterminal.data.buildTrainingSessionLoads
import com.bioscan.fieldterminal.data.model.ExpectedBandRow
import com.bioscan.fieldterminal.data.model.RelationshipResultRow
import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.data.model.TrainingLoadSessionRow
import com.bioscan.fieldterminal.data.model.TypicalityResultRow
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.data.model.WellbeingAnalysisRow
import com.bioscan.fieldterminal.domain.DisplayValue
import com.bioscan.fieldterminal.domain.EveningCheckin
import com.bioscan.fieldterminal.domain.SeriesBreakStarts
import com.bioscan.fieldterminal.domain.SleepNight
import com.bioscan.fieldterminal.domain.checkinDateFor
import com.bioscan.fieldterminal.domain.computeDynamicRecovery
import com.bioscan.fieldterminal.domain.computeSleepIndex
import com.bioscan.fieldterminal.domain.computeTrainingReadiness
import com.bioscan.fieldterminal.domain.evaluateHrv
import com.bioscan.fieldterminal.domain.evaluateRhr
import com.bioscan.fieldterminal.domain.evaluateSubjective
import com.bioscan.fieldterminal.domain.evaluateTrainingLoad
import com.bioscan.fieldterminal.domain.isComplete
import com.bioscan.fieldterminal.domain.lastEveningDate
import com.bioscan.fieldterminal.domain.contributors
import com.bioscan.fieldterminal.domain.evidenceBoard
import com.bioscan.fieldterminal.domain.stress.StressDay
import com.bioscan.fieldterminal.domain.stress.analyzeStressDay
import com.bioscan.fieldterminal.domain.summary
import com.bioscan.fieldterminal.domain.trainingLoadStateWord
import com.bioscan.fieldterminal.domain.typicalityView
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTCardEmphasis
import com.bioscan.fieldterminal.ui.components.FTMetricValue
import com.bioscan.fieldterminal.ui.components.HydrationSourceDonutChart
import com.bioscan.fieldterminal.ui.components.LoadErrorCard
import com.bioscan.fieldterminal.ui.components.MacroDonutChart
import com.bioscan.fieldterminal.ui.components.TileHeader
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.components.metricStateLabel
import com.bioscan.fieldterminal.ui.components.parallel
import com.bioscan.fieldterminal.ui.components.rememberLoadGuard
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.util.Perf
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToInt

// The Today page (UX review P6; was Daily Readiness), reached from TODAY › on the Status hero. One vocabulary for "how am I
// today": THIS MORNING leads (analysis review phase C: each core metric against the band expected for you, answer first,
// with what last evening brought), then TODAY'S RECOVERY, READY FOR (it is built on recovery), today's fuel, then the
// evidence board (phase D), what came before off mornings, and typicality. Training load and HRV / resting HR are linked (Training › Load, Health › Cardio) instead of repeated here.
// Every section reuses the exact domain chain + render card its own tile screen already uses (DynamicRecoveryCard in
// HeartTileScreen.kt, TrainingReadinessCard in TrainingTileScreen.kt, the hydration/macro totals in
// NutritionHydrationScreen.kt) -- this screen just composes them, loading its own data independently per this app's
// established per-screen-loader convention.
@Composable
fun TodayScreen(
    onBack: () -> Unit,
    onOpenTrainingLoad: () -> Unit,
    onOpenCardio: () -> Unit,
    // Analysis review phase B: the 22:25 reminder lands here with the evening check-in open.
    openCheckIn: Boolean = false,
    onCheckInHandled: () -> Unit = {},
) {
    var sessions by remember { mutableStateOf<List<TrainingLoadSessionRow>?>(null) }
    var wearable by remember { mutableStateOf<List<WearableAnalysisRow>?>(null) }
    var sleep by remember { mutableStateOf<List<SleepAnalysisRow>?>(null) }
    var wellbeing by remember { mutableStateOf<List<WellbeingAnalysisRow>?>(null) }
    var stressDays by remember { mutableStateOf<List<StressDay>>(emptyList()) }
    var nutrition by remember { mutableStateOf<NutritionOverview?>(null) }
    // Analysis Layer 3 / R1: start of each metric's current regime (last confirmed series break); NONE until loaded.
    var breakStarts by remember { mutableStateOf(SeriesBreakStarts.NONE) }
    // Analysis Layer 3 / Phase 3: published confirmatory relationships (read-only).
    var relationships by remember { mutableStateOf<List<RelationshipResultRow>>(emptyList()) }
    // Analysis Layer 3 / Phase 4: "is today typical for you", a quiet drill-down (not part of Today's Recovery).
    var typicality by remember { mutableStateOf<TypicalityResultRow?>(null) }
    // Analysis review phase C: the published expected band per metric (empty before the first run).
    var bands by remember { mutableStateOf<Map<String, ExpectedBandRow>>(emptyMap()) }
    // Analysis review phase D: event curves, case-crossover tables and lag curves for the evidence board.
    var evidence by remember { mutableStateOf(EvidenceRows()) }
    // Analysis review phase B: last evening's check-in (null until answered or if the read fails) and the open sheet.
    var lastEvening by remember { mutableStateOf<EveningCheckin?>(null) }
    var checkInDate by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(openCheckIn) {
        if (openCheckIn) {
            checkInDate = checkinDateFor(LocalDateTime.now())
            onCheckInHandled()
        }
    }

    val guard = rememberLoadGuard()
    LaunchedEffect(guard.attempt) {
        // Perf pass: the loads are independent, so they run together (was one after another).
        Perf.timed("Today.load") {
            guard.run {
                val repo = AnalysisRepository(SupabaseClientProvider.client)
                parallel(
                    { sessions = repo.loadExerciseSessionsForTrainingLoad() },
                    { wearable = repo.loadWearableDaily() },
                    { sleep = repo.loadSleepDaily() },
                    { wellbeing = repo.loadWellbeingDaily() },
                    { stressDays = ZeppStressRepository(SupabaseClientProvider.client).loadStressDays() },
                    { nutrition = NutritionRepository(SupabaseClientProvider.client).loadOverview() },
                    { breakStarts = repo.loadSeriesBreakStarts() },
                    { relationships = repo.loadRelationships() },
                    { typicality = repo.loadTypicality() },
                    { bands = repo.loadExpectedBands() },
                    { evidence = repo.loadEvidence() },
                    // A missing table (migration not yet applied) or a failed read just leaves the card asking.
                    { lastEvening = runCatching { EveningCheckinRepository(SupabaseClientProvider.client).load(lastEveningDate(LocalDate.now())) }.getOrNull() },
                )
            }
        }
    }

    val s = sessions
    val w = wearable
    val sl = sleep
    val wb = wellbeing
    val nut = nutrition
    if (s == null || w == null || sl == null || wb == null || nut == null) {
        Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
            TileHeader(onBack = onBack, title = "TODAY", subtitle = todayContextLine())
            if (guard.error != null) {
                LoadErrorCard(guard)
            } else {
                Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = FT.Emerald)
                }
            }
        }
        return
    }

    val hrvPoints = w.mapNotNull { row -> row.hrv?.let { LocalDate.parse(row.date) to it } }
    val rhrPoints = w.mapNotNull { row -> row.rhr?.let { LocalDate.parse(row.date) to it } }
    // Perf pass: pure functions of the loaded rows, computed once per load instead of on every recomposition.
    val (trainingLoadEval, recovery) = remember(s, w, sl, wb, stressDays, breakStarts) {
        val sessionLoads = buildTrainingSessionLoads(s, w.lastOrNull()?.rhr)
        val trainingLoadEval = evaluateTrainingLoad(sessionLoads)

        val nights = sl.mapNotNull { row ->
            val bedtime = row.bedtime?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
            val wake = row.wakeTime?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
            if (bedtime != null && wake != null) SleepNight(LocalDate.parse(row.date), bedtime, wake) else null
        }
        val sleepIndexResult = computeSleepIndex(
            sl.mapNotNull { row -> row.hours?.let { LocalDate.parse(row.date) to it } },
            sl.mapNotNull { row -> row.deepMin?.let { LocalDate.parse(row.date) to it } },
            sl.mapNotNull { row -> row.respiratoryRate?.let { LocalDate.parse(row.date) to it } },
            nights,
            durationWindowStart = breakStarts.start("sleep_hours"), stageWindowStart = breakStarts.start("deep_min"),
        )
        val exerciseWindows = s.mapNotNull { session ->
            val start = runCatching { java.time.OffsetDateTime.parse(session.startTime) }.getOrNull()?.toInstant() ?: return@mapNotNull null
            val durationMin = session.durationMin ?: return@mapNotNull null
            start to start.plusSeconds((durationMin * 60).toLong())
        }
        val physiologicalStress = stressDays.firstOrNull()?.let { analyzeStressDay(it, stressDays.drop(1), exerciseWindows) }
        val recovery = computeDynamicRecovery(
            sleepIndex = sleepIndexResult,
            hrvEval = evaluateHrv(hrvPoints, windowStart = breakStarts.start("hrv")),
            rhrEval = evaluateRhr(rhrPoints, windowStart = breakStarts.start("rhr")),
            trainingLoadEval = trainingLoadEval,
            energyEval = evaluateSubjective(wb.mapNotNull { row -> row.energy?.let { LocalDate.parse(row.date) to it.toDouble() } }),
            stressEval = evaluateSubjective(wb.mapNotNull { row -> row.stress?.let { LocalDate.parse(row.date) to it.toDouble() } }),
            sorenessEval = evaluateSubjective(wb.mapNotNull { row -> row.soreness?.let { LocalDate.parse(row.date) to it.toDouble() } }),
            physiologicalStress = physiologicalStress,
            morningWoodEval = evaluateSubjective(wb.mapNotNull { row -> row.morningErectionQuality?.let { LocalDate.parse(row.date) to it.toDouble() } }),
            arousalEval = evaluateSubjective(wb.mapNotNull { row -> row.arousalLevel?.let { LocalDate.parse(row.date) to it.toDouble() } }),
        )
        trainingLoadEval to recovery
    }

    val waterMl = nut.todayHydrationMl?.toDouble() ?: 0.0
    val beverageMl = nut.todayBeverageItems.mapNotNull { it.effectiveHydrationMl }.sum()
    val todayCaffeineMg = nut.todayBeverageItems.mapNotNull { it.caffeineMg }.takeIf { it.isNotEmpty() }?.sum()

    Column(modifier = Modifier.fillMaxSize().background(FT.Base).verticalScroll(rememberScrollState())) {
        TileHeader(onBack = onBack, title = "TODAY", subtitle = todayContextLine())
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // Analysis review phase C: the answer first -- each core metric this morning against what was expected.
            val bandInputs = buildMap {
                bands["hrv"]?.let { put("hrv", BandInputs(it, hrvPoints)) }
                bands["rhr"]?.let { put("rhr", BandInputs(it, rhrPoints)) }
                bands["sleep_hours"]?.let { put("sleep_hours", BandInputs(it, sl.mapNotNull { row -> row.hours?.let { v -> LocalDate.parse(row.date) to v } })) }
            }
            ThisMorningCard(
                bandInputs,
                LocalDate.now(),
                onOpenCardio = onOpenCardio,
                // Analysis review phase D: what last evening brought, with the board's evidence about it.
                contributors = contributors(lastEvening, relationships, evidence.events.map { it.payload }),
            )
            val eveningAnswered = lastEvening?.isComplete == true
            if (!eveningAnswered) LastEveningCard(lastEvening) { checkInDate = lastEveningDate(LocalDate.now()) }
            DynamicRecoveryCard(recovery, emphasis = if (bandInputs.isEmpty()) FTCardEmphasis.Hero else FTCardEmphasis.Supporting)
            TrainingReadinessCard(computeTrainingReadiness(recovery))
            // The inputs behind recovery and READY FOR, one tap away instead of repeated on this page.
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val tsb = trainingLoadEval.tsb
                LinkTile(
                    title = "TRAINING LOAD",
                    value = trainingLoadEval.tsbBand?.uppercase()?.let { band -> if (tsb != null) "$band · TSB " + "%+.0f".format(tsb) else band }
                        ?: trainingLoadStateWord(trainingLoadEval.state).let { it.word ?: metricStateLabel(it.state) },
                    accent = FT.DomainTraining,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = onOpenTrainingLoad,
                )
                LinkTile(
                    title = "CARDIO",
                    value = "HRV " + (w.lastOrNull()?.hrv?.let { "%.0f".format(it) } ?: "—") + " · RHR " + (w.lastOrNull()?.rhr?.let { "%.0f".format(it) } ?: "—"),
                    accent = FT.DomainHeart,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = onOpenCardio,
                )
            }

            // Stacked full-width, not side-by-side -- MacroDonutChart/
            // HydrationSourceDonutChart's fixed 110dp canvas + legend row is
            // sized for a full-width card (as NutritionAveragesCard already
            // uses them); a half-width column left no room for the legend
            // text, wrapping it one character per line. Fixing the layout
            // here rather than the shared components, which work correctly
            // everywhere else they're already used.
            FTCard(title = "CALORIES TODAY") {
                val today = nut.today
                if (today == null) {
                    Text("No meals logged yet.", style = FTType.BodySmall, color = FT.TextSecondary)
                } else {
                    FTMetricValue(DisplayValue(primary = today.calories.roundToInt().toString(), unit = "KCAL"))
                    MacroDonutChart(carbsG = today.carbsG, proteinG = today.proteinG, fatG = today.fatG, modifier = Modifier.padding(top = 10.dp))
                }
            }
            FTCard(title = "HYDRATION TODAY") {
                if (waterMl + beverageMl <= 0.0) {
                    Text("No hydration logged yet today.", style = FTType.BodySmall, color = FT.TextSecondary)
                } else {
                    FTMetricValue(DisplayValue(primary = "%.1f".format((waterMl + beverageMl) / 1000.0), unit = "L"))
                    HydrationSourceDonutChart(waterMl = waterMl, beverageMl = beverageMl, modifier = Modifier.padding(top = 10.dp))
                }
            }

            if (todayCaffeineMg != null) {
                FTCard(title = "CAFFEINE TODAY") {
                    FTMetricValue(DisplayValue(primary = "%.0f".format(todayCaffeineMg), unit = "MG"))
                    Text(
                        "Total from logged beverages",
                        style = FTType.Caption,
                        color = FT.TextMuted,
                    )
                }
            }

            if (eveningAnswered) LastEveningCard(lastEvening) { checkInDate = lastEveningDate(LocalDate.now()) }
            EvidenceBoardCard(evidenceBoard(relationships), evidence)
            CaseCrossoverCard(evidence.crossover)
            TypicalityCard(typicalityView(typicality))
        }
    }

    checkInDate?.let { date ->
        EveningCheckInSheet(
            date = date,
            onDismiss = { checkInDate = null },
            onSaved = { saved ->
                if (saved.date == lastEveningDate(LocalDate.now())) lastEvening = saved
                checkInDate = null
            },
        )
    }
}

// Analysis review phase B: the morning catch-up for last evening's check-in. Unanswered, it leads the page; answered,
// it sits above the patterns as a one-line summary with EDIT.
@Composable
private fun LastEveningCard(checkin: EveningCheckin?, onOpen: () -> Unit) {
    FTCard(title = "LAST EVENING") {
        if (checkin?.isComplete == true) {
            Text(checkin.summary().replaceFirstChar { it.uppercase() }, style = FTType.BodySmall, color = FT.TextPrimary)
            FTButton("EDIT", emphasis = FTButtonEmphasis.Tertiary, onClick = onOpen)
        } else {
            Text(
                "Drinks, caffeine after 14:00, a late meal: one tap each. A quiet evening then counts as a real none, which is what " +
                    "lets the app tell whether any of them goes with your next morning.",
                style = FTType.BodySmall,
                color = FT.TextSecondary,
            )
            FTButton("ANSWER", emphasis = FTButtonEmphasis.Primary, onClick = onOpen)
        }
    }
}

// A titled link to the tile page that holds the full card: section accent dot, title, the one value that matters here.
@Composable
private fun LinkTile(title: String, value: String, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(FT.RadiusModule)
    Column(
        modifier = modifier
            .clip(shape)
            .background(FT.GlassFill)
            .border(FT.BorderWidth, FT.GlassBorder, shape)
            .ftClickable(shape = shape, role = Role.Button, onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
            Text(title, style = FTType.LabelCaps, color = FT.TextPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("›", style = FTType.LabelCaps, color = FT.TextSecondary)
        }
        Text(value, style = FTType.Label, color = FT.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// "WED 14 OCT": the Today page's context line.
private fun todayContextLine(): String =
    java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH)).uppercase()
