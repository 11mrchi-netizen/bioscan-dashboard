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
    @Test fun illness() = fits("illness", 5) { IllnessSheet(onDismiss = {}, onSaved = {}) }
}
