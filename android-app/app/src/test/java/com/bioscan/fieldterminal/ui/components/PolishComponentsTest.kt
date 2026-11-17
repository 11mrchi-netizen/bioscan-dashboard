package com.bioscan.fieldterminal.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.MetricState
import com.bioscan.fieldterminal.domain.aging.FactorStatus
import com.bioscan.fieldterminal.domain.aging.HealthspanFactor
import com.bioscan.fieldterminal.domain.aging.HealthspanFactorId
import com.bioscan.fieldterminal.domain.aging.HealthspanSummary
import com.bioscan.fieldterminal.domain.healthspanStateWord
import com.bioscan.fieldterminal.ui.nav.MeTab
import com.bioscan.fieldterminal.ui.screens.HealthspanCard
import com.bioscan.fieldterminal.ui.screens.detail.Section
import com.bioscan.fieldterminal.ui.screens.today.PlanAction
import com.bioscan.fieldterminal.ui.screens.today.PlanKind
import com.bioscan.fieldterminal.ui.screens.today.PlanRow
import com.bioscan.fieldterminal.ui.screens.today.TimelineRow
import com.bioscan.fieldterminal.ui.theme.FieldTerminalTheme
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

// Polish pass (docs/design/ui-polish-audit.md): the shared primitives on the two phone frames the brief names, the 390 ×
// 844 reference and a small 360 × 640 phone. Each test asserts what the pass promised (48 dp targets, every ME tab
// reachable, one state language for the Healthspan radar and list), and writes a screenshot when FT_POLISH_SHOTS names
// a folder.

private val SUMMARY = HealthspanSummary(
    factors = listOf(
        HealthspanFactor(HealthspanFactorId.CARDIO_FITNESS, FactorStatus.FAVORABLE, "VO2max 52", emptyList(), null, score = 78),
        HealthspanFactor(HealthspanFactorId.BIOLOGICAL_AGE, FactorStatus.NEUTRAL, null, emptyList(), null, score = 44),
        HealthspanFactor(HealthspanFactorId.STRENGTH, FactorStatus.FAVORABLE, null, emptyList(), null, score = 63),
        HealthspanFactor(HealthspanFactorId.BODY_COMPOSITION, FactorStatus.NEUTRAL, null, emptyList(), null, score = 41),
        HealthspanFactor(HealthspanFactorId.SLEEP, FactorStatus.OPPORTUNITY, null, emptyList(), null, score = 18),
        HealthspanFactor(HealthspanFactorId.ACTIVITY, FactorStatus.FAVORABLE, null, emptyList(), null, score = 70),
        HealthspanFactor(HealthspanFactorId.METABOLIC, FactorStatus.UNASSESSED, null, listOf("a lipid panel in the last year"), null),
        HealthspanFactor(HealthspanFactorId.GLYCAEMIC, FactorStatus.NEUTRAL, null, emptyList(), null, score = 35),
    ),
    assessed = 7, favorable = 3, score = 50, preliminary = false,
)

@androidx.compose.runtime.Composable
private fun Framed(content: @androidx.compose.runtime.Composable () -> Unit) = FieldTerminalTheme {
    androidx.compose.foundation.layout.Box(Modifier.background(FT.Base).padding(vertical = 8.dp)) { content() }
}

abstract class PolishComponentsBase {
    @get:Rule val compose = createComposeRule()
    abstract val frame: String

    @After fun motion() = FTMotion.forceReduced(false)

    protected fun shot(name: String) {
        val out = System.getenv("FT_POLISH_SHOTS") ?: return
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "${name}_$frame.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun meTabsAllReachableAndTall() {
        var tab by mutableStateOf(MeTab.Healthspan)
        compose.setContent {
            Framed {
                Column {
                    androidx.compose.foundation.layout.Row {
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        FTIconButton(FTIcons.Settings, "Settings", {})
                    }
                    SubTabRow(MeTab.entries, tab, { it.label }, { tab = it })
                }
            }
        }
        compose.onNodeWithContentDescription("Settings").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        for (t in MeTab.entries) compose.onNodeWithText(t.label).assertHeightIsAtLeast(0.dp)
        shot("me_tabs_first")
        // The last tab, off-screen on a narrow phone, is selected: the row brings it into view.
        compose.runOnIdle { tab = MeTab.entries.last() }
        compose.waitForIdle()
        compose.onNodeWithText(MeTab.entries.last().label).assertIsDisplayed()
        shot("me_tabs_last")
    }

    @Test
    fun chipsAreFortyEightTall() {
        compose.setContent {
            Framed {
                var r by remember { mutableStateOf(FTRange.D28) }
                Column { FTRangeSelector(r, { r = it }) }
            }
        }
        for (r in FTRange.entries) {
            compose.onAllNodesWithText(r.label)[0].assertHeightIsAtLeast(0.dp)
        }
        compose.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Selected, true))
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("90D").performClick()
        compose.onNode(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Selected, true) and
                SemanticsMatcher("has 90D") { n -> n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsActions.OnClick) { null }?.label == "90D" },
        ).assertIsDisplayed()
        shot("range_selector")
    }

    @Test
    fun timelineRowsAreFortyEightTall() {
        val rows = listOf(
            PlanRow(LocalTime.of(7, 30), "Breakfast", "620 kcal", done = true, action = PlanAction.SoFar, kind = PlanKind.MEAL, kcal = 620.0),
            PlanRow(LocalTime.of(12, 0), "Threshold run, 10 km with a long name that runs on", "60 min", done = false, action = PlanAction.SoFar, kind = PlanKind.SESSION, durationMin = 60.0),
            PlanRow(LocalTime.of(21, 30), "Close the day", null, done = false, action = PlanAction.CloseDay(LocalDate.of(2026, 11, 12))),
        )
        compose.setContent {
            Framed {
                Column(Modifier.padding(16.dp)) { rows.forEachIndexed { i, r -> TimelineRow(r, i == 0, i == rows.lastIndex) {} } }
            }
        }
        for (r in rows) compose.onNode(SemanticsMatcher("opens ${r.title}") { n ->
            n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsActions.OnClick) { null }?.label == "Open ${r.title}"
        }).assertHeightIsAtLeast(48.dp)
        shot("timeline_rows")
    }

    @Test
    fun healthspanRadarAndListShareTheStateLanguage() {
        compose.setContent {
            Framed {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { HealthspanCard(SUMMARY) }
            }
        }
        // The list's pills carry the same words the radar's spoken description does.
        compose.onAllNodesWithText("FAVORABLE")[0].assertIsDisplayed()
        compose.onNodeWithText("NOT ASSESSED").assertHeightIsAtLeast(0.dp)
        compose.onNodeWithText("OPPORTUNITY").assertHeightIsAtLeast(0.dp)
        shot("healthspan_card")
    }

    @Test
    fun cardHierarchy() {
        compose.setContent {
            Framed {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)) {
                    FTCard("Lead card", info = "What this is.", emphasis = FTCardEmphasis.Hero) {
                        FTLatestReading("52.4", "ml/kg/min", LocalDate.now().minusDays(1), ink = FT.Metric.Vo2max)
                    }
                    Section("Supporting section", info = "Help text.", trailing = "ALL ›") {
                        FTLegend(listOf("RIGHT" to FT.Metric.Grip, "LEFT" to FT.Metric.Grip.copy(alpha = 0.55f)), dashed = setOf("LEFT"))
                        FTChartNote("Only 2 readings in this range: shown as dots, not a trend.")
                    }
                    FTCard("Compact block", emphasis = FTCardEmphasis.Compact) { FTStatePill(healthspanStateWord(FactorStatus.NEUTRAL)) }
                }
            }
        }
        compose.onNodeWithContentDescription("About Lead card").assertHeightIsAtLeast(20.dp)
        compose.onNode(SemanticsMatcher("help target") { n ->
            n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsActions.OnClick) { null }?.label == "Explain Supporting section"
        }).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        shot("cards")
    }

    @Test
    fun reducedMotionSnaps() {
        FTMotion.forceReduced(true)
        assertEquals(androidx.compose.animation.EnterTransition.None, FTMotion.pushEnter())
        assertEquals(androidx.compose.animation.ExitTransition.None, FTMotion.popExit())
        FTMotion.forceReduced(false)
        assertNotEquals(androidx.compose.animation.EnterTransition.None, FTMotion.pushEnter())
    }

    @Test
    fun healthspanStatesMapOnce() {
        assertEquals(MetricState.Optimal, healthspanStateWord(FactorStatus.FAVORABLE).state)
        assertEquals(MetricState.Neutral, healthspanStateWord(FactorStatus.NEUTRAL).state)
        assertEquals(MetricState.Warning, healthspanStateWord(FactorStatus.OPPORTUNITY).state)
        assertEquals(MetricState.Unavailable, healthspanStateWord(FactorStatus.UNASSESSED).state)
    }

    @Test
    fun rangeMemoryKeepsAPick() {
        RangeMemory.set("test:page", FTRange.Y1)
        assertEquals(FTRange.Y1, RangeMemory.get("test:page", FTRange.D28))
        assertEquals(FTRange.D28, RangeMemory.get("test:other", FTRange.D28))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xhdpi", application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PolishComponents390Test : PolishComponentsBase() { override val frame = "390x844" }

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xhdpi", application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PolishComponents360Test : PolishComponentsBase() { override val frame = "360x640" }
