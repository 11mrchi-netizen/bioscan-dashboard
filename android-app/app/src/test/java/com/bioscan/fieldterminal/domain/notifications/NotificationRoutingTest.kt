package com.bioscan.fieldterminal.domain.notifications

import com.bioscan.fieldterminal.data.model.NotificationKind
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationRoutingTest {

    @Test fun supplementReminderOpensSupplements() {
        assertEquals(NotificationDestination.SUPPLEMENTS, notificationDestination(NotificationKind.SUPPLEMENT_DUE))
    }

    @Test fun checkinAndLogKindsOpenLog() {
        listOf(
            NotificationKind.CHECKIN_MORNING,
            NotificationKind.CHECKIN_EVENING,
            NotificationKind.WELLBEING_CHECKIN,
            NotificationKind.EVENING_LOG,
        ).forEach { assertEquals(it, NotificationDestination.LOG, notificationDestination(it)) }
    }

    @Test fun interventionRemindersOpenTheRepeatableCard() {
        listOf(NotificationKind.EVENT_RATING, NotificationKind.EVENT_DRAW, NotificationKind.EVENT_FOLLOWUP)
            .forEach {
                assertEquals(it, NotificationDestination.EVENTS, notificationDestination(it))
                org.junit.Assert.assertTrue(it in NotificationKind.ALL)
            }
    }

    @Test fun unknownOrMissingKindGoesNowhere() {
        assertEquals(NotificationDestination.NONE, notificationDestination(null))
        assertEquals(NotificationDestination.NONE, notificationDestination("something_else"))
    }

    @Test fun idsStillToLogKeepsOnlyOpenSlotsAndDuplicatesOnce() {
        assertEquals(listOf(1L, 3L), idsStillToLog(listOf(1L, 2L, 3L, 1L), setOf(1L, 3L)))
    }

    @Test fun idsStillToLogIsEmptyWhenNothingOpen() {
        assertEquals(emptyList<Long>(), idsStillToLog(listOf(1L, 2L), emptySet()))
    }

    // Vitamin C sits in morning, afternoon and night: taken once this morning, it is still due in the afternoon bundle.
    @Test fun anItemInSeveralBundlesIsStillLoggedByTheLaterBundle() {
        assertEquals(listOf(22L, 7L), idsStillToLog(listOf(22L, 7L), setOf(22L, 7L)))
        assertEquals(listOf(22L, 7L), idsStillToLog(listOf(22L, 7L), null))
    }
}
