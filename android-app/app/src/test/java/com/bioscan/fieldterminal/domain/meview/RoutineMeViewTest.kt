package com.bioscan.fieldterminal.domain.meview

import com.bioscan.fieldterminal.domain.routine.ActivityKind
import com.bioscan.fieldterminal.domain.routine.DailyLine
import com.bioscan.fieldterminal.domain.routine.LogSource
import com.bioscan.fieldterminal.domain.routine.LogStatus
import com.bioscan.fieldterminal.domain.routine.RibbonDay
import com.bioscan.fieldterminal.domain.routine.RibbonKind
import com.bioscan.fieldterminal.domain.routine.RibbonSpan
import com.bioscan.fieldterminal.domain.routine.RoutineActivity
import com.bioscan.fieldterminal.domain.routine.RoutineLog
import com.bioscan.fieldterminal.domain.routine.TimeSource
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

// Me › ROUTINE as the web reads it (console/src/dashboard/meView.ts, kind "ribbons" and rows with a hue).
class RoutineMeViewTest {
    private val today = LocalDate.of(2026, 11, 18)
    private val through = today.minusDays(1)
    private val days = (1..56).map { through.minusDays(56L - it) }
    private val ribbons = days.takeLast(28).map { RibbonDay(it, listOf(RibbonSpan(0, 28, RibbonKind.ASLEEP), RibbonSpan(28, 96, RibbonKind.HOME))) }
    private val sleep = DailyLine("sleep", "Sleep", TimeSource.SLEEP, days.associateWith { if (it.isAfter(through.minusDays(28))) 480.0 else 420.0 })
    private val home = DailyLine("place:home", "Home", TimeSource.LOCATION, days.associateWith { 14.0 })
    private val yoga = RoutineActivity(1, "Yoga", ActivityKind.PRACTICE, days = setOf(1, 2, 3, 4, 5, 6, 7))
    private val logs = (1..10).map { RoutineLog(it.toLong(), 1, through.minusDays(it - 1L), null, null, 20.0, LogStatus.DONE, LogSource.MANUAL) }

    private fun doc() = routineMeView(today, ribbons, listOf(sleep), listOf(home), emptyList(), emptyList(), listOf(yoga), logs, emptyList())

    @Test fun `routine sections in the phone's order`() {
        assertEquals(
            listOf("THE LAST 28 DAYS", "TIME", "PLACES", "PHONE", "PRACTICES", "THE LAST 4 WEEKS AGAINST THE 4 BEFORE", "REGULARITY"),
            doc().sections.map { it.title },
        )
    }

    @Test fun `lines carry the phone's format and hue`() {
        val d = doc()
        val sleepRow = d.sections[1].items.filterIsInstance<MeRow>().single()
        assertEquals("8 h 00", sleepRow.value)
        assertEquals("m-sleep-duration", sleepRow.hue)
        assertEquals("14.0 h", d.sections[2].items.filterIsInstance<MeRow>().single().value)
        assertEquals("10/27", d.sections[4].items.filterIsInstance<MeRow>().single().value)
        val change = d.sections[5].items.filterIsInstance<MeRow>().first { it.label == "SLEEP" }
        assertEquals("up from 7 h 00", change.sub)
    }

    @Test fun `ribbons serialize with spans and a legend of hours a day`() {
        val r = doc().sections[0].items.single() as MeRibbons
        assertEquals("7.0 h", r.legend.first { it.label == "Asleep" }.value)
        val json = r.toJson()
        assertEquals("ribbons", json["kind"]!!.jsonPrimitive.content)
        assertEquals(96, json["slots"]!!.jsonPrimitive.content.toInt())
        val span = json["days"]!!.jsonArray[0].jsonObject["spans"]!!.jsonArray[0].jsonArray
        assertEquals(listOf("0", "28", "m-sleep-duration"), span.map { it.jsonPrimitive.content })
    }

    @Test fun `nothing recorded says so`() {
        val empty = routineMeView(today, ribbons.map { RibbonDay(it.date, listOf(RibbonSpan(0, 96, RibbonKind.UNKNOWN))) }, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        assertTrue(empty.sections[0].items.single() is MeNote)
    }
}
