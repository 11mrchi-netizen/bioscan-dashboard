package com.bioscan.fieldterminal.ui.components

import com.bioscan.fieldterminal.domain.events.AidStation
import com.bioscan.fieldterminal.domain.events.AthleteEvent
import com.bioscan.fieldterminal.domain.events.EventKind
import com.bioscan.fieldterminal.domain.events.EventPriority
import com.bioscan.fieldterminal.domain.events.EventSport
import com.bioscan.fieldterminal.domain.trail.CoursePoint
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class SaversTest {
    @Test
    fun aCourseSurvivesAsAString() {
        val points = listOf(CoursePoint(0.0, 12.5), CoursePoint(1000.0, null), CoursePoint(2500.5, 140.0))
        assertEquals(points, decodeCourse(encodeCourse(points)))
        assertEquals(emptyList<CoursePoint>(), decodeCourse(encodeCourse(emptyList())))
    }

    @Test
    fun anEventDraftSurvivesWithItsCourseAndStations() {
        val e = AthleteEvent(
            name = "Synthetic trail 30K", kind = EventKind.RACE, sport = EventSport.TRAIL_RUN, priority = EventPriority.A,
            date = LocalDate.of(2026, 12, 6), start = ZonedDateTime.of(2026, 12, 6, 7, 30, 0, 0, ZoneId.of("Asia/Taipei")),
            targetTimeMin = 240.0, distanceKm = 30.2, ascentM = 1500.0, startLat = 25.0, startLon = 121.5,
            course = listOf(CoursePoint(0.0, 100.0), CoursePoint(30200.0, 120.0)),
            aidStations = listOf(AidStation("CP1", 11.0, water = true, food = true, cutoffMin = 120.0)),
            calendarEventId = "cal-1", notes = "Line one\nline two",
        )
        assertEquals(e, decodeEvent(encodeEvent(e)))
    }
}
