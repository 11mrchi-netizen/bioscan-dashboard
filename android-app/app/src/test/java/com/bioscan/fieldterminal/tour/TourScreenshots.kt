package com.bioscan.fieldterminal.tour

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.bioscan.fieldterminal.data.ScoresView
import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.data.model.StoolAnalysisRow
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.data.model.WellbeingAnalysisRow
import com.bioscan.fieldterminal.domain.DailyNutrition
import com.bioscan.fieldterminal.domain.EveningCheckin
import com.bioscan.fieldterminal.domain.score.ChargeStatus
import com.bioscan.fieldterminal.domain.score.DomainKey
import com.bioscan.fieldterminal.domain.score.FuelTargets
import com.bioscan.fieldterminal.domain.score.QUALITY_HISTORY_DAYS
import com.bioscan.fieldterminal.domain.score.ScoreEngine
import com.bioscan.fieldterminal.domain.score.ScoreKeys
import com.bioscan.fieldterminal.domain.score.ScoreSources
import com.bioscan.fieldterminal.domain.score.assembleScoreSeries
import com.bioscan.fieldterminal.domain.score.fitChargeWeights
import com.bioscan.fieldterminal.domain.score.history
import com.bioscan.fieldterminal.domain.score.scoreQuality
import com.bioscan.fieldterminal.domain.stress.PhysiologicalStressReading
import com.bioscan.fieldterminal.domain.stress.StressDailySummary
import com.bioscan.fieldterminal.domain.stress.StressDay
import com.bioscan.fieldterminal.ui.screens.today.ChargeOverlay
import com.bioscan.fieldterminal.ui.screens.today.DomainOverlay
import com.bioscan.fieldterminal.ui.screens.today.FullPicture
import com.bioscan.fieldterminal.ui.screens.today.InputOverlay
import com.bioscan.fieldterminal.ui.screens.today.LocalInlineOverlays
import com.bioscan.fieldterminal.ui.screens.today.PictureOverlay
import com.bioscan.fieldterminal.ui.screens.today.PlanAction
import com.bioscan.fieldterminal.ui.screens.today.PlanRow
import com.bioscan.fieldterminal.ui.screens.today.TodayContent
import com.bioscan.fieldterminal.ui.theme.FieldTerminalTheme
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Random
import kotlin.math.exp

// Screenshot tour (opt-in): renders the scoring system's screens on the JVM with eight months of synthetic data run
// through the real pipeline (assembly, engine, quality, weights), and saves a PNG per screen for review. Skipped unless
// FT_SCREENSHOTS_DIR names the folder to write to:
//   FT_SCREENSHOTS_DIR=/tmp/tour ./gradlew testDebugUnitTest --tests 'com.bioscan.fieldterminal.tour.*'
// (set ROBOLECTRIC_REPO_URL to a Maven mirror if Central rate-limits the Android runtime download).

private val ZONE = ZoneOffset.UTC
private val TODAY = LocalDate.of(2026, 11, 3)
private val OUT: String? = System.getenv("FT_SCREENSHOTS_DIR")

private fun person(): ScoresView {
    val r = Random(42)
    val days = 240
    val first = TODAY.minusDays(days - 1L)
    val wearable = mutableListOf<WearableAnalysisRow>()
    val sleep = mutableListOf<SleepAnalysisRow>()
    val wellbeing = mutableListOf<WellbeingAnalysisRow>()
    val loads = mutableListOf<Pair<LocalDate, Double>>()
    val nutrition = mutableListOf<DailyNutrition>()
    val hydration = HashMap<LocalDate, Double>()
    val checkins = mutableListOf<EveningCheckin>()
    val stool = mutableListOf<StoolAnalysisRow>()
    val stress = mutableListOf<StressDay>()
    val weekly = listOf(60.0, 95.0, 0.0, 85.0, 30.0, 190.0, 40.0) // Mon..Sun
    for (i in 0 until days) {
        val d = first.plusDays(i.toLong())
        val last = d == TODAY
        wearable += WearableAnalysisRow(d.toString(), hrv = if (last) 59.0 else 50.0 * exp(0.08 * r.nextGaussian()), rhr = if (last) 47.0 else 50.0 + 1.5 * r.nextGaussian())
        val hours = if (last) 8.1 else 7.2 + 0.5 * r.nextGaussian()
        val bed = d.minusDays(1).atTime(23, 0).plusMinutes((20 * r.nextGaussian()).toLong()).toInstant(ZONE)
        sleep += SleepAnalysisRow(
            d.toString(), hours = hours, bedtime = bed.toString(), wakeTime = bed.plusSeconds((hours * 3600).toLong()).toString(),
            respiratoryRate = 14.2 + 0.3 * r.nextGaussian(), deepMin = if (last) 95.0 else 80.0 + 10 * r.nextGaussian(), remMin = 90.0 + 10 * r.nextGaussian(),
        )
        if (i >= days - 60) wellbeing += WellbeingAnalysisRow(d.toString(), energy = 3 + r.nextInt(2), mood = 3 + r.nextInt(2), stress = 2 + r.nextInt(2), soreness = 1 + r.nextInt(3))
        weekly[d.dayOfWeek.value - 1].takeIf { it > 0 && d.isBefore(TODAY) }?.let { loads += d to it * (0.85 + 0.3 * r.nextDouble()) }
        if (d.isBefore(TODAY)) {
            nutrition += DailyNutrition(d.toString(), calories = 2600 + 200 * r.nextGaussian(), proteinG = 150 + 20 * r.nextGaussian(), fatG = 80.0, carbsG = 320 + 40 * r.nextGaussian(), mealCount = 4)
            hydration[d] = 3000 + 300 * r.nextGaussian()
            val minute = 7 * 60 + 50 + (25 * r.nextGaussian()).toInt()
            val type = listOf(4, 4, 4, 3, 5, 4, 6, 2, 4, 4)[r.nextInt(10)]
            stool += StoolAnalysisRow(d.atTime(LocalTime.of(minute / 60, minute % 60)).toInstant(ZONE).toString(), type)
            if (r.nextDouble() < 0.25) stool += StoolAnalysisRow(d.atTime(18, 30).toInstant(ZONE).toString(), 5)
            if (i >= days - 40) checkins += EveningCheckin(d, drinks = r.nextInt(2), caffeineAfter14 = false, lateMeal = false, stress = 2 + r.nextInt(2), lateScreen = r.nextDouble() < 0.2, gutComfort = 3 + r.nextInt(2), gutSymptoms = emptyList())
            if (i >= days - 60) {
                val samples = (7..22).map { h -> PhysiologicalStressReading(d.atTime(h, 0).toInstant(ZONE), (25 + 15 * r.nextGaussian() + if (h in 14..16) 20 else 0).toInt().coerceIn(1, 99)) }
                stress += StressDay(d, samples, StressDailySummary(30, 80, 5, 35 + r.nextInt(10), 40, 15 + r.nextInt(8), 5 + r.nextInt(4)))
            }
        }
    }
    val sources = ScoreSources(
        wearable = wearable, sleep = sleep, wellbeing = wellbeing, sessionLoads = loads, nutrition = nutrition,
        hydrationMl = hydration, targets = { FuelTargets(2650.0, 150.0, 330.0, 3000.0) }, checkins = checkins,
        stool = stool, stressDays = stress.sortedByDescending { it.date }, zone = ZONE,
    )
    val series = assembleScoreSeries(sources, TODAY)
    val defaultEngine = ScoreEngine(series)
    val historyFrom = TODAY.minusDays(QUALITY_HISTORY_DAYS - 1L)
    val defaultHistory = defaultEngine.history(historyFrom, TODAY)
    val weights = fitChargeWeights(defaultHistory.chargeInputs, series[ScoreKeys.HRV].orEmpty(), TODAY)
    val engine = if (weights.personal) ScoreEngine(series, weights = weights.weights) else defaultEngine
    val history = if (weights.personal) engine.history(historyFrom, TODAY) else defaultHistory
    val from = TODAY.minusDays(29)
    return ScoresView(
        day = TODAY, scores = engine.score(TODAY), status = ChargeStatus.FINAL,
        chargeStrip = engine.chargeStrip(from, TODAY),
        domainStrips = DomainKey.entries.associateWith { engine.domainStrip(it, from, TODAY) },
        series = series, quality = scoreQuality(history, series, emptyMap(), weights), weights = weights,
        stool = stool, lastSleep = sleep.last(), stressDays = sources.stressDays,
    )
}

private val VIEW by lazy { person() }

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h2400dp-xhdpi", application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TourScreenshots {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, content: @Composable () -> Unit) {
        val out = OUT
        org.junit.Assume.assumeTrue("set FT_SCREENSHOTS_DIR to render the tour", out != null)
        compose.setContent {
            FieldTerminalTheme {
                CompositionLocalProvider(LocalInlineOverlays provides true) {
                    Box(Modifier.fillMaxSize().background(FT.Base)) { content() }
                }
            }
        }
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out!!).mkdirs()
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun t01_today() = shot("01_today") {
        val t = { h: Int, m: Int -> LocalTime.of(h, m) }
        TodayContent(
            view = VIEW,
            rows = listOf(
                PlanRow(t(7, 30), "Breakfast", "620 kcal", true, PlanAction.SoFar),
                PlanRow(t(8, 0), "Morning supplements", "3 due", true, PlanAction.SoFar),
                PlanRow(t(12, 30), "Lunch", "780 kcal", true, PlanAction.SoFar),
                PlanRow(t(16, 0), "Pre-session", "150 kcal", false, PlanAction.SoFar),
                PlanRow(t(18, 30), "Threshold run 10 km", "55 min · READY ✓", false, PlanAction.SoFar),
                PlanRow(t(20, 0), "Dinner", "850 kcal", false, PlanAction.SoFar),
                PlanRow(t(21, 0), "Night supplements", "2 due", false, PlanAction.SoFar),
                PlanRow(t(21, 30), "Close the day", null, false, PlanAction.SoFar),
            ),
            soFar = 1420.0 to 2650.0, loading = false, today = TODAY, onOpenOverlay = {}, onRow = {}, onSoFar = {},
        )
    }

    @Test fun t02_charge() = shot("02_charge") { ChargeOverlay(VIEW, onOpen = {}, onClose = {}) }
    @Test fun t03_sleep() = shot("03_sleep") { DomainOverlay(VIEW, DomainKey.SLEEP, onOpen = {}, onClose = {}) }
    @Test fun t04_recovery() = shot("04_recovery") { DomainOverlay(VIEW, DomainKey.RECOVERY, onOpen = {}, onClose = {}) }
    @Test fun t05_load() = shot("05_load") { DomainOverlay(VIEW, DomainKey.LOAD, onOpen = {}, onClose = {}) }
    @Test fun t06_fuel() = shot("06_fuel") { DomainOverlay(VIEW, DomainKey.FUEL, onOpen = {}, onClose = {}) }
    @Test fun t07_balance() = shot("07_balance") { DomainOverlay(VIEW, DomainKey.BALANCE, onOpen = {}, onClose = {}) }
    @Test fun t08_metric_hrv() = shot("08_metric_hrv") { InputOverlay(VIEW, DomainKey.RECOVERY, ScoreKeys.HRV, onBack = {}, onClose = {}) }
    @Test fun t09_stool() = shot("09_stool_pattern") { PictureOverlay(VIEW, FullPicture.DIGESTIVE_PATTERN, onBack = {}, onClose = {}) }
    @Test fun t10_timing() = shot("10_bowel_timing") { PictureOverlay(VIEW, FullPicture.BOWEL_TIMING, onBack = {}, onClose = {}) }
    @Test fun t11_stress() = shot("11_stress_day") { PictureOverlay(VIEW, FullPicture.STRESS_DAY, onBack = {}, onClose = {}) }
    @Test fun t12_metric_sleep() = shot("12_metric_sleep_hours") { InputOverlay(VIEW, DomainKey.SLEEP, ScoreKeys.SLEEP_HOURS, onBack = {}, onClose = {}) }
}
