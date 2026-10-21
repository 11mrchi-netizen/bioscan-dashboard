package com.bioscan.fieldterminal.domain.health

import com.bioscan.fieldterminal.data.model.ConcernStateDto
import com.bioscan.fieldterminal.data.model.DailySeriesDto
import com.bioscan.fieldterminal.data.model.EpisodeDto
import com.bioscan.fieldterminal.data.model.FindingRow
import com.bioscan.fieldterminal.data.model.HealthObservationInsert
import com.bioscan.fieldterminal.data.model.HealthObservationRow
import com.bioscan.fieldterminal.data.model.HealthSignalInsert
import com.bioscan.fieldterminal.data.model.HealthOverviewRow
import com.bioscan.fieldterminal.data.model.RegionRef
import com.bioscan.fieldterminal.domain.MetricState
import com.bioscan.fieldterminal.data.model.NotificationKind
import com.bioscan.fieldterminal.domain.notifications.NotificationDestination
import com.bioscan.fieldterminal.domain.notifications.notificationDestination
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime

class HealthConcernsDomainTest {
    private val vocab = parseHealthVocab(File("../../supabase/functions/_shared/health-vocab/health-vocab.json").readText())
    private var n = 0
    private val ids = { "id${++n}" }
    private val evening = ZonedDateTime.of(2026, 10, 21, 21, 5, 0, 0, ZoneOffset.ofHours(8))

    @Test
    fun `the shared vocabulary parses and gives labels and follow-ups`() {
        assertEquals(27, vocab.regions.size)
        assertEquals("R KNEE", vocab.regionLabel("knee", "right"))
        assertEquals("Right knee", vocab.defaultTitle("knee", "right", "sym.pain"))
        assertEquals("Sore throat", vocab.defaultTitle(null, null, "sym.sore_throat"))
        assertEquals(listOf("swelling", "gave_way"), vocab.followupsFor("knee", "sym.pain").map { it.id })
        assertTrue(vocab.systemicSymptoms.any { it.code == "sym.fever" })
        assertFalse(vocab.localSymptoms.any { it.code == "sym.fever" })
    }

    @Test
    fun `every vocabulary region is on the body map, on both sides when sided`() {
        for (r in vocab.regions) {
            val shapes = BODY_MAP_SHAPES.filter { it.code == r.code }
            assertTrue("${r.code} has no shape", shapes.isNotEmpty())
            if (r.sided) {
                assertTrue("${r.code} right", shapes.any { it.side == "right" })
                assertTrue("${r.code} left", shapes.any { it.side == "left" })
            } else assertTrue("${r.code} is not sided", shapes.all { it.side == null })
        }
        assertTrue(BODY_MAP_SHAPES.all { vocab.region(it.code) != null })
    }

    @Test
    fun `a tap at a region's centre picks that region, and the front view mirrors sides`() {
        for (s in BODY_MAP_SHAPES) {
            val hit = hitRegion(s.view, s.cx, s.cy)
            assertEquals("${s.view} ${s.code} ${s.side}", s.code to s.side, hit?.code to hit?.side)
        }
        // Front: the viewer's left half is the person's right.
        assertEquals("right", hitRegion(BodyView.Front, 41f, 132f)?.side)
        assertEquals("right", hitRegion(BodyView.Back, 58f, 132f)?.side)
        assertNull(hitRegion(BodyView.Front, 2f, 2f))
    }

    @Test
    fun `a check-in after midnight belongs to the evening before`() {
        assertEquals(LocalDate.of(2026, 10, 20), checkInDate(ZonedDateTime.of(2026, 10, 21, 0, 40, 0, 0, ZoneOffset.UTC)))
        assertEquals(LocalDate.of(2026, 10, 21), checkInDate(evening))
    }

    @Test
    fun `NOTHING is one sweep marked symptom-free`() {
        val plan = buildCheckIn(CheckInDraft(), evening, ids)
        assertEquals(1, plan.observations.size)
        val o = plan.observations[0]
        assertEquals("sweep", o.scope)
        assertTrue(o.symptomFree)
        assertNull(o.concernId)
        assertEquals("2026-10-21", o.localDate)
        assertEquals(480, o.utcOffsetMin)
        assertTrue(plan.signals.isEmpty())
    }

    @Test
    fun `NOTHING SINCE covers missed days, at most seven back`() {
        assertEquals(LocalDate.of(2026, 10, 18), recallStart(LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 21)))
        assertEquals(LocalDate.of(2026, 10, 14), recallStart(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 21)))
        assertNull(recallStart(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 21)))
        val plan = buildCheckIn(CheckInDraft(recallFrom = LocalDate.of(2026, 10, 18)), evening, ids)
        assertEquals("since_last", plan.observations[0].recall)
        assertEquals("2026-10-18", plan.observations[0].recallFrom)
        // out of range is dropped rather than rejected by the table
        val far = buildCheckIn(CheckInDraft(recallFrom = LocalDate.of(2026, 9, 1)), evening, ids)
        assertEquals("today", far.observations[0].recall)
    }

    @Test
    fun `answers, a new problem and nothing else`() {
        val draft = CheckInDraft(
            answers = listOf(
                ConcernAnswer("knee", ConcernChange.Worse, severity = 5, impact = 2),
                ConcernAnswer("cold", ConcernChange.Gone, severity = null, impact = null, symptom = "sym.sore_throat"),
            ),
            problems = listOf(
                NewProblem(
                    concernId = null,
                    newConcern = NewConcernSpec("Left achilles", "anatomical", "gradual", listOf(RegionRef("achilles", "left")), listOf("sym.stiffness")),
                    signals = listOf(NewSignal("sym.stiffness", "achilles", "left", 3, mapOf("morning_stiffness" to "true", "warm_up" to "better"))),
                    impact = 1, onset = false, sessionId = 42L, timing = "after_session",
                ),
            ),
        )
        val plan = buildCheckIn(draft, evening, ids)
        assertEquals(1, plan.concerns.size)
        assertFalse(plan.concerns[0].excludesFromBaselines)
        assertEquals(4, plan.observations.size) // knee, cold, new achilles, the sweep
        val knee = plan.observations.first { it.concernId == "knee" }
        assertEquals("worse", knee.changeVsLast)
        assertEquals(2, knee.functionImpact)
        val cold = plan.observations.first { it.concernId == "cold" }
        assertTrue(cold.symptomFree)
        val achilles = plan.observations.first { it.concernId == plan.concerns[0].id }
        assertEquals("session", achilles.scope)
        assertEquals(42L, achilles.sessionId)
        assertEquals(2, plan.signals.size)
        val sig = plan.signals.first { it.symptom == "sym.stiffness" }
        assertEquals("true", sig.details["morning_stiffness"].toString())
        assertEquals("\"better\"", sig.details["warm_up"].toString())
        assertEquals("sweep", plan.observations.last().scope)
    }

    @Test
    fun `a systemic concern is left out of baselines and nothing else can be turned off`() {
        val plan = buildCheckIn(
            CheckInDraft(
                problems = listOf(NewProblem(null, NewConcernSpec("Cold", "systemic", "unknown", emptyList(), listOf("sym.sore_throat")), listOf(NewSignal("sym.sore_throat", severity = 4, temperatureC = 38.2)), null, onset = false)),
                nothingElse = false,
            ),
            evening, ids,
        )
        assertTrue(plan.concerns[0].excludesFromBaselines)
        assertTrue(plan.observations.none { it.scope == "sweep" })
        assertEquals(38.2, plan.signals[0].temperatureC!!, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `severity outside 0-10 is refused`() {
        buildCheckIn(CheckInDraft(answers = listOf(ConcernAnswer("k", ConcernChange.Same, 11, null))), evening, ids)
    }

    @Test
    fun `a retraction replaces and withdraws the original`() {
        val r = retraction(HealthObservationRow("o1", "2026-10-21T13:00:00Z", "2026-10-21", "k", "concern", false), evening, ids)
        assertEquals("o1", r.supersedes)
        assertTrue(r.retracted)
        assertEquals("k", r.concernId)
    }

    @Test
    fun `state words keep amber for worsening only and unobserved is not fine`() {
        assertEquals(MetricState.Warning, concernStateWord("worsening").state)
        assertEquals(MetricState.Unavailable, concernStateWord("unobserved").state)
        assertEquals(MetricState.Optimal, concernStateWord("improving").state)
        assertEquals("POSSIBLE · EXPLORATORY", findingStatusWord("possible").word)
        assertEquals("NOT ENOUGH YET", findingStatusWord("insufficient").word)
        assertEquals("INTERIM", trialWord("interim", null).word)
        assertTrue(listOf("worsening", "active", "stable", "improving", "unobserved", "resolved").zipWithNext().all { (a, b) -> concernStateOrder(a) < concernStateOrder(b) })
    }

    @Test
    fun `severity points keep unobserved days as gaps and explicit zeros as zero`() {
        val s = ConcernStateDto(daily = DailySeriesDto(
            dates = listOf("2026-10-18", "2026-10-19", "2026-10-20", "2026-10-21"),
            severity = listOf(0, null, 4, null),
            observed = listOf(2, 0, 1, 1),
            present = listOf(0, 0, 1, 1),
            impact = listOf(null, null, 1, null),
        ))
        val pts = severityPoints(s, 30)
        assertEquals(listOf(0, null, 4, null), pts.map { it.severity })
        assertEquals(listOf(true, false, true, true), pts.map { it.observed })
        assertTrue(pts[0].recalled)
    }

    @Test
    fun `recovery curves line up each episode from its first day`() {
        val s = ConcernStateDto(
            daily = DailySeriesDto(listOf("2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04"), listOf(5, 3, null, 0), listOf(1, 1, 0, 1), listOf(1, 1, 0, 0), listOf(null, null, null, null)),
            episodes = listOf(EpisodeDto(start = "2026-10-01", end = "2026-10-04")),
        )
        assertEquals(listOf(0 to 5, 1 to 3, 3 to 0), recoveryCurves(s).single().second)
    }

    @Test
    fun `published payloads decode, with fields the app does not use ignored`() {
        val json = Json { ignoreUnknownKeys = true }
        val overview = json.decodeFromString(HealthOverviewRow.serializer(), """{"key":"all","state":"ready","payload":{"asOf":"2026-10-21","concerns":[{"concernId":"k","title":"Right knee","kind":"anatomical","onsetType":"gradual","regions":[{"code":"knee","side":"right"}],"state":"worsening","stateReason":"x","lastObserved":"2026-10-21","currentEpisode":{"start":"2026-10-15","peakSeverity":6},"lastSeverity":5,"episodes":3,"safety":[{"code":"rising","text":"t"}],"dataQuality":"GOOD","findings":1,"extra":true}],"unassigned":[],"lastCheckin":"2026-10-21","versions":{"core":"3"}}}""")
        assertEquals("worsening", overview.payload.concerns.single().state)
        assertEquals(6, overview.payload.concerns.single().currentEpisode?.peakSeverity)
        val finding = json.decodeFromString(FindingRow.serializer(), """{"key":"k:trail_run","state":"possible","payload":{"questionId":"health:k:trail_run","status":"possible","what":"w","evidence":{"text":"t","cases":8,"or":3.5,"lo":1.2,"hi":9.1,"links":{"episodes":["e1"],"sessions":[4],"dates":["2026-10-01"]},"sensitivity":[]},"explanations":["a"],"uncertainty":"u","nextStep":{"kind":"observe","text":"n"},"rawVerdict":"possible"}}""")
        assertEquals(3.5, finding.payload.evidence.oddsRatio!!, 0.0)
        assertNotNull(finding.payload.evidence.links.sessions.single())
    }

    @Test
    fun `the 21-00 reminder is skipped once today has a check-in and opens Health concerns`() {
        val today = LocalDate.of(2026, 10, 21)
        assertTrue(healthCheckinNeeded(null, today))
        assertTrue(healthCheckinNeeded(today.minusDays(1), today))
        assertFalse(healthCheckinNeeded(today, today))
        assertEquals(NotificationDestination.HEALTH, notificationDestination(NotificationKind.HEALTH_CHECKIN))
        assertTrue(NotificationKind.HEALTH_CHECKIN in NotificationKind.ALL)
    }

    @Test
    fun `insert rows always carry their not-null defaults, so a batch never sends NULL for them`() {
        // a batch insert fills a key one row leaves out with NULL when another row has it (onset 23502 on 21 Oct 2026)
        val json = Json { encodeDefaults = false }
        val obs = json.encodeToString(HealthObservationInsert.serializer(), HealthObservationInsert("o", "t", "2026-10-21", scope = "sweep", symptomFree = true))
        for (key in listOf("\"onset\":false", "\"recall\":\"today\"", "\"retracted\":false")) assertTrue(key, key in obs)
        val sig = json.encodeToString(HealthSignalInsert.serializer(), HealthSignalInsert("o", symptom = "sym.pain"))
        assertTrue(sig, "\"details\":{}" in sig)
    }
}
