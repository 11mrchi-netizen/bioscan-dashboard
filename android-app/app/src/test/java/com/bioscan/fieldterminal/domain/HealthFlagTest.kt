package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class HealthFlagTest {

    private val today = LocalDate.of(2026, 10, 14)

    @Test
    fun dayOneIsTheStartDate() {
        assertEquals(1, healthFlagDay(HealthFlag("Knee (Right)", true, today), today))
        assertEquals(4, healthFlagDay(HealthFlag("Knee (Right)", true, today.minusDays(3)), today))
        assertNull(healthFlagDay(HealthFlag("Knee (Right)", true, null), today))
    }

    @Test
    fun injuriesWinOverIllnessesAndTheNewestInjuryIsShown() {
        val older = HealthFlag("Shoulder", true, today.minusDays(20))
        val newer = HealthFlag("Calf", true, today.minusDays(2))
        val cold = HealthFlag("Cold", false, today)
        assertEquals(newer, pickHealthFlag(listOf(older, newer), listOf(cold)))
        assertEquals(cold, pickHealthFlag(emptyList(), listOf(cold)))
        assertNull(pickHealthFlag(emptyList(), emptyList()))
    }

    @Test
    fun rightIsDrawnOnTheRightAndLeftOnTheLeft() {
        assertEquals(FigurePin(FigurePoint(150f, 146f), "R KNEE"), figurePinForBodyPart("Knee (Right)"))
        assertEquals(FigurePin(FigurePoint(110f, 146f), "L KNEE"), figurePinForBodyPart("Left knee"))
        assertEquals(FigurePin(FigurePoint(110f, 146f), "L KNEE"), figurePinForBodyPart("L knee"))
        // No side given: pinned on the right, the figure's default side, and the label does not claim a side.
        assertEquals(FigurePin(FigurePoint(150f, 146f), "KNEE"), figurePinForBodyPart("knee"))
    }

    @Test
    fun regionsLandOnTheirPartOfTheFigureWithAShortLabel() {
        assertEquals(FigurePin(FigurePoint(130f, 22f), "HEAD"), figurePinForBodyPart("Head"))
        assertEquals(FigurePin(FigurePoint(105f, 58f), "L SHOULDER"), figurePinForBodyPart("Left shoulder"))
        assertEquals(FigurePin(FigurePoint(130f, 96f), "BACK"), figurePinForBodyPart("Lower back"))
        assertEquals(FigurePin(FigurePoint(153f, 186f), "R FOOT"), figurePinForBodyPart("Plantar fascia (right)"))
        assertEquals(166f, figurePinForBodyPart("Achilles").point.y)
        assertEquals("R THIGH", figurePinForBodyPart("Right hamstring").label)
        // Forearm is the wrist/hand region, not the upper arm.
        assertEquals(FigurePin(FigurePoint(84f, 118f), "L HAND"), figurePinForBodyPart("Forearm (left)"))
        // A limb named with the word "back" is still that limb.
        assertEquals("R KNEE", figurePinForBodyPart("Back of the right knee").label)
    }

    @Test
    fun unknownPartsArePinnedAtThePelvisNotGuessedOntoALimb() {
        assertEquals(FigurePin(FigurePoint(130f, 104f), "INJURY"), figurePinForBodyPart("Tendinopathy"))
        assertEquals(FigurePin(FigurePoint(130f, 104f), "INJURY"), figurePinForBodyPart(""))
    }
}
