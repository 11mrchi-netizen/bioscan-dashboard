package com.bioscan.fieldterminal.data

import android.content.Context
import com.bioscan.fieldterminal.domain.WallClock
import com.bioscan.fieldterminal.domain.risk.ChecklistAnswer
import com.bioscan.fieldterminal.domain.risk.ChecklistDefs
import com.bioscan.fieldterminal.domain.risk.HOT_DAY_C
import com.bioscan.fieldterminal.domain.risk.HeatSummary
import com.bioscan.fieldterminal.domain.risk.PreventiveContext
import com.bioscan.fieldterminal.domain.risk.PreventiveDefs
import com.bioscan.fieldterminal.domain.risk.PreventiveRecord
import com.bioscan.fieldterminal.domain.risk.SubstanceSummary
import com.bioscan.fieldterminal.domain.risk.parseChecklist
import com.bioscan.fieldterminal.domain.risk.parsePreventive
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Risk profile R4 (supabase/migrations/20261119160000_home_checklist_preventive.sql): the home and water checklist, the
// preventive records, and the substance and heat summaries read from what is already logged. Every read is soft.

@Serializable
private data class ChecklistRow(val item: String, val value: String, @SerialName("answered_at") val answeredAt: String)

@Serializable
private data class NewChecklistRow(val item: String, val value: String, @SerialName("defs_version") val defsVersion: String)

@Serializable
data class PreventiveRow(
    val id: Long,
    val item: String,
    @SerialName("done_on") val doneOn: String? = null,
    val method: String? = null,
    @SerialName("not_applicable") val notApplicable: Boolean = false,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
private data class NewPreventiveRow(
    val item: String,
    @SerialName("done_on") val doneOn: String?,
    val method: String?,
    @SerialName("not_applicable") val notApplicable: Boolean,
)

@Serializable
private data class PrMealItem(@SerialName("alcohol_g") val alcoholG: Double? = null, @SerialName("caffeine_mg") val caffeineMg: Double? = null)

@Serializable
private data class PrMealRow(@SerialName("logged_at") val loggedAt: String, @SerialName("meal_items") val items: List<PrMealItem> = emptyList())

@Serializable
private data class PrContextRow(val date: String, @SerialName("temp_max_c") val tempMaxC: Double? = null)

@Serializable
private data class PrSessionRow(@SerialName("start_time") val startTime: String)

/** What Me › EXPOSURES shows for R4. */
data class PreventionData(
    val checklist: List<ChecklistAnswer>,
    val records: List<PreventiveRow>,
    val substances: SubstanceSummary,
    val heat: HeatSummary?,
)

fun PreventiveRow.toDomain(zone: ZoneId = ZoneId.systemDefault()) = PreventiveRecord(
    item = item,
    date = doneOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    method = method,
    notApplicable = notApplicable,
    // created_at is a real instant: its local date
    created = runCatching { Instant.parse(createdAt).atZone(zone).toLocalDate() }.getOrElse { LocalDate.MIN },
)

/** auto keys of preventive.json to dates from the person's data. */
fun preventiveContext(screens: RiskScreens?, defs: PreventiveDefs): PreventiveContext {
    val autoDates = defs.items.mapNotNull { it.auto }.distinct().mapNotNull { key ->
        val date = when {
            key == "bp" -> screens?.bpLast
            key.startsWith("lab:") -> {
                val names = key.removePrefix("lab:").split('|')
                screens?.labDates?.filterKeys { m -> names.any { m.contains(it) } }?.values?.maxOrNull()
            }
            else -> null
        }
        date?.let { key to it }
    }.toMap()
    val i = screens?.inputs
    return PreventiveContext(i?.age, i?.sex, i?.bmi?.value, i?.background?.smoking, screens?.prevent10y, autoDates)
}

class PreventionRepository(private val supabase: SupabaseClient) {

    suspend fun load(today: LocalDate = LocalDate.now(), sessionsHighUv: Int = 0): PreventionData {
        val zone = ZoneId.systemDefault()
        val checklist = catching {
            supabase.postgrest.from("home_checklist_answers").select(Columns.list("item,value,answered_at")).decodeList<ChecklistRow>()
        }.getOrElse { emptyList() }.mapNotNull { r ->
            runCatching { ChecklistAnswer(r.item, r.value, Instant.parse(r.answeredAt).atZone(zone).toLocalDate()) }.getOrNull()
        }
        val records = catching {
            supabase.postgrest.from("preventive_records").select(Columns.list("id,item,done_on,method,not_applicable,created_at")).decodeList<PreventiveRow>()
        }.getOrElse { emptyList() }

        val since = today.minusDays(SCREEN_WINDOW_DAYS)
        val meals = catching {
            supabase.postgrest.from("meals").select(Columns.list("logged_at,meal_items(alcohol_g,caffeine_mg)")) {
                filter { gte("logged_at", since.toString()) }
            }.decodeList<PrMealRow>()
        }.getOrNull()
        val substances = if (meals.isNullOrEmpty()) SubstanceSummary(null, null, null, SCREEN_WINDOW_DAYS.toInt()) else {
            val alcohol = meals.sumOf { m -> m.items.sumOf { it.alcoholG ?: 0.0 } }
            val caffeine = meals.sumOf { m -> m.items.sumOf { it.caffeineMg ?: 0.0 } }
            // logged_at is a logged time: its digits are the local clock
            val late = meals.filter { m -> m.items.any { (it.caffeineMg ?: 0.0) > 0 } && (WallClock.read(m.loggedAt)?.hour ?: 0) >= 14 }
                .mapNotNull { WallClock.date(it.loggedAt) }.distinct().size
            SubstanceSummary(alcohol * 7 / SCREEN_WINDOW_DAYS, caffeine / SCREEN_WINDOW_DAYS, late, SCREEN_WINDOW_DAYS.toInt())
        }

        val heat = catching {
            val hotDays = supabase.postgrest.from("daily_context").select(Columns.list("date,temp_max_c")) {
                filter { gte("date", since.toString()) }
            }.decodeList<PrContextRow>().filter { (it.tempMaxC ?: 0.0) >= HOT_DAY_C }.map { it.date }.toSet()
            val sessionDays = supabase.postgrest.from("exercise_sessions").select(Columns.list("start_time")) {
                filter { gte("start_time", since.toString()) }
            }.decodeList<PrSessionRow>().mapNotNull { WallClock.date(it.startTime)?.toString() }
            HeatSummary(hotDays.size, sessionDays.count { it in hotDays }, sessionsHighUv, SCREEN_WINDOW_DAYS.toInt())
        }.getOrNull()

        return PreventionData(checklist, records, substances, heat)
    }

    suspend fun saveChecklist(defs: ChecklistDefs, answers: Map<String, String>) {
        if (answers.isEmpty()) return
        supabase.postgrest.from("home_checklist_answers").insert(answers.map { (k, v) -> NewChecklistRow(k, v, defs.version) })
    }

    suspend fun addRecord(item: String, doneOn: LocalDate?, method: String?, notApplicable: Boolean) {
        supabase.postgrest.from("preventive_records").insert(NewPreventiveRow(item, doneOn?.toString(), method, notApplicable))
    }

    suspend fun deleteRecord(id: Long) {
        supabase.postgrest.from("preventive_records").delete { filter { eq("id", id) } }
    }

    companion object {
        @Volatile private var checklist: ChecklistDefs? = null
        @Volatile private var preventive: PreventiveDefs? = null

        fun checklistDefs(context: Context): ChecklistDefs =
            checklist ?: context.applicationContext.assets.open("home-checklist.json").bufferedReader().use { parseChecklist(it.readText()) }.also { checklist = it }

        fun preventiveDefs(context: Context): PreventiveDefs =
            preventive ?: context.applicationContext.assets.open("preventive.json").bufferedReader().use { parsePreventive(it.readText()) }.also { preventive = it }
    }
}
