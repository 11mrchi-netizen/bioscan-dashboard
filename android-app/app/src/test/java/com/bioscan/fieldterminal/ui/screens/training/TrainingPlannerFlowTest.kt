package com.bioscan.fieldterminal.ui.screens.training

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.bioscan.fieldterminal.data.DefinitionCatalog
import com.bioscan.fieldterminal.data.TrainingProgramRepository
import com.bioscan.fieldterminal.domain.training.definition.parseDefinition
import com.bioscan.fieldterminal.ui.theme.FieldTerminalTheme
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

// The planner's walk (owner's layout, Nov 2026): WHEN → PROGRAM → THE WEEK → THE WEEKS → DETAILS → REVIEW, with a
// placeholder program (nothing here comes from a book).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h900dp-xhdpi", application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrainingPlannerFlowTest {
    @get:Rule val compose = createComposeRule()

    private fun week(n: Int, pct: Int) = """{"week":$n,"sessions":[
        {"session":"D1","items":[{"slot":"A","sets":3,"reps":5,"load":{"kind":"pct_1rm","value":$pct}},{"slot":"B","sets":3,"reps":5,"load":{"kind":"pct_1rm","value":$pct}}]},
        {"session":"D3","items":[{"slot":"A","sets":3,"reps":5,"load":{"kind":"pct_1rm","value":$pct}},{"slot":"H","sets":1,"reps":5,"load":{"kind":"pct_1rm","value":$pct}}]},
        {"session":"D5","items":[{"slot":"A","sets":3,"reps":5,"load":{"kind":"pct_1rm","value":$pct}},{"slot":"B","sets":3,"reps":5,"load":{"kind":"pct_1rm","value":$pct}}]}]}"""
    private val module = """
    {"kind":"strength_module","key":"example.three_day","title":"Example three-day","family":"example","domain":"max_strength",
     "sessions_per_week":3,"day_positions":[1,3,5],"block_lengths":[3],
     "slots":[{"id":"A","role":"press","standard":"Press"},{"id":"B","role":"squat","standard":"Squat"},{"id":"H","role":"hinge","standard":"Deadlift"}],
     "sessions":[{"id":"D1","label":"One","slots":["A","B"]},{"id":"D3","label":"Three","slots":["A","H"]},{"id":"D5","label":"Five","slots":["A","B"]}],
     "variants":[{"key":"std","title":"Standard","weeks":[${week(1, 70)},${week(2, 75)},${week(3, 80)}]}],
     "progression":{"unit":"lb","upper":5,"lower":10,"after_weeks":{"min":3,"max":6}}}
    """.trimIndent()
    private val protocol = """
    {"kind":"conditioning_protocol","key":"example.black","title":"Example black","protocol_type":"polarized",
     "budget":{"low_intensity_minutes_per_week":{"min":120,"max":180},"session_min_minutes":30,"high_intensity_per_week":1,"min_rest_days_per_week":1},
     "default_layout":[{"day":2,"kind":"lic"},{"day":4,"kind":"lic"},{"day":6,"kind":"hic"}],
     "suggested":{"lic":["example.run"],"hic":["example.hills"]}}
    """.trimIndent()
    private val runDef = """
    {"kind":"conditioning_session","key":"example.run","title":"Example run","category":"lic","domains":["aerobic_base"],
     "prescription":{"by":["minutes"],"minutes":{"min":30,"max":90}},"structure":{"type":"continuous"}}
    """.trimIndent()
    private val hills = """
    {"kind":"conditioning_session","key":"example.hills","title":"Example hills","category":"hic","domains":["anaerobic_capacity"],
     "prescription":{"by":["rounds"],"rounds":{"min":5,"max":10}},"structure":{"type":"repeats"}}
    """.trimIndent()

    private fun state(): TrainingPlannerState {
        val client = createSupabaseClient("https://example.invalid", "key") { install(Postgrest) }
        val defs = listOf(module, protocol, runDef, hills).map { parseDefinition(it) }
        return TrainingPlannerState(TrainingProgramRepository(client), CoroutineScope(Dispatchers.Unconfined)).apply {
            catalog = DefinitionCatalog(defs, emptyList())
            loading = false
            startDate = LocalDate.of(2026, 11, 16)
        }
    }

    @Test
    fun bothRoutesWalkTheSameOrderDatesFirst() {
        val s = state()
        assertEquals(listOf(PlannerStep.When, PlannerStep.Program, PlannerStep.Week, PlannerStep.Weeks, PlannerStep.Details, PlannerStep.Review), s.steps)
        assertTrue(s.program is Program.Build)
        assertEquals(PlannerStep.When, s.step)
    }

    @Test
    fun aLengthSetFirstIsKeptWhenTheProgramIsPicked() {
        val s = state()
        s.setLength(10)
        s.next()
        assertFalse(s.canContinue()) // no program yet
        s.pickStrength("example.three_day")
        s.pickProtocol("example.black")
        assertEquals(10, s.weeks)
        assertEquals(10, s.timeline.size)
        assertTrue(s.canContinue())
    }

    @Test
    fun editFromReviewComesBackToReview() {
        val s = state()
        s.pickStrength("example.three_day")
        repeat(5) { s.next() }
        assertEquals(PlannerStep.Review, s.step)
        s.editFromReview(PlannerStep.Week)
        assertEquals(PlannerStep.Week, s.step)
        s.next()
        assertEquals(PlannerStep.Review, s.step)
        // the step bar opens only steps already walked
        val fresh = state()
        fresh.goTo(PlannerStep.Details)
        assertEquals(PlannerStep.When, fresh.step)
    }

    @Test
    fun screenshots() {
        val s = state()
        s.name = "Winter base"
        s.setLength(9)
        s.pickStrength("example.three_day")
        s.pickProtocol("example.black")
        compose.setContent { FieldTerminalTheme { TrainingPlannerScreen(onBack = {}, onCreated = {}, preset = s) } }
        val out = System.getenv("FT_PLANNER_SHOTS") ?: return
        for (step in listOf(PlannerStep.When, PlannerStep.Week, PlannerStep.Weeks, PlannerStep.Details, PlannerStep.Review)) {
            compose.runOnIdle { s.step = step; s.furthest = 5 }
            compose.waitForIdle()
            val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
            File(out, "planner_${step.name.lowercase()}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
