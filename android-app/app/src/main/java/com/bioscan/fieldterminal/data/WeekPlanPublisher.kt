package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.fuel.FoodOption
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// The web's meal prep portions its containers from the same week the phone works out (console MEAL PREP, Nov 2026):
// each time the phone computes a prep week (FuelPlanRepository.loadWeekPlan), it writes the coded days and the foods to
// fuel_week_plans (supabase/migrations/20261117073643_fuel_week_plans.sql). A plain, versioned snapshot: the web reads
// targets and foods, never the phone's internal types. Meal times are local wall-clock "HH:mm".

private val HM = DateTimeFormatter.ofPattern("HH:mm")

private fun foodJson(f: FoodOption): JsonObject = buildJsonObject {
    put("id", f.id); put("name", f.name); put("role", f.role.name)
    putJsonArray("slots") { f.slots.forEach { add(JsonPrimitive(it.name)) } }
    put("carb_per_g", f.carbPerG); put("protein_per_g", f.proteinPerG); put("fat_per_g", f.fatPerG); put("fibre_per_g", f.fibrePerG); put("kcal_per_g", f.kcalPerG)
    put("typical_g", f.typicalG); put("min_g", f.minG); put("max_g", f.maxG); put("step_g", f.stepG)
    f.serving?.let { s -> putJsonObject("serving") { put("singular", s.singular); put("plural", s.plural); put("grams", s.grams); put("step", s.step) } }
    put("unit", f.unit); put("taste", f.taste.name); put("yours", f.yours)
    putJsonObject("uses") { f.uses.forEach { (k, n) -> put(k.name, n) } }
}

/** The week as the web reads it (plan.v = 1). */
fun weekPlanSnapshot(plan: WeekPlan, zone: ZoneId): JsonObject = buildJsonObject {
    put("v", 1)
    put("weight_kg", plan.weightKg)
    putJsonArray("days") {
        plan.days.forEach { d ->
            add(buildJsonObject {
                put("date", d.date.toString())
                putJsonArray("meals") {
                    d.meals.forEach { m ->
                        add(buildJsonObject {
                            put("kind", m.kind.name); put("time", m.time.withZoneSameInstant(zone).toLocalTime().format(HM)); put("code", m.code.name)
                            put("carb_low_g", m.carbLowG); put("carb_high_g", m.carbHighG); put("protein_g", m.proteinG)
                            m.fatG?.let { put("fat_g", it) }
                            put("before_key_session", m.beforeKeySession); put("reason", m.reason)
                        })
                    }
                }
                putJsonArray("sessions") {
                    d.sessions.forEach { s ->
                        add(buildJsonObject {
                            put("title", s.item.title); put("type", s.item.type); put("intensity", s.item.intensity.name); put("duration_min", s.item.durationMin)
                            s.item.start?.let { put("start", it.withZoneSameInstant(zone).toLocalTime().format(HM)) }
                        })
                    }
                }
            })
        }
    }
    put("foods", buildJsonArray { plan.foods.forEach { add(foodJson(it)) } })
}

suspend fun publishWeekPlan(supabase: SupabaseClient, weekStart: LocalDate, plan: WeekPlan, zone: ZoneId) {
    supabase.postgrest.from("fuel_week_plans").upsert(
        buildJsonObject {
            put("week_start", weekStart.toString())
            put("plan", weekPlanSnapshot(plan, zone))
            put("engine_version", FUEL_ENGINE_VERSION)
            put("computed_at", java.time.Instant.now().toString())
        },
    ) { onConflict = "user_id,week_start" }
}
