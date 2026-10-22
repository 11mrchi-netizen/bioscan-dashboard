package com.bioscan.fieldterminal.domain.notifications

import com.bioscan.fieldterminal.data.model.NotificationKind

// Pure helpers for notification taps/actions, kept out of the Android classes so they're unit-testable.

enum class NotificationDestination { SUPPLEMENTS, LOG, CHECKIN, BODY_CHECKIN, FUEL, HEALTH, NONE }

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
    else -> NotificationDestination.NONE
}

// The ids a "LOG ALL" press should still write: de-duplicated, minus any already logged today (in the app, or by an
// earlier press), so a double-tap or an in-app log can never produce a second supplement_log row.
fun idsStillToLog(ids: List<Long>, takenToday: Set<Long>): List<Long> =
    ids.distinct().filter { it !in takenToday }
