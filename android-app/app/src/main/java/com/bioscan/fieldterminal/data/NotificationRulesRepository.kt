package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.NewNotificationLogRow
import com.bioscan.fieldterminal.data.model.NewNotificationRuleRow
import com.bioscan.fieldterminal.data.model.NotificationConditions
import com.bioscan.fieldterminal.data.model.NotificationKind
import com.bioscan.fieldterminal.data.model.NotificationLimits
import com.bioscan.fieldterminal.data.model.NotificationLogRow
import com.bioscan.fieldterminal.data.model.NotificationOutcome
import com.bioscan.fieldterminal.data.model.NotificationPrefsRow
import com.bioscan.fieldterminal.data.model.NotificationRuleRow
import com.bioscan.fieldterminal.data.model.NotificationSchedule
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

@Serializable
private data class NewNotificationPrefsRow(
    val timezone: String?,
    @SerialName("master_enabled") val masterEnabled: Boolean = true,
)

private val DEFAULT_RULES = listOf(
    NewNotificationRuleRow(
        kind = NotificationKind.SUPPLEMENT_DUE,
        enabled = true,
        schedule = NotificationSchedule(buckets = mapOf("morning" to "08:00", "afternoon" to "13:00", "night" to "21:00")),
        conditions = NotificationConditions(skipIfLogged = true),
        limits = NotificationLimits(maxPerDay = 5, minGapMin = 60, snoozeMin = 30),
    ),
    NewNotificationRuleRow(
        kind = NotificationKind.CHECKIN_MORNING,
        enabled = true,
        schedule = NotificationSchedule(times = listOf("09:00")),
        conditions = NotificationConditions(skipIfLogged = true),
        limits = NotificationLimits(maxPerDay = 2, minGapMin = 120, snoozeMin = 60),
    ),
    NewNotificationRuleRow(
        kind = NotificationKind.CHECKIN_EVENING,
        enabled = true,
        schedule = NotificationSchedule(times = listOf("21:00")),
        conditions = NotificationConditions(skipIfLogged = true),
        limits = NotificationLimits(maxPerDay = 2, minGapMin = 120, snoozeMin = 60),
    ),
)

class NotificationRulesRepository(private val supabase: SupabaseClient) {

    // First-run seed: insert-if-missing so a rule the user already edited is
    // never overwritten. `timezone` is the device zone id, stored once.
    suspend fun ensureDefaults(timezone: String) {
        supabase.postgrest.from("notification_rules").upsert(DEFAULT_RULES) {
            onConflict = "user_id,kind"
            ignoreDuplicates = true
        }
        supabase.postgrest.from("notification_prefs").upsert(NewNotificationPrefsRow(timezone = timezone)) {
            onConflict = "user_id"
            ignoreDuplicates = true
        }
    }

    suspend fun loadRules(): List<NotificationRuleRow> =
        supabase.postgrest.from("notification_rules")
            .select(columns = Columns.list("id,kind,enabled,schedule,conditions,limits"))
            .decodeList<NotificationRuleRow>()

    suspend fun loadPrefs(): NotificationPrefsRow? =
        supabase.postgrest.from("notification_prefs")
            .select(columns = Columns.list("master_enabled,quiet_start,quiet_end,respect_sleep,daily_cap,timezone"))
            .decodeList<NotificationPrefsRow>()
            .firstOrNull()

    // Every column is put explicitly so a value equal to its Kotlin default
    // (e.g. quiet hours switched back to 23:00) is still written on conflict.
    suspend fun savePrefs(prefs: NotificationPrefsRow) {
        supabase.postgrest.from("notification_prefs").upsert(
            buildJsonObject {
                put("master_enabled", prefs.masterEnabled)
                put("quiet_enabled", prefs.quietEnabled)
                put("quiet_start", prefs.quietStart)
                put("quiet_end", prefs.quietEnd)
                put("respect_sleep", prefs.respectSleep)
                put("daily_cap", prefs.dailyCap)
                put("timezone", prefs.timezone)
            }
        ) { onConflict = "user_id" }
    }

    suspend fun appendLog(ruleKind: String, outcome: String, action: String? = null, dedupeKey: String? = null) {
        supabase.postgrest.from("notification_log").insert(
            NewNotificationLogRow(ruleKind = ruleKind, outcome = outcome, action = action, dedupeKey = dedupeKey)
        )
    }

    suspend fun setRuleEnabled(kind: String, enabled: Boolean) {
        supabase.postgrest.from("notification_rules")
            .update(buildJsonObject { put("enabled", enabled) }) { filter { eq("kind", kind) } }
    }

    // Used for daily cap / min-gap checks: what was actually shown since `since`.
    suspend fun loadShownSince(since: Instant): List<NotificationLogRow> =
        supabase.postgrest.from("notification_log")
            .select(columns = Columns.list("rule_kind,fired_at,outcome")) {
                filter {
                    eq("outcome", NotificationOutcome.SHOWN)
                    gte("fired_at", since.toString())
                }
            }
            .decodeList<NotificationLogRow>()
}
