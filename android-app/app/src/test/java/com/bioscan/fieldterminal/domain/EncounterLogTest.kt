package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.LogEncounterRow
import com.bioscan.fieldterminal.data.model.PersonName
import org.junit.Assert.assertEquals
import org.junit.Test

class EncounterLogTest {

    private fun enc(id: Long, date: String, sexual: Boolean, rounds: Int? = null, orgasms: Int? = null, person: Long? = null, name: String? = null) =
        LogEncounterRow(id = id, date = date, status = "logged", sexual = sexual, rounds = rounds, orgasmCount = orgasms, personId = person, people = name?.let { PersonName(it) })

    @Test
    fun aDaysSexualEncountersFoldIntoOneIntercourseRow() {
        val rows = intercourseDayRows(
            listOf(
                enc(1, "2026-11-13", sexual = true, rounds = 2, orgasms = 1, person = 7),
                enc(2, "2026-11-13", sexual = true, rounds = 1, orgasms = 1, person = 9),
                enc(3, "2026-11-13", sexual = false),
                // no round count is one round; more orgasms than rounds never loses an orgasm
                enc(4, "2026-11-14", sexual = true),
                enc(5, "2026-11-15", sexual = true, rounds = 1, orgasms = 2),
            )
        ).associateBy { it.date }
        assertEquals(setOf("2026-11-13", "2026-11-14", "2026-11-15"), rows.keys)
        assertEquals("intercourse", rows.getValue("2026-11-13").activityType)
        assertEquals(3, rows.getValue("2026-11-13").instances.size)
        assertEquals(2, rows.getValue("2026-11-13").instances.count { it.orgasm })
        assertEquals(listOf(7L, 7L, 9L), rows.getValue("2026-11-13").instances.map { it.partnerId })
        assertEquals(1, rows.getValue("2026-11-14").instances.size)
        assertEquals(2, rows.getValue("2026-11-15").instances.count { it.orgasm })
    }

    @Test
    fun aSexualEncounterShowsAsIntercourseUnderThePartnersName() {
        val entries = buildLogEntries(
            meals = emptyList(), exerciseSessions = emptyList(), sleep = emptyList(), stool = emptyList(),
            encounters = listOf(
                enc(1, "2026-11-13", sexual = true, rounds = 2, orgasms = 1, name = "Sam"),
                enc(2, "2026-11-12", sexual = false, name = "Alex"),
            ),
        )
        assertEquals(listOf(LogEntryKind.Intercourse, LogEntryKind.Encounter), entries.map { it.kind })
        assertEquals("Sam", entries[0].headline)
        assertEquals("2 rounds · 1 orgasm", entries[0].detail)
        assertEquals(null, entries[1].detail)
    }
}

class IntimacyVocabTest {
    @Test
    fun savedValuesOutsideTheVocabularyStayVisibleAndListsHaveNoDuplicates() {
        assertEquals(listOf("Vaginal", "Mouth", "dinner"), withSaved(listOf("Vaginal", "Mouth"), listOf("Mouth", "dinner")))
        with(IntimacyVocab) {
            listOf(formats, activities, positions, toys, protection, ejaculationLocation).forEach { assertEquals(it.size, it.toSet().size) }
        }
    }
}

class IntimacyStatsTest {
    private fun enc(id: Long, date: String, person: Long?, sexual: Boolean = true, protection: List<String>? = null, rating: Int? = null, rounds: Int? = null, orgasms: Int? = null) =
        LogEncounterRow(id = id, date = date, status = "logged", sexual = sexual, personId = person, protection = protection, myRating = rating, rounds = rounds, orgasmCount = orgasms)

    private val rows = listOf(
        enc(1, "2026-08-01", 7, protection = listOf("Condom")),
        enc(2, "2026-11-01", 7, protection = listOf("Condom (partly)", "PrEP"), rating = 4, rounds = 2, orgasms = 1),
        enc(3, "2026-11-05", 9, protection = listOf("Condom"), rating = 2),
        enc(4, "2026-11-06", 9, sexual = false),
        enc(5, "2026-11-10", null),
    )

    @Test
    fun aWindowCountsItsEncountersPartnersAndBarriers() {
        val s = intimacyStats(rows, java.time.LocalDate.parse("2026-10-15"), java.time.LocalDate.parse("2026-11-15"))
        assertEquals(4, s.encounters)
        assertEquals(3, s.sexual)
        assertEquals(2, s.partners)
        // 7 was first seen in August, so only 9 is new
        assertEquals(1, s.newPartners)
        assertEquals(4, s.rounds)
        assertEquals(1, s.withBarrier)
        // "Condom (partly)" is not a barrier for the encounter
        assertEquals(1, s.withoutBarrier)
        assertEquals(1, s.protectionUnknown)
        assertEquals(3.0, s.avgRating!!, 0.0001)
    }

    @Test
    fun stiStatusCountsWhatCameAfterTheLastResult() {
        fun test(id: Long, date: String, infection: String, result: String, treated: String? = null) =
            com.bioscan.fieldterminal.data.model.StiTestRow(id, date, infection, result, treatedOn = treated)
        val status = stiStatus(
            listOf(
                test(1, "2026-08-10", "HIV", "negative"),
                test(2, "2026-11-02", "Chlamydia", "positive"),
                test(3, "2026-11-02", "HIV", "negative"),
                test(4, "2026-11-12", "Syphilis", "pending"),
            ),
            rows,
        )
        // the pending test is not the last test
        assertEquals(java.time.LocalDate.parse("2026-11-02"), status.lastTest)
        assertEquals(listOf("HIV" to 3L, "Syphilis" to 4L, "Chlamydia" to 2L), status.latest.map { it.infection to it.id })
        assertEquals(listOf(2L), status.open.map { it.id })
        // after 2 Nov: #3 (partner 9, condom) and #5 (no partner, nothing recorded); #4 was not sexual
        assertEquals(1, status.partnersSince)
        assertEquals(0, status.withoutBarrierSince)
        assertEquals(1, status.protectionUnknownSince)
        assertEquals(listOf(7L), partnersBefore(java.time.LocalDate.parse("2026-11-02"), rows))
        assertEquals(0, stiStatus(listOf(test(2, "2026-11-02", "Chlamydia", "positive", treated = "2026-11-09")), rows).open.size)
    }

    @Test
    fun partnersAreBrowsedBySearchAndOrder() {
        fun person(id: Long, name: String, score: Int? = null) = com.bioscan.fieldterminal.data.model.PersonProfileRow(id = id, name = name, score = score)
        val all = partnerSummaries(listOf(person(7, "Sam", 3), person(9, "Alex", 5), person(11, "Robin")), rows)
        assertEquals(listOf("Alex", "Sam", "Robin"), browsePartners(all, "", PartnerSort.Recent).map { it.person.name })
        assertEquals(listOf("Alex", "Robin", "Sam"), browsePartners(all, "", PartnerSort.Name).map { it.person.name })
        assertEquals(listOf("Alex", "Sam", "Robin"), browsePartners(all, "", PartnerSort.Score).map { it.person.name })
        assertEquals(listOf("Sam"), browsePartners(all, " sa ", PartnerSort.Most).map { it.person.name })
        assertEquals(2, all.first { it.person.id == 7L }.encounters.size)
        assertEquals(listOf("Condom" to 2, "Condom (partly)" to 1, "PrEP" to 1), tally(rows) { it.protection })
    }

    @Test
    fun aMonthIsWholeWeeksFromMonday() {
        val nov = monthCells(java.time.YearMonth.of(2026, 11))
        // 1 Nov 2026 is a Sunday: six blanks before it, 30 days, six after to finish the last week
        assertEquals(42, nov.size)
        assertEquals(java.time.LocalDate.of(2026, 11, 1), nov[6])
        assertEquals(null, nov[5])
        assertEquals(java.time.LocalDate.of(2026, 11, 30), nov[35])
        assertEquals(28, monthCells(java.time.YearMonth.of(2027, 2)).size)
    }

    @Test
    fun theStayAroundTheLoggedTimeIsSuggested() {
        fun span(place: String, from: String, to: String) = VisitSpan(place, java.time.LocalDateTime.parse(from), java.time.LocalDateTime.parse(to))
        val visits = listOf(
            span("home", "2026-11-14T08:00", "2026-11-14T19:00"),
            span("theirs", "2026-11-14T20:00", "2026-11-14T23:30"),
            span("kiosk", "2026-11-14T23:40", "2026-11-14T23:45"),
        )
        fun at(t: String) = suggestVisit(visits, java.time.LocalDateTime.parse(t))?.placeId
        assertEquals("theirs", at("2026-11-14T21:00"))
        // logged on the way home: the stay that just ended, not the 5-minute stop
        assertEquals("theirs", at("2026-11-15T00:30"))
        assertEquals(210, suggestVisit(visits, java.time.LocalDateTime.parse("2026-11-14T21:00"))!!.minutes)
        assertEquals(null, at("2026-11-15T09:00"))
    }
}
