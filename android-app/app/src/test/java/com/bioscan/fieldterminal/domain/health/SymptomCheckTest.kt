package com.bioscan.fieldterminal.domain.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

class SymptomCheckTest {
    private val vocab = parseHealthVocab(File("../../supabase/functions/_shared/health-vocab/health-vocab.json").readText())

    private fun check(area: CheckArea, symptoms: Set<String>, yes: Set<String> = emptySet(), intensity: CheckIntensity = CheckIntensity.MILD,
                      since: CheckSince = CheckSince.TODAY, course: CheckCourse = CheckCourse.SAME) =
        CheckAnswers(area, symptoms, intensity, since, course, questionsFor(area, symptoms).associate { it.id to (it.id in yes) })

    @Test
    fun everySymptomAndPatternTheCheckUsesExists() {
        val codes = vocab.symptoms.map { it.code }.toSet()
        CheckArea.entries.flatMap { it.symptoms }.forEach { assertTrue("$it is not in the vocabulary", it in codes) }
        CHECK_QUESTIONS.flatMap { it.supports }.forEach { assertTrue("pattern $it", it in CHECK_PATTERNS) }
        assertEquals(CHECK_QUESTIONS.size, CHECK_QUESTIONS.map { it.id }.toSet().size)
    }

    @Test
    fun aTensionHeadacheIsASmallThingWithItsReasons() {
        val o = assess(check(CheckArea.HEAD, setOf("sym.headache"), setOf("head_band", "head_screen")))
        assertEquals(CheckLevel.SELF_CARE, o.level)
        assertEquals("tension", o.patterns.first().pattern.id)
        assertEquals(2, o.patterns.first().because.size)
        assertNull(o.track)
    }

    @Test
    fun aRedFlagComesFirstAndMakesItUrgent() {
        val o = assess(check(CheckArea.HEAD, setOf("sym.headache"), setOf("head_band", "head_sudden")))
        assertEquals(CheckLevel.URGENT, o.level)
        assertTrue(o.flags.first().text.contains("119"))
        // The red flags are asked first.
        assertEquals(FlagLevel.URGENT, questionsFor(CheckArea.HEAD, setOf("sym.headache")).first().flag?.level)
    }

    @Test
    fun aStuffedNoseWithFeverAndAchesPointsToTrackingAnIllness() {
        val small = assess(check(CheckArea.NOSE_THROAT, setOf("sym.congestion")))
        assertEquals("cold", small.patterns.single().pattern.id)
        val flu = assess(check(CheckArea.NOSE_THROAT, setOf("sym.congestion", "sym.sore_throat"), setOf("nt_fever", "nt_aches")))
        assertEquals("flu_like", flu.patterns.first().pattern.id)
        assertEquals(TrackAs.ILLNESS, flu.track)
    }

    @Test
    fun howItGoesAddsItsOwnFlags() {
        val worse = assess(check(CheckArea.MUSCLES, setOf("sym.ache"), since = CheckSince.WEEK, course = CheckCourse.WORSE))
        assertEquals(CheckLevel.CLINICIAN, worse.level)
        assertEquals(TrackAs.INJURY, worse.track)
        assertEquals(CheckLevel.CLINICIAN, assess(check(CheckArea.URINARY, setOf("sym.urinary"))).level)
    }

    @Test
    fun theDataPrefillsSleepAndTraining() {
        val q = questionsFor(CheckArea.WHOLE_BODY, setOf("sym.fatigue"))
        val p = CheckContext(sleepHoursLastNight = 5.2, trainingMin72h = 240.0, longestSessionMin72h = 95.0).prefill(q)
        assertEquals(true, p["wb_sleep"]?.first)
        assertEquals(true, p["wb_training"]?.first)
        assertTrue(p["wb_sleep"]!!.second.contains("5.2"))
    }

    @Test
    fun aCheckBelongsToNoConcernAndKeepsTheRegionInItsDetails() {
        val a = check(CheckArea.MUSCLES, setOf("sym.ache"), setOf("mu_session")).copy(region = "quad", side = "left")
        val plan = buildSymptomCheck(a, assess(a), ZonedDateTime.of(2026, 11, 10, 21, 0, 0, 0, ZoneId.of("Asia/Taipei"))) { "id1" }
        val o = plan.observations.single()
        assertEquals("check", o.scope)
        assertNull(o.concernId)
        assertTrue(plan.concerns.isEmpty())
        val s = plan.signals.single()
        assertNull(s.region)
        assertEquals("\"quad\"", s.details["region"].toString())
        assertEquals("\"doms\"", o.assessment!!["patterns"].toString().removeSurrounding("[", "]"))
    }
}
