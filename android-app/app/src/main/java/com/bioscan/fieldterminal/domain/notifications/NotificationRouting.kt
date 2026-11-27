package com.bioscan.fieldterminal.domain.notifications

import com.bioscan.fieldterminal.data.model.NotificationKind

// Pure helpers for notification taps/actions, kept out of the Android classes so they're unit-testable.

enum class NotificationDestination { SUPPLEMENTS, LOG, CHECKIN, BODY_CHECKIN, FUEL, HEALTH, EVENTS, ROUTINE, EXPOSURES, REPORT, NONE }

// Where tapping a notification of this kind should land in the app.
fun notificationDestination(kind: String?): NotificationDestination = when (kind) {
    NotificationKind.SUPPLEMENT_DUE -> NotificationDestination.SUPPLEMENTS
    NotificationKind.CHECKIN_MORNING,
    NotificationKind.CHECKIN_EVENING,
    NotificationKind.WELLBEING_CHECKIN,
    NotificationKind.EVENING_LOG -> NotificationDestination.LOG
    // The structured check-in opens its own sheet on the Today page.
    NotificationKind.EVENING_CHECKIN -> NotificationDestination.CHECKIN
    // The body check-in opens its sheet on Fuel › Body, where its results are shown.
    NotificationKind.BODY_CHECKIN -> NotificationDestination.BODY_CHECKIN
    // Fuel reminders open Fuel › Nutrition, where today's plan is.
    NotificationKind.FUEL_SESSION, NotificationKind.FUEL_EVENT -> NotificationDestination.FUEL
    // The health check-in opens Health › CONCERNS, where CHECK IN is.
    NotificationKind.HEALTH_CHECKIN -> NotificationDestination.HEALTH
    // Rating, drawing and follow-ups all happen on Lab › N=1, in the repeatable-interventions card.
    NotificationKind.EVENT_RATING, NotificationKind.EVENT_DRAW, NotificationKind.EVENT_FOLLOWUP -> NotificationDestination.EVENTS
    // A practice reminder (and the routine timer) open Me › ROUTINE, where PRACTICES and TIME are.
    NotificationKind.PRACTICE_DUE -> NotificationDestination.ROUTINE
    // A due questionnaire opens Me › EXPOSURES, where TAKE is.
    NotificationKind.QUESTIONNAIRE_DUE -> NotificationDestination.EXPOSURES
    // A weekly or monthly report opens its page in the app, over Lab › N=1 (where the REPORTS card is).
    NotificationKind.PERIOD_REPORT -> NotificationDestination.REPORT
    else -> NotificationDestination.NONE
}

// The ids a "LOG ALL" press should still write: de-duplicated, and only those whose slot is still open. [openNow] is
// what supplementsDueForBucket says is due for the notification's bundle at the time of the press (it counts doses per
// slot: vitamin C taken in the morning is still due in the afternoon and at night). A day-level "already taken today"
// test would drop every later bundle's dose of an item that sits in several bundles. Null (a notification posted before
// the bundle travelled with it): the notification's own list, de-duplicated; it was cancelled on the first tap.
fun idsStillToLog(ids: List<Long>, openNow: Set<Long>?): List<Long> =
    ids.distinct().filter { openNow == null || it in openNow }
