package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.ui.screens.today.PlanRow
import com.bioscan.fieldterminal.ui.screens.today.SoFar
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// The web Dashboard shows any day's TODAY as the phone drew it (web transition plan, phase 2, Nov 2026): each time TODAY is
// built, the plan rows and the SO FAR line go to today_plans (supabase/migrations/20261118114843_today_plans.sql). A plain,
// versioned snapshot (plan.v = 1): the web reads it and never recomputes the plan. Times are local wall clock "HH:mm".

private val HM = DateTimeFormatter.ofPattern("HH:mm")

/** The day's plan as the web reads it. */
fun todayPlanSnapshot(rows: List<PlanRow>, soFar: SoFar?): JsonObject = buildJsonObject {
    put("v", 1)
    put("rows", buildJsonArray {
        rows.forEach { r ->
            add(buildJsonObject {
                r.time?.let { put("time", it.format(HM)) }
                put("title", r.title)
                r.detail?.let { put("detail", it) }
                put("done", r.done)
                put("kind", r.kind.name)
                r.kcal?.let { put("kcal", it) }
                r.durationMin?.let { put("duration_min", it) }
            })
        }
    })
    soFar?.let { s ->
        putJsonObject("so_far") {
            put("kcal", s.kcal); s.target?.let { put("target_kcal", it) }
            put("carbs_g", s.carbsG); put("protein_g", s.proteinG); put("fat_g", s.fatG)
        }
    }
}

object TodayPlanPublisher {
    @Volatile private var lastSent: Pair<LocalDate, Int>? = null

    /** Writes the day's plan when it changed since the last write from this process. Throws on a failed write. */
    suspend fun publish(supabase: SupabaseClient, day: LocalDate, rows: List<PlanRow>, soFar: SoFar?) {
        val plan = todayPlanSnapshot(rows, soFar)
        val stamp = day to plan.hashCode()
        if (lastSent == stamp) return
        supabase.postgrest.from("today_plans").upsert(
            buildJsonObject { put("day", day.toString()); put("plan", plan); put("built_at", java.time.Instant.now().toString()) },
        ) { onConflict = "user_id,day" }
        lastSent = stamp
    }
}
