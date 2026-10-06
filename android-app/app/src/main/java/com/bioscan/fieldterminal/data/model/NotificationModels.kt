package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object NotificationKind {
    const val SUPPLEMENT_DUE = "supplement_due"
    const val CHECKIN_MORNING = "checkin_morning"
    const val CHECKIN_EVENING = "checkin_evening"
    val ALL = listOf(SUPPLEMENT_DUE, CHECKIN_MORNING, CHECKIN_EVENING)
}

object NotificationOutcome {
    const val SHOWN = "shown"
    const val SUPPRESSED = "suppressed"
    const val ACTIONED = "actioned"
    const val SNOOZED = "snoozed"
    const val DISMISSED = "dismissed"
    const val EXPIRED = "expired"
}

// Every field defaulted so a partial / older jsonb blob always decodes (the
// training_cycles.focus crash was a shape mismatch -- don't repeat it).
@Serializable
data class NotificationSchedule(
    // Check-ins: fire at these "HH:mm" local times.
    val times: List<String> = emptyList(),
    // ISO day-of-week, 1 = Monday .. 7 = Sunday.
    val days: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7),
    // supplement_due: supplements.time_of_day bucket -> "HH:mm".
    val buckets: Map<String, String> = emptyMap(),
)

@Serializable
data class NotificationConditions(
    @SerialName("skip_if_logged") val skipIfLogged: Boolean = true,
)

@Serializable
data class NotificationLimits(
    @SerialName("max_per_day") val maxPerDay: Int = 2,
    @SerialName("min_gap_min") val minGapMin: Int = 120,
    @SerialName("snooze_min") val snoozeMin: Int = 30,
)

@Serializable
data class NotificationRuleRow(
    val id: Long = 0,
    val kind: String,
    val enabled: Boolean = true,
    val schedule: NotificationSchedule = NotificationSchedule(),
    val conditions: NotificationConditions = NotificationConditions(),
    val limits: NotificationLimits = NotificationLimits(),
)

@Serializable
data class NewNotificationRuleRow(
    val kind: String,
    val enabled: Boolean,
    val schedule: NotificationSchedule,
    val conditions: NotificationConditions,
    val limits: NotificationLimits,
)

@Serializable
data class NotificationPrefsRow(
    @SerialName("master_enabled") val masterEnabled: Boolean = true,
    @SerialName("quiet_enabled") val quietEnabled: Boolean = true,
    @SerialName("quiet_start") val quietStart: String = "23:00",
    @SerialName("quiet_end") val quietEnd: String = "07:00",
    @SerialName("respect_sleep") val respectSleep: Boolean = true,
    @SerialName("daily_cap") val dailyCap: Int = 6,
    val timezone: String? = null,
)

@Serializable
data class NotificationLogRow(
    @SerialName("rule_kind") val ruleKind: String,
    @SerialName("fired_at") val firedAt: String,
    val outcome: String,
)

@Serializable
data class NewNotificationLogRow(
    @SerialName("rule_kind") val ruleKind: String,
    val outcome: String,
    val action: String? = null,
    @SerialName("dedupe_key") val dedupeKey: String? = null,
)
