package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.NotificationKind
import com.bioscan.fieldterminal.domain.notifications.NotificationDestination
import com.bioscan.fieldterminal.domain.notifications.notificationDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

// Synthetic values only.
class BodyCheckinTest {
    private fun checkin(id: Long, date: String, caliper: String? = null, vararg m: BodyMeasurement) =
        BodyCheckinRecord(id, LocalDate.parse(date).atTime(8, 0), caliper = caliper, measurements = m.toList())

    private fun tape(site: BodySite, vararg r: Double, side: BodySide = BodySide.NONE) = BodyMeasurement(site, side, r.toList())
    private fun fold(site: BodySite, vararg r: Double) = BodyMeasurement(site, BodySide.RIGHT, r.toList())

    @Test
    fun `tape takes two readings and a third only when they disagree by more than half a centimetre`() {
        assertTrue(needsAnotherReading(MeasureMethod.TAPE, listOf(84.0)))
        assertFalse(needsAnotherReading(MeasureMethod.TAPE, listOf(84.0, 84.5)))
        assertTrue(needsAnotherReading(MeasureMethod.TAPE, listOf(84.0, 84.7)))
        assertFalse(needsAnotherReading(MeasureMethod.TAPE, listOf(84.0, 84.7, 84.2)))
    }

    @Test
    fun `skinfolds take three readings and a fourth when they span more than two millimetres`() {
        assertTrue(needsAnotherReading(MeasureMethod.CALIPER, listOf(12.0, 13.0)))
        assertFalse(needsAnotherReading(MeasureMethod.CALIPER, listOf(12.0, 13.0, 12.0)))
        assertTrue(needsAnotherReading(MeasureMethod.CALIPER, listOf(12.0, 15.0, 12.0)))
        assertFalse(needsAnotherReading(MeasureMethod.CALIPER, listOf(12.0, 15.0, 12.0, 13.0)))
        assertEquals(12.5, BodyMeasurement(BodySite.SF_THIGH, BodySide.RIGHT, listOf(12.0, 15.0, 12.0, 13.0)).value, 1e-9)
    }

    @Test
    fun `a typed reading is accepted only inside the site's plausible range`() {
        assertEquals(84.5, parseReading(BodySite.WAIST_MIDPOINT, " 84,5 ")!!, 1e-9)
        assertNull(parseReading(BodySite.WAIST_MIDPOINT, "8"))
        assertNull(parseReading(BodySite.SF_ABDOMINAL, "abc"))
    }

    @Test
    fun `limbs are measured on both sides, skinfolds on the right, optional sites only when chosen`() {
        assertEquals(20, BODY_CHECKIN_SLOTS.size)
        assertTrue(BODY_CHECKIN_SLOTS.filter { it.site.method == MeasureMethod.CALIPER }.all { it.side == BodySide.RIGHT })
        assertEquals(listOf(BodySide.RIGHT, BodySide.LEFT), BODY_CHECKIN_SLOTS.filter { it.site == BodySite.CALF }.map { it.side })
        val slots = activeSlots(tape = true, calipers = true, flexedArms = false, chestSkinfold = false)
        assertEquals(17, slots.size)
        assertTrue(slots.none { it.site == BodySite.BICEPS_FLEXED || it.site == BodySite.SF_CHEST })
        assertTrue(activeSlots(tape = false, calipers = true, flexedArms = true, chestSkinfold = true).all { it.site.method == MeasureMethod.CALIPER })
    }

    @Test
    fun `tape sites are finished one by one, then skinfolds go round in rounds`() {
        val slots = activeSlots(tape = true, calipers = true, flexedArms = false, chestSkinfold = false)
        val neck = BodySlot(BodySite.NECK, BodySide.NONE)
        assertEquals(neck, nextSlot(slots, emptyMap(), emptySet(), null))
        // One reading of the neck: stay on it.
        assertEquals(neck, nextSlot(slots, mapOf(neck to listOf(38.0)), emptySet(), neck))
        // Neck done, shoulders skipped: on to the chest.
        val shoulders = BodySlot(BodySite.SHOULDERS, BodySide.NONE)
        assertEquals(BodySite.CHEST, nextSlot(slots, mapOf(neck to listOf(38.0, 38.2)), setOf(shoulders), neck)!!.site)

        val tapeDone = slots.filter { it.site.method == MeasureMethod.TAPE }.associateWith { listOf(50.0, 50.0) }
        val abd = BodySlot(BodySite.SF_ABDOMINAL, BodySide.RIGHT)
        val supra = BodySlot(BodySite.SF_SUPRAILIAC, BodySide.RIGHT)
        val thigh = BodySlot(BodySite.SF_THIGH, BodySide.RIGHT)
        val calf = BodySlot(BodySite.CALF, BodySide.LEFT)
        assertEquals(abd, nextSlot(slots, tapeDone, emptySet(), calf))
        // A reading at the abdomen moves to the next fold, the last fold wraps round to the first.
        assertEquals(supra, nextSlot(slots, tapeDone + (abd to listOf(20.0)), emptySet(), abd))
        assertEquals(abd, nextSlot(slots, tapeDone + mapOf(abd to listOf(20.0), supra to listOf(15.0), thigh to listOf(12.0)), emptySet(), thigh))
        // Abdomen and thigh agree after three rounds, the suprailiac fold does not: only it is asked again.
        val three = tapeDone + mapOf(abd to listOf(20.0, 20.5, 20.0), supra to listOf(15.0, 18.0, 15.5), thigh to listOf(12.0, 12.0, 12.5))
        assertEquals(supra, nextSlot(slots, three, emptySet(), thigh))
        assertNull(nextSlot(slots, three + (supra to listOf(15.0, 18.0, 15.5, 15.0)), emptySet(), supra))
        assertNull(nextSlot(slots, three, setOf(supra), thigh))
    }

    @Test
    fun `the typical error comes from the repeats and never drops below the floor`() {
        val h = listOf(
            checkin(1, "2026-08-01", null, tape(BodySite.WAIST_MIDPOINT, 84.0, 84.4), fold(BodySite.SF_THIGH, 10.0, 12.0, 14.0)),
            checkin(2, "2026-09-01", null, tape(BodySite.WAIST_MIDPOINT, 83.0, 83.4), fold(BodySite.SF_THIGH, 20.0, 22.0, 24.0)),
        )
        assertEquals(0.2828, singleReadingError(h, BodySite.WAIST_MIDPOINT)!!, 1e-3)
        assertEquals(0.5, valueError(h, BodySite.WAIST_MIDPOINT), 1e-9)
        assertEquals(2.0, singleReadingError(h, BodySite.SF_THIGH)!!, 1e-9)
        assertEquals(2.0 / kotlin.math.sqrt(3.0), valueError(h, BodySite.SF_THIGH), 1e-9)
        assertNull(singleReadingError(h.take(1), BodySite.WAIST_MIDPOINT))
    }

    @Test
    fun `a change counts only beyond the smallest real difference`() {
        val down = siteChanges(
            listOf(
                checkin(1, "2026-08-01", null, tape(BodySite.WAIST_MIDPOINT, 85.0, 85.0)),
                checkin(2, "2026-09-01", null, tape(BodySite.WAIST_MIDPOINT, 83.5, 83.5)),
            ),
        ).single()
        assertEquals(-1.5, down.change!!, 1e-9)
        assertEquals(2.77 * 0.5, down.smallestReal, 1e-9)
        assertEquals(ChangeVerdict.DOWN, down.verdict)
        val small = siteChanges(
            listOf(
                checkin(1, "2026-08-01", null, tape(BodySite.WAIST_MIDPOINT, 85.0, 85.0)),
                checkin(2, "2026-09-01", null, tape(BodySite.WAIST_MIDPOINT, 84.5, 84.5)),
            ),
        ).single()
        assertEquals(ChangeVerdict.WITHIN_ERROR, small.verdict)
        val first = siteChanges(listOf(checkin(1, "2026-08-01", null, tape(BodySite.NECK, 38.0, 38.0)))).single()
        assertNull(first.change)
        assertNull(first.verdict)
    }

    @Test
    fun `left and right differ only when the gap is beyond error`() {
        val h = listOf(
            checkin(
                1, "2026-09-01", null,
                tape(BodySite.THIGH, 57.0, 57.0, side = BodySide.RIGHT), tape(BodySite.THIGH, 55.0, 55.0, side = BodySide.LEFT),
                tape(BodySite.CALF, 38.4, 38.4, side = BodySide.RIGHT), tape(BodySite.CALF, 38.0, 38.0, side = BodySide.LEFT),
            ),
        )
        val a = asymmetries(h).associateBy { it.site }
        assertTrue(a.getValue(BodySite.THIGH).beyondError)
        assertFalse(a.getValue(BodySite.CALF).beyondError)
    }

    @Test
    fun `ratios and estimates match their published equations`() {
        val c = checkin(
            1, "2026-09-01", null,
            tape(BodySite.NECK, 38.0, 38.0), tape(BodySite.WAIST_NAVEL, 85.0, 85.0), tape(BodySite.HIPS, 100.0, 100.0),
            fold(BodySite.SF_CHEST, 10.0, 10.0, 10.0), fold(BodySite.SF_ABDOMINAL, 20.0, 20.0, 20.0), fold(BodySite.SF_THIGH, 15.0, 15.0, 15.0),
        )
        assertEquals(85.0 / 178.0, waistToHeight(c, 178.0)!!, 1e-9)
        assertEquals(0.85, waistToHip(c)!!, 1e-9)
        assertNull(waistToHeight(c, null))
        assertEquals(16.436, navyBodyFat(c, 178.0, "male")!!, 1e-3)
        assertNull(navyBodyFat(c, 178.0, null))
        assertEquals(14.735, jacksonPollockBodyFat(c, 40, "male")!!, 1e-3)
        assertNull(jacksonPollockBodyFat(c, 40, "female"))
        // The sum needs the suprailiac fold too.
        assertNull(skinfoldSum(c))

        val f = checkin(1, "2026-09-01", null, tape(BodySite.NECK, 32.0, 32.0), tape(BodySite.WAIST_MIDPOINT, 72.0, 72.0), tape(BodySite.HIPS, 98.0, 98.0))
        assertEquals(27.426, navyBodyFat(f, 165.0, "female")!!, 1e-3)
    }

    @Test
    fun `the skinfold sum is compared only with the same caliper`() {
        fun sum(id: Long, date: String, caliper: String?, a: Double) = checkin(
            id, date, caliper,
            fold(BodySite.SF_ABDOMINAL, a, a, a), fold(BodySite.SF_SUPRAILIAC, 15.0, 15.0, 15.0), fold(BodySite.SF_THIGH, 12.0, 12.0, 12.0),
        )
        val same = skinfoldSumChange(listOf(sum(1, "2026-08-01", "Caliper A", 30.0), sum(2, "2026-09-01", "caliper a ", 20.0)))!!
        assertEquals(47.0, same.latest, 1e-9)
        assertEquals(-10.0, same.change!!, 1e-9)
        // Three sites at the 1 mm floor: 2.77 x root 3.
        assertEquals(2.77 * kotlin.math.sqrt(3.0), same.smallestReal, 1e-9)
        assertEquals(ChangeVerdict.DOWN, same.verdict)
        val other = skinfoldSumChange(listOf(sum(1, "2026-08-01", "Caliper A", 30.0), sum(2, "2026-09-01", "Caliper B", 20.0)))!!
        assertTrue(other.caliperChanged)
        assertNull(other.verdict)
    }

    @Test
    fun `the reminder waits four weeks after the last check-in and opens the sheet`() {
        val last = LocalDate.parse("2026-09-19")
        assertFalse(bodyCheckinNeeded(last, LocalDate.parse("2026-10-16")))
        assertTrue(bodyCheckinNeeded(last, LocalDate.parse("2026-10-17")))
        assertTrue(bodyCheckinNeeded(null, LocalDate.parse("2026-10-17")))
        assertEquals(NotificationDestination.BODY_CHECKIN, notificationDestination(NotificationKind.BODY_CHECKIN))
        assertTrue(NotificationKind.BODY_CHECKIN in NotificationKind.ALL)
        assertEquals(AddEntryTarget.BodyCheckIn, AddEntryChoice.BodyCheckIn.target)
        assertEquals(AddEntryGroup.Body, AddEntryChoice.BodyCheckIn.group)
    }
}
