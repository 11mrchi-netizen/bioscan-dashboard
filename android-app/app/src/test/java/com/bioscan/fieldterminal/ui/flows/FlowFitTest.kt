package com.bioscan.fieldterminal.ui.flows

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import com.bioscan.fieldterminal.ui.components.FLOW_BODY_TAG
import com.bioscan.fieldterminal.ui.components.LocalFlowStartPage
import com.bioscan.fieldterminal.ui.components.LocalInlineFlows
import com.bioscan.fieldterminal.ui.screens.health.IllnessSheet
import com.bioscan.fieldterminal.ui.theme.FieldTerminalTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// The owner's rule for logging and building flows (ui/components/FTFlow.kt): no page needs scrolling. Each flow is opened
// on each of its pages on a small phone (360 × 640 dp) and its body must not scroll. Pages are drawn as they open, before
// any answer, so a page that grows with answers (a second chip row) keeps some room for that. Set FT_SCREENSHOTS_DIR to
// also save a PNG of every page.

private val OUT: String? = System.getenv("FT_SCREENSHOTS_DIR")

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xhdpi", application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FlowFitTest {
    @get:Rule val compose = createComposeRule()

    // The forms build their repositories on open; the Supabase client's session store needs the settings library's
    // context, which androidx.startup gives it in the app and nothing gives it here.
    @org.junit.Before fun settings() {
        androidx.startup.AppInitializer.getInstance(androidx.test.core.app.ApplicationProvider.getApplicationContext())
            .initializeComponent(com.russhwolf.settings.SettingsInitializer::class.java)
    }

    private fun fits(name: String, pages: Int, flow: @Composable () -> Unit) {
        val overflow = mutableListOf<String>()
        for (p in 0 until pages) {
            Holder.page.value = p
            if (p == 0) compose.setContent {
                FieldTerminalTheme {
                    androidx.compose.runtime.key(Holder.page.value) {
                        CompositionLocalProvider(LocalInlineFlows provides true, LocalFlowStartPage provides Holder.page.value) {
                            Box(Modifier.fillMaxSize().background(FT.Base)) { flow() }
                        }
                    }
                }
            }
            compose.waitForIdle()
            val range = compose.onNodeWithTag(FLOW_BODY_TAG).fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
            val max = range?.maxValue?.invoke() ?: 0f
            if (max > 0f) overflow += "page ${p + 1} overflows by ${max.toInt()} px"
            OUT?.let { dir ->
                File(dir).mkdirs()
                val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
                File(dir, "${name}_${p + 1}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        assertTrue("$name: ${overflow.joinToString("; ")}", overflow.isEmpty())
    }

    private object Holder { val page = androidx.compose.runtime.mutableIntStateOf(0) }

    @Test fun symptomCheck() = fits("symptom_check", 3) { com.bioscan.fieldterminal.ui.screens.health.SymptomCheckSheet(onDismiss = {}, onSaved = {}, onTrackIllness = { _, _ -> }, onTrackInjury = {}) }
    @Test fun event() = fits("event", 5) { com.bioscan.fieldterminal.ui.screens.events.EventFormSheet(onDismiss = {}, onSaved = {}) }
    @Test fun sweatTest() = fits("sweat_test", 3) { com.bioscan.fieldterminal.ui.screens.events.SweatTestSheet(onDismiss = {}, onSaved = {}) }
    @Test fun bodyCheckIn() = fits("body_check_in", 5) { com.bioscan.fieldterminal.ui.screens.status.BodyCheckInSheet(onDismiss = {}, onSaved = {}) }

    // A long run with a schedule of five blocks: what you took runs over two pages.
    private val debrief = com.bioscan.fieldterminal.data.DebriefTarget(
        sessionId = 1, eventId = null, title = "LONG RUN · 2 h 30", date = java.time.LocalDate.of(2026, 11, 15), type = "run", durationMin = 150.0,
        schedule = com.bioscan.fieldterminal.data.FuelScheduleRow(
            kind = "session", refKey = "x", durationMin = 150,
            blocks = (1..5).map { com.bioscan.fieldterminal.domain.fuel.FuelBlock("b$it", "Gel $it", "gel", startMin = 20 * it, intervalMin = 0, count = 1, carbsG = 25.0) },
        ),
        existing = null,
    )
    @Test fun fuelDebrief() = fits("fuel_debrief", 6) { com.bioscan.fieldterminal.ui.screens.fuel.FuelDebriefSheet(debrief, onDismiss = {}, onSaved = {}) }
    @Test fun regimenNew() = fits("regimen", 6) { com.bioscan.fieldterminal.ui.screens.status.SupplementFormSheet(existing = null, onDismiss = {}, onSaved = {}) }
    @Test fun healthCheckIn() = fits("health_check_in", 1) { com.bioscan.fieldterminal.ui.screens.health.HealthCheckInSheet(onDismiss = {}, onSaved = {}) }
    @Test fun healthNewBody() = fits("health_new_body", 5) { com.bioscan.fieldterminal.ui.screens.health.HealthCheckInSheet(onDismiss = {}, onSaved = {}, start = com.bioscan.fieldterminal.ui.screens.health.CheckInStart.NewBody) }
    @Test fun eveningCheckIn() = fits("evening_check_in", 2) { com.bioscan.fieldterminal.ui.screens.status.EveningCheckInSheet(java.time.LocalDate.of(2026, 11, 17), onDismiss = {}, onSaved = {}) }
    // Add entry: the menu, then each form it opens.
    private fun add(name: String, start: com.bioscan.fieldterminal.domain.AddEntryStart?, steps: Int = 1) =
        fits(name, steps) { com.bioscan.fieldterminal.ui.screens.AddEntrySheet(onDismiss = {}, onSaved = {}, start = start) }
    @Test fun addMenu() = add("add_menu", null)
    @Test fun addFood() = add("add_food", com.bioscan.fieldterminal.domain.AddEntryStart.Fuel(com.bioscan.fieldterminal.domain.FuelSubType.Food), 3)
    @Test fun addDrink() = add("add_drink", com.bioscan.fieldterminal.domain.AddEntryStart.Fuel(com.bioscan.fieldterminal.domain.FuelSubType.Drink))
    @Test fun addSupplements() = add("add_supps", com.bioscan.fieldterminal.domain.AddEntryStart.Fuel(com.bioscan.fieldterminal.domain.FuelSubType.Supplements))
    @Test fun addEncounter() = add("add_encounter", com.bioscan.fieldterminal.domain.AddEntryStart.Form(com.bioscan.fieldterminal.domain.AddEntryType.Encounter), 9)
    @Test fun addStool() = add("add_stool", com.bioscan.fieldterminal.domain.AddEntryStart.Form(com.bioscan.fieldterminal.domain.AddEntryType.Stool))
    @Test fun addWellness() = add("add_wellness", com.bioscan.fieldterminal.domain.AddEntryStart.Form(com.bioscan.fieldterminal.domain.AddEntryType.Wellness), 2)
    @Test fun addNote() = add("add_note", com.bioscan.fieldterminal.domain.AddEntryStart.Form(com.bioscan.fieldterminal.domain.AddEntryType.Note))
    @Test fun addSexual() = add("add_sexual", com.bioscan.fieldterminal.domain.AddEntryStart.Form(com.bioscan.fieldterminal.domain.AddEntryType.SexualActivity), 3)
    @Test fun addGrip() = add("add_grip", com.bioscan.fieldterminal.domain.AddEntryStart.Form(com.bioscan.fieldterminal.domain.AddEntryType.Grip))
    @Test fun shoes() = fits("shoes", 2) { com.bioscan.fieldterminal.ui.screens.shoes.ShoeFormSheet(com.bioscan.fieldterminal.ui.screens.shoes.ShoeFormRequest(null, "a1b2c3d4e5", null), onDismiss = {}, onSaved = {}) }
    // Accessory work: a full session (the most a page holds) and the editor of one exercise.
    private val curls = com.bioscan.fieldterminal.domain.training.generate.Accessory(
        "Incline Dumbbell Hammer Curl", 3, com.bioscan.fieldterminal.domain.training.definition.NumRange(10.0, 12.0),
        com.bioscan.fieldterminal.domain.training.definition.LoadDto("rpe", rpe = 8.0), note = "superset with pushdowns, slow eccentric",
    )
    @Test fun accessories() = fits("accessories", 3) {
        com.bioscan.fieldterminal.ui.screens.training.AccessoriesFlow(
            "ACCESSORY WORK · EXAMPLE THREE-DAY", listOf("D1" to "Squat and press day", "D3" to "Deadlift day"),
            mapOf("*" to List(com.bioscan.fieldterminal.ui.screens.training.MAX_PER_SESSION) { curls }, "D1" to listOf(curls)), onClose = {}, onSave = {},
        )
    }
    @Test fun accessoryEditor() = fits("accessory_editor", 2) {
        com.bioscan.fieldterminal.ui.screens.training.AccessoryEditor(curls, onClose = {}, onSave = {})
    }
    @Test fun illness() = fits("illness", 5) { IllnessSheet(onDismiss = {}, onSaved = {}) }
}
