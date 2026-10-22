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

    @Test fun unknownOrMissingKindGoesNowhere() {
        assertEquals(NotificationDestination.NONE, notificationDestination(null))
        assertEquals(NotificationDestination.NONE, notificationDestination("something_else"))
    }

    @Test fun idsStillToLogSkipsAlreadyTakenAndDuplicates() {
        assertEquals(listOf(1L, 3L), idsStillToLog(listOf(1L, 2L, 3L, 1L), setOf(2L)))
    }

    @Test fun idsStillToLogIsEmptyWhenEverythingTaken() {
        assertEquals(emptyList<Long>(), idsStillToLog(listOf(1L, 2L), setOf(1L, 2L)))
    }
}
