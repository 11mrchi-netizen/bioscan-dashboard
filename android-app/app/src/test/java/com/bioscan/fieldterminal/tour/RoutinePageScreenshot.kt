package com.bioscan.fieldterminal.tour

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.routine.AppWeekTotal
import com.bioscan.fieldterminal.data.routine.RoutinePageData
import com.bioscan.fieldterminal.domain.routine.ActivityCategory
import com.bioscan.fieldterminal.domain.routine.ActivityKind
import com.bioscan.fieldterminal.domain.routine.DailyLine
import com.bioscan.fieldterminal.domain.routine.LogSource
import com.bioscan.fieldterminal.domain.routine.LogStatus
import com.bioscan.fieldterminal.domain.routine.RibbonInterval
import com.bioscan.fieldterminal.domain.routine.RibbonKind
import com.bioscan.fieldterminal.domain.routine.RoutineActivity
import com.bioscan.fieldterminal.domain.routine.RoutineLog
import com.bioscan.fieldterminal.domain.routine.TimeSource
import com.bioscan.fieldterminal.domain.routine.ribbonDay
import com.bioscan.fieldterminal.ui.screens.me.RoutinePageContent
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
import java.time.ZoneId
import kotlin.random.Random

// ME › ROUTINE drawn from a synthetic two months (opt-in, like TourScreenshots: FT_SCREENSHOTS_DIR names the folder).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h5200dp-xhdpi", application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoutinePageScreenshot {
    @get:Rule val compose = createComposeRule()

    private val zone = ZoneId.of("Asia/Taipei")
    private val today = LocalDate.of(2026, 11, 10)

    private fun data(): RoutinePageData {
        val r = Random(7)
        fun ms(d: LocalDate, h: Int, m: Int = 0) = d.atStartOfDay(zone).toInstant().toEpochMilli() + (h * 60L + m) * 60_000L
        val iv = mutableListOf<RibbonInterval>()
        for (i in 0..29) {
            val d = today.minusDays(i.toLong())
            val weekend = d.dayOfWeek.value >= 6
            val bed = 23 * 60 + r.nextInt(-40, 70)
            iv += RibbonInterval(ms(d.minusDays(1), 0) + bed * 60_000L, ms(d, 7, r.nextInt(0, 40)), RibbonKind.ASLEEP)
            iv += RibbonInterval(ms(d, 0), ms(d, 24), RibbonKind.HOME)
            if (!weekend) {
                iv += RibbonInterval(ms(d, 8, 30), ms(d, 9, 15), RibbonKind.MOVE)
                iv += RibbonInterval(ms(d, 9, 15), ms(d, 18), RibbonKind.WORK)
                iv += RibbonInterval(ms(d, 18), ms(d, 18, 50), RibbonKind.MOVE)
                if (r.nextBoolean()) iv += RibbonInterval(ms(d, 12, 15), ms(d, 13, 10), RibbonKind.OUT)
            } else {
                iv += RibbonInterval(ms(d, 11), ms(d, 15), RibbonKind.OUT)
            }
            if (i % 2 == 0) iv += RibbonInterval(ms(d, 6, 0), ms(d, 7, 10), RibbonKind.TRAINING)
            if (r.nextInt(3) > 0) iv += RibbonInterval(ms(d, 21, 0), ms(d, 22, r.nextInt(0, 50)), RibbonKind.LOGGED)
            if (i in 12..13) iv.removeAll { it.startMs >= ms(d, 0) && it.startMs < ms(d, 24) && it.kind != RibbonKind.ASLEEP } // the collector off
        }
        val ribbons = (0 until 28).map { ribbonDay(today.minusDays(it.toLong()), zone, iv, ms(today, 15)) }
        fun line(key: String, label: String, src: TimeSource, days: Int, f: (Int) -> Double) =
            DailyLine(key, label, src, (1..days).associate { today.minusDays(it.toLong()) to f(it).coerceAtLeast(0.0) })
        val time = listOf(
            line("activity:1", "TV", TimeSource.LOGGED, 40) { 50 + r.nextInt(-30, 40).toDouble() },
            line("activity:2", "Reading", TimeSource.LOGGED, 40) { if (it < 28) 30.0 + r.nextInt(0, 20) else 12.0 },
            line("session:run", "Training: run", TimeSource.WATCH, 56) { if (it % 2 == 0) 70.0 else 0.0 },
            line("trip:walking", "Walking outside", TimeSource.LOCATION, 21) { 25.0 + r.nextInt(0, 30) },
            line("commute", "Commuting", TimeSource.LOCATION, 21) { if (today.minusDays(it.toLong()).dayOfWeek.value < 6) 90.0 + r.nextInt(-10, 15) else 0.0 },
            line("outdoor_daylight", "Outdoors in daylight", TimeSource.LOCATION, 21) { 40.0 + r.nextInt(0, 40) },
            line("phone:video", "Video on the phone", TimeSource.PHONE, 15) { 120.0 + r.nextInt(-60, 60) },
            line("sleep", "Asleep", TimeSource.SLEEP, 60) { 7 * 60.0 + r.nextInt(-50, 40) },
        )
        val places = listOf(
            line("place:home", "Home", TimeSource.LOCATION, 21) { 14.0 + r.nextDouble() * 2 },
            line("place:work", "Work", TimeSource.LOCATION, 21) { if (today.minusDays(it.toLong()).dayOfWeek.value < 6) 8.5 else 0.0 },
            line("place:food", "Food", TimeSource.LOCATION, 21) { r.nextDouble() * 1.5 },
            line("place:unlabelled", "Unnamed places", TimeSource.LOCATION, 21) { r.nextDouble() * 0.8 },
        )
        val phone = listOf(
            line("phone:screen", "Screen time", TimeSource.PHONE, 15) { 420.0 + r.nextInt(-90, 120) },
            line("phone:bed", "In the hour before sleep", TimeSource.PHONE, 15) { 35.0 + r.nextInt(-20, 20) },
            line("phone:night", "Unlocks at night", TimeSource.PHONE, 15) { r.nextInt(0, 4).toDouble() },
            line("phone:social", "Social apps", TimeSource.PHONE, 15) { 110.0 + r.nextInt(-40, 50) },
        )
        val regularity = listOf(
            line("reg:bedtime", "Bedtime spread", TimeSource.SLEEP, 60) { 30.0 + r.nextInt(0, 25) },
            line("reg:wake", "Wake time spread", TimeSource.SLEEP, 60) { 15.0 + r.nextInt(0, 15) },
            line("reg:similarity", "Like the same weekday", TimeSource.LOCATION, 14) { 70.0 + r.nextInt(-15, 15) },
            line("reg:places", "Places in a day", TimeSource.LOCATION, 21) { 3.0 + r.nextInt(0, 3) },
        )
        val med = RoutineActivity(10, "Meditation", ActivityKind.PRACTICE, ActivityCategory.MIND, 10, (1..7).toSet(), LocalTime.of(7, 0), remind = true)
        val sauna = RoutineActivity(11, "Sauna", ActivityKind.PRACTICE, ActivityCategory.RECOVERY, 20, setOf(2, 4), LocalTime.of(19, 0))
        val logs = (1..80).mapNotNull { i ->
            val d = today.minusDays(i.toLong())
            if (r.nextInt(10) < 8) RoutineLog(i.toLong(), 10, d, null, null, 10.0, LogStatus.DONE, LogSource.REMINDER) else null
        } + (1..80).mapNotNull { i ->
            val d = today.minusDays(i.toLong())
            if (sauna.days!!.contains(d.dayOfWeek.value) && r.nextInt(3) > 0) RoutineLog(1000L + i, 11, d, null, null, 20.0, LogStatus.DONE, LogSource.MANUAL) else null
        }
        val apps = listOf(
            AppWeekTotal("com.youtube", "YouTube", "video", 2600.0, 120),
            AppWeekTotal("com.instagram", "Instagram", "social", 2100.0, 400),
            AppWeekTotal("com.line", "LINE", "social", 1300.0, 600),
        )
        return RoutinePageData(today, ribbons, time, places, phone, regularity, listOf(med, sauna), logs, apps)
    }

    @Test
    fun routinePage() {
        val out = System.getenv("FT_SCREENSHOTS_DIR")
        org.junit.Assume.assumeTrue("set FT_SCREENSHOTS_DIR to render", out != null)
        val d = data()
        compose.setContent {
            FieldTerminalTheme {
                Box(Modifier.fillMaxSize().background(FT.Base).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp)) {
                    RoutinePageContent(d, onOpenDay = {}, onOpenHistory = {}, onOpenCalendar = {}, placesEditor = false)
                }
            }
        }
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out!!).mkdirs()
        File(out, "routine_page.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
